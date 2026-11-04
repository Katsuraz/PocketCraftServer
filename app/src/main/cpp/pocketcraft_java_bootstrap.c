// PocketCraft native bootstrap: executable code installed from APK as a native library.
// Android targetSdk 29+ forbids exec() of writable app data (including extracted bin/java).
// This program starts OpenJDK via its packaged native libjvm.so instead.
#include <jni.h>
#include <dlfcn.h>
#include <errno.h>
#include <limits.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

typedef jint (JNICALL *JvmCreate)(JavaVM **, void **, void *);
static int fail(const char *message) {
    fprintf(stderr, "[PocketCraft native Java] %s\n", message);
    fflush(stderr);
    return 1;
}
static int java_error(JNIEnv *env, const char *what) {
    fprintf(stderr, "[PocketCraft native Java] %s\n", what);
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionDescribe(env);
        (*env)->ExceptionClear(env);
    }
    return 1;
}
static char *option(const char *key, const char *value) {
    size_t n = strlen(key) + strlen(value) + 2;
    char *s = malloc(n);
    if (!s) return NULL;
    snprintf(s, n, "%s=%s", key, value);
    return s;
}
int main(int argc, char **argv) {
    // --probe JAVA_HOME NATIVE_LIB_DIR TMPDIR
    // --server JAVA_HOME NATIVE_LIB_DIR TMPDIR HEAP_MB LAUNCHER_JAR MAIN_CLASS
    if (!((argc == 5 && !strcmp(argv[1], "--probe")) ||
          (argc == 8 && !strcmp(argv[1], "--server"))))
        return fail("Invalid bootstrap arguments");
    const int server = !strcmp(argv[1], "--server");
    const char *home = argv[2], *libs = argv[3], *temp = argv[4];

    if (setenv("JAVA_HOME", home, 1) || setenv("TMPDIR", temp, 1))
        return fail("Unable to configure Java environment");

    /*
     * HotSpot derives the boot module image location from the path returned
     * by dladdr(libjvm.so), not from -Djava.home. The previously APK-packaged
     * libjvm.so lived under /data/app/.../lib/arm64, so HotSpot looked for
     * /data/app/.../lib/modules and emitted "Failed setting boot class path".
     *
     * Always load libjvm.so from the extracted JRE's ORIGINAL
     * <java.home>/lib/server location. Android disallows execve() for
     * user-writable files at targetSdk>=29; dlopen() of a normal PIC library
     * is a separate operation. Devices may still restrict that dlopen().
     */
    char jvm[PATH_MAX];
    if (snprintf(jvm, sizeof jvm, "%s/lib/server/libjvm.so", home) >= (int)sizeof jvm)
        return fail("Java runtime path too long");
    char module_image[PATH_MAX];
    if (snprintf(module_image, sizeof module_image, "%s/lib/modules", home) >= (int)sizeof module_image)
        return fail("Java modules path too long");
    if (access(module_image, R_OK)) {
        fprintf(stderr, "[PocketCraft native Java] Missing Java boot modules: %s (%s)\n",
                module_image, strerror(errno));
        return 2;
    }
    fprintf(stderr, "[PocketCraft native Java] Loading JVM from %s\n", jvm);
    fflush(stderr);
    void *handle = dlopen(jvm, RTLD_NOW | RTLD_GLOBAL);
    if (!handle) {
        fprintf(stderr, "[PocketCraft native Java] Android rejected runtime libjvm.so: %s\n", dlerror());
        fprintf(stderr, "[PocketCraft native Java] Java's .so files may be blocked by this device's app-data execution policy.\n");
        return 2;
    }
    JvmCreate create = (JvmCreate)dlsym(handle, "JNI_CreateJavaVM");
    if (!create) return fail("libjvm.so does not expose JNI_CreateJavaVM");

    JavaVMOption opts[16];
    int count = 0;
    char *allocated[16] = {0};
    int n = 0;
    #define OPT(s) do { opts[count++].optionString = (char *)(s); } while (0)
    #define OPTVAL(k,v) do { \
        allocated[n] = option((k),(v)); \
        if (!allocated[n]) return fail("Out of memory"); \
        opts[count++].optionString = allocated[n++]; \
    } while (0)
    OPTVAL("-Djava.home", home);
    OPTVAL("-Dsun.boot.library.path", libs);
    OPTVAL("-Djava.library.path", libs);
    OPTVAL("-Djava.io.tmpdir", temp);
    OPT("-Djava.awt.headless=true");
    OPT("-Djavax.imageio.useCache=false");
    if (server) {
        char *classpath = argv[6];
        OPTVAL("-Djava.class.path", classpath);
        char memory[48];
        snprintf(memory, sizeof memory, "-Xmx%sm", argv[5]);
        // memory is on the stack and lives through JNI_CreateJavaVM.
        OPT("-Xms256m");
        OPT(memory);
        OPTVAL("-Dsun.java.command", argv[6]);
    }

    JavaVMInitArgs vm_args = { 0 };
    vm_args.version = JNI_VERSION_1_6;
    vm_args.nOptions = count;
    vm_args.options = opts;
    vm_args.ignoreUnrecognized = JNI_FALSE;
    JavaVM *vm = NULL;
    JNIEnv *env = NULL;
    jint rc = create(&vm, (void **)&env, &vm_args);
    if (rc != JNI_OK || !env) {
        fprintf(stderr, "[PocketCraft native Java] JNI_CreateJavaVM failed: %d\n", rc);
        return 3;
    }
    for (int i=0; i<n; i++) free(allocated[i]);

    if (!server) {
        jclass system = (*env)->FindClass(env, "java/lang/System");
        if (!system) return java_error(env, "Could not initialize java.lang.System");
        jmethodID get = (*env)->GetStaticMethodID(env, system, "getProperty", "(Ljava/lang/String;)Ljava/lang/String;");
        if (!get) return java_error(env, "Could not read java.version");
        jstring key = (*env)->NewStringUTF(env, "java.version");
        jstring value = (jstring)(*env)->CallStaticObjectMethod(env, system, get, key);
        if ((*env)->ExceptionCheck(env)) return java_error(env, "Java self-test failed");
        const char *version = value ? (*env)->GetStringUTFChars(env, value, NULL) : NULL;
        printf("PocketCraft OpenJDK %s (JNI boot)\n", version ? version : "unknown");
        fflush(stdout);
        if (version) (*env)->ReleaseStringUTFChars(env, value, version);
        (*vm)->DestroyJavaVM(vm);
        return 0;
    }

    char mainClass[512];
    size_t len = strlen(argv[7]);
    if (len == 0 || len >= sizeof(mainClass)) return fail("Invalid server main class");
    for (size_t i=0; i<len; i++) {
        char ch = argv[7][i];
        mainClass[i] = ch == '.' ? '/' : ch;
    }
    mainClass[len] = 0;
    jclass app = (*env)->FindClass(env, mainClass);
    if (!app) return java_error(env, "Fabric main class not found (check server JAR and libraries)");
    jmethodID main = (*env)->GetStaticMethodID(env, app, "main", "([Ljava/lang/String;)V");
    if (!main) return java_error(env, "Fabric launcher has no static main(String[])");
    jclass str = (*env)->FindClass(env, "java/lang/String");
    if (!str) return java_error(env, "Could not initialize Java String class");
    jobjectArray params = (*env)->NewObjectArray(env, 1, str, NULL);
    jstring nogui = (*env)->NewStringUTF(env, "nogui");
    (*env)->SetObjectArrayElement(env, params, 0, nogui);
    printf("[PocketCraft native Java] Launching %s from %s\n", argv[7], argv[6]);
    fflush(stdout);
    (*env)->CallStaticVoidMethod(env, app, main, params);
    if ((*env)->ExceptionCheck(env)) return java_error(env, "Fabric main() threw an exception");
    (*vm)->DestroyJavaVM(vm);
    return 0;
}
