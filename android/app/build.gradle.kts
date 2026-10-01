plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.clashaiaa.overlay"
    compileSdk = 34

    defaultConfig {
        applicationId = "dev.clashaiaa.overlay"
        minSdk = 26
        targetSdk = 34
        versionCode = 10
        versionName = "2.8-history-reconcile"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    // Reuse the exact offline card table and art already generated for the
    // Windows HUD.  Nothing is downloaded by the Android app at runtime.
    sourceSets["main"].assets.srcDir(rootProject.file("../assets/cards"))
}

dependencies {
    // The probe endpoint and JSON parser use only Android platform APIs.
    // org.json is a real implementation only in JVM unit tests; on device the
    // platform class is used.
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
