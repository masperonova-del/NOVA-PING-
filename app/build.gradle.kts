plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val persistentDebugKeyPath = System.getenv("NOVA_KEYSTORE_PATH")
val persistentDebugSigningEnabled = !persistentDebugKeyPath.isNullOrBlank()

android {
    namespace = "com.novaping.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.novaping.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 36
        versionName = "1.22.0-dev"
    }

    signingConfigs {
        if (persistentDebugSigningEnabled) {
            create("persistentDebug") {
                storeFile = file(persistentDebugKeyPath!!)
                storePassword = System.getenv("NOVA_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("NOVA_KEY_ALIAS")
                // The key was generated with the same password as the keystore.
                keyPassword = System.getenv("NOVA_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            // Keep the build type as Debug; only make its signing certificate persistent.
            if (persistentDebugSigningEnabled) {
                signingConfig = signingConfigs.getByName("persistentDebug")
            }
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("com.wireguard.android:tunnel:1.0.20260102")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.3")
    testImplementation("junit:junit:4.13.2")
}
