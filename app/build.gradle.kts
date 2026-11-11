import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "ru.rusinstaller"
    compileSdk = 36

    defaultConfig {
        applicationId = "ru.ruarm"
        minSdk = 30
        targetSdk = 28
        versionCode = 4
        versionName = "1.0.0"
        ndk { abiFilters += "arm64-v8a" }
    }

    buildFeatures {
        compose = true
        aidl = true
        buildConfig = true
    }

    androidResources {
        // the Wine archive is already xz-compressed
        noCompress += "xz"
    }

    packaging {
        jniLibs {
            // native tools are executed from nativeLibraryDir, so they must be extracted on install
            useLegacyPackaging = true
            keepDebugSymbols += "**/*.so"
        }
    }

    lint {
        // targetSdk 28 is required to run the built-in Wine from the app folder (like Winlator/GameNative)
        disable += "ExpiredTargetSdkVersion"
        checkReleaseBuilds = false
    }

    // Release signing: keystore.properties + ruarm-release.jks next to the project (never committed).
    val ksFile = rootProject.file("keystore.properties")
    signingConfigs {
        if (ksFile.exists()) {
            val ks = Properties().apply { ksFile.inputStream().use { load(it) } }
            create("release") {
                storeFile = rootProject.file(ks.getProperty("storeFile"))
                storePassword = ks.getProperty("storePassword")
                keyAlias = ks.getProperty("keyAlias")
                keyPassword = ks.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (ksFile.exists()) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.06.00"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.1")
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
}
