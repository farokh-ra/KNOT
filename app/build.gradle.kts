import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing material is read from signing/keystore.properties, which is git-ignored.
// When it is absent (fresh clone, CI without the key) the release build stays unsigned
// instead of silently falling back to a debug key.
val keystorePropsFile: File = rootProject.file("signing/keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) {
        keystorePropsFile.inputStream().use { load(it) }
    }
}

android {
    namespace = "dev.twoperson.messenger"
    compileSdk = 34

    defaultConfig {
        applicationId = "dev.twoperson.messenger"
        minSdk = 26
        targetSdk = 34
        // 4 / 1.2.1: connected status names the route that actually carried the link. The
        // package id and signing key stay stable so an existing install can be upgraded.
        versionCode = 4
        versionName = "1.2.1"
        vectorDrawables.useSupportLibrary = true
    }

    signingConfigs {
        if (keystorePropsFile.exists()) {
            create("release") {
                storeFile = rootProject.file(
                    keystoreProps.getProperty("storeFile") ?: "signing/release.jks"
                )
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
                // Explicit schemes instead of AGP defaults: v2 and v3 cover every device
                // this app supports (minSdk 26). v1 is off because APK Signature Scheme v2
                // supersedes it on API 24+ and the JAR signature only adds bytes here.
                enableV1Signing = false
                enableV2Signing = true
                enableV3Signing = true
                enableV4Signing = false
            }
        }
    }

    buildTypes {
        getByName("release") {
            // No shrinking: the release path must not depend on rules that were never
            // exercised on a device in this environment.
            isMinifyEnabled = false
            isShrinkResources = false
            if (keystorePropsFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        getByName("debug") {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    packaging {
        resources.excludes += setOf(
            "META-INF/LICENSE*",
            "META-INF/NOTICE*",
            "META-INF/*.kotlin_module",
            "META-INF/*.version",
            "META-INF/AL2.0",
            "META-INF/LGPL2.1",
            "DebugProbesKt.bin"
        )
    }

    lint {
        abortOnError = false
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // QR generation (pure Java, no native code).
    implementation("com.google.zxing:core:3.5.3")
    // QR scanning: activity-based scanner, bundled decoder, no Play Services dependency.
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
}
