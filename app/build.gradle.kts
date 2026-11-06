plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.pocketcraft.server"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.pocketcraft.server"
        minSdk = 26
        targetSdk = 34
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        versionCode = 67
        versionName = "0.8.42"
    }
    signingConfigs {
        create("pocketcraftUpdates") {
            val keyPath = System.getenv("POCKETCRAFT_SIGNING_KEYSTORE")
            val password = System.getenv("POCKETCRAFT_SIGNING_PASSWORD")
            if (!keyPath.isNullOrBlank() && !password.isNullOrBlank()) {
                storeFile = file(keyPath)
                storePassword = password
                keyAlias = "pocketcraft"
                keyPassword = password
            }
        }
    }
    buildTypes {
        getByName("debug") {
            if (!System.getenv("POCKETCRAFT_SIGNING_KEYSTORE").isNullOrBlank()) {
                signingConfig = signingConfigs.getByName("pocketcraftUpdates")
            }
        }
    }
    packaging {
        jniLibs {
            // Executables masquerading as native libraries must be extracted
            // and remain unstripped; Android 10+ forbids executing app-data ELF.
            useLegacyPackaging = true
            // Emulator CI uses an x86_64 Android image. Real releases retain all ARM64 JNI.
            // This build flag is exclusive to the instrumented UI smoke-test job.
            if (providers.gradleProperty("pocketcraft.uiSmokeOnly").orNull == "true") {
                excludes.add("**/arm64-v8a/**")
            }
            keepDebugSymbols.add("**/libpocketjava.so")
            keepDebugSymbols.add("**/libpocketplayitcli.so")
            keepDebugSymbols.add("**/libpocketplayitd.so")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}


dependencies {
    implementation("org.apache.commons:commons-compress:1.27.1")
    implementation("org.tukaani:xz:1.10")
    implementation("androidx.core:core:1.13.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
}
