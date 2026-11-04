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
        targetSdk = 28
        versionCode = 9
        versionName = "0.6.1"
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
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}


dependencies {
    implementation("org.apache.commons:commons-compress:1.27.1")
    implementation("org.tukaani:xz:1.10")
    testImplementation("junit:junit:4.13.2")
}
