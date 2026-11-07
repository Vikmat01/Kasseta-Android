import java.util.Properties

plugins {
    id("com.android.application")
}

// release key: keystore.properties next to settings.gradle.kts (not in git), or the CI secrets
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.kasseta.player"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.kasseta.player"
        minSdk = 29          // Android 10
        targetSdk = 37
        versionCode = 4
        versionName = "1.2.1"
        ndk {
            // HiFi players are ARM; the FFmpeg configuration is prepared for these two ABIs
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    signingConfigs {
        if (keystoreProps.containsKey("storeFile")) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // the release key keeps updates installable over each other; without it, the debug key
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // no libraries: the UI is drawn by hand and decoding is done by the bundled FFmpeg
}
