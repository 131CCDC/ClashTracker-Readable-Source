plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// The probe binary is an artifact of probe/build_probe.ps1, not a source file, so it is
// staged into the assets at build time instead of being committed.
val probeArtifact = rootProject.file("../probe/artifacts/candidates/stable-candidate/libscid_sdk.so")
val stagedAssetsDir = layout.buildDirectory.dir("staged-assets").get().asFile

val stageProbe by tasks.registering {
    description = "Stage the compiled Clashaiaa probe into the installer assets"
    inputs.file(probeArtifact)
    outputs.dir(stagedAssetsDir)
    doLast {
        if (!probeArtifact.isFile) {
            throw GradleException("probe artifact missing: $probeArtifact (build it with probe/build_probe.ps1)")
        }
        stagedAssetsDir.mkdirs()
        probeArtifact.copyTo(File(stagedAssetsDir, "libscid_sdk.so"), overwrite = true)
    }
}

android {
    namespace = "dev.clashaiaa.installer"
    compileSdk = 34

    defaultConfig {
        applicationId = "dev.clashaiaa.installer"
        minSdk = 26
        targetSdk = 34
        versionCode = 3
        versionName = "2.1-tablet"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    sourceSets["main"].assets.srcDir(stagedAssetsDir)

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
}

tasks.named("preBuild") { dependsOn(stageProbe) }
