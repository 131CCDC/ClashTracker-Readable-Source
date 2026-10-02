import groovy.json.JsonSlurper
import java.io.File

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

// ---------------------------------------------------------------------------
// Card asset preflight
//
// :app reads its assets straight out of ../assets/cards, so a build that ships
// without cards.json or the card-id PNGs produces a HUD full of "?" tiles.
// APK assembly/packaging (debug and release) is gated on a cheap validation of
// that directory; pure JVM unit tests are not.
// ---------------------------------------------------------------------------
val cardAssetsDir = rootProject.file("../assets/cards")
val minCardArtCoverage = 0.9

val verifyCardAssets by tasks.registering {
    group = "verification"
    description = "Fails the build when the shared ClashTracker card assets are missing or incomplete."

    val assetsDir = cardAssetsDir
    outputs.upToDateWhen { false }

    doLast {
        fun failCardAssets(reason: String): Nothing = throw GradleException(
            "ClashTracker card assets are missing.\n" +
                "Restore assets/cards/cards.json and card-id PNG files before building.\n" +
                "Details: $reason"
        )

        val table = File(assetsDir, "cards.json")
        if (!table.isFile || table.length() == 0L) {
            failCardAssets("${table.absolutePath} is missing or empty")
        }

        val root = try {
            JsonSlurper().parseText(table.readText(Charsets.UTF_8))
        } catch (error: Exception) {
            failCardAssets("cards.json is not valid JSON (${error.message})")
        }
        val byId = (root as? Map<*, *>)?.get("by_id") as? Map<*, *>
            ?: failCardAssets("cards.json has no by_id table")
        val ids = byId.keys.mapNotNull { it?.toString()?.toIntOrNull() }.distinct()
        if (ids.isEmpty()) {
            failCardAssets("cards.json by_id table is empty")
        }

        val missing = ids.filter { id ->
            val art = File(assetsDir, "$id.png")
            !art.isFile || art.length() == 0L
        }
        val covered = ids.size - missing.size
        val coverage = covered.toDouble() / ids.size
        if (covered == 0 || coverage < minCardArtCoverage) {
            val more = if (missing.size > 8) ", ..." else ""
            failCardAssets(
                "only $covered/${ids.size} card ids have PNG art " +
                    "(missing: ${missing.take(8).joinToString(", ")}$more)"
            )
        }

        logger.lifecycle(
            "ClashTracker card assets OK: $covered/${ids.size} card ids have art " +
                "(${"%.1f".format(coverage * 100)}% coverage)"
        )
    }
}

tasks.matching {
    (it.name.startsWith("assemble") || it.name.startsWith("package")) &&
        !it.name.contains("UnitTest") &&
        !it.name.endsWith("Resources") &&
        !it.name.endsWith("Assets")
}.configureEach {
    dependsOn(verifyCardAssets)
}

dependencies {
    // The probe endpoint and JSON parser use only Android platform APIs.
    // org.json is a real implementation only in JVM unit tests; on device the
    // platform class is used.
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
