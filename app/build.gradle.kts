import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

/**
 * Release signing. CI passes the key through environment variables (from GitHub secrets); on the Mac,
 * keystore.properties next to this project (gitignored) points at the same key, so builds from
 * Android Studio and from GitHub Releases can update each other. Without either, the debug key is used.
 */
val signingProps: Map<String, String>? = run {
    System.getenv("DIACRITICA_KEYSTORE")?.takeIf { it.isNotBlank() }?.let {
        return@run mapOf(
            "storeFile" to it,
            "storePassword" to System.getenv("DIACRITICA_KEYSTORE_PASSWORD").orEmpty(),
            "keyAlias" to System.getenv("DIACRITICA_KEY_ALIAS").orEmpty(),
            "keyPassword" to System.getenv("DIACRITICA_KEY_PASSWORD").orEmpty(),
        )
    }
    val f = rootProject.file("keystore.properties")
    if (!f.exists()) return@run null
    val p = Properties().apply { f.inputStream().use { load(it) } }
    p.stringPropertyNames().associateWith { p.getProperty(it) }
}

/** Number of commits: grows with every merge, so each release can update the one before. */
val commitCount: Int = runCatching {
    providers.exec { commandLine("git", "rev-list", "--count", "HEAD") }.standardOutput.asText.get().trim().toInt()
}.getOrDefault(1)

android {
    namespace = "app.diacritica"
    compileSdk = 35

    signingConfigs {
        signingProps?.let { p ->
            create("release") {
                storeFile = rootProject.file(p.getValue("storeFile"))
                storePassword = p["storePassword"]
                keyAlias = p["keyAlias"]
                keyPassword = p["keyPassword"]
            }
        }
    }

    defaultConfig {
        // One-time data move from the app's earlier ID: set to true and Run (this updates the old app and
        // gives it Settings › Backup & restore), make a backup, set back to false, Run, then Restore.
        val legacyId = false
        applicationId = if (legacyId) "com.polyglot." + "dict" else "app.diacritica"
        minSdk = 26
        targetSdk = 35
        versionCode = commitCount
        versionName = "0.3.$commitCount"
    }

    buildTypes {
        val key = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        release {
            // Phones and tablets from the last several years are all 64-bit ARM. Shipping only that
            // instead of four processor types cuts the APK from ~116 MB to ~40 MB. Debug builds keep
            // every type, so emulators still work.
            ndk { abiFilters += "arm64-v8a" }
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = key
        }
        debug {
            // Same key as releases when it's set up, so a Run from Android Studio updates a release install.
            signingConfig = key
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
        compose = true
    }
    androidResources {
        // The prebuilt dictionary is copied out of assets byte-for-byte; keep it uncompressed.
        noCompress += "db"
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    // On-device translation (Translate section); models download per language on first use.
    implementation(libs.mlkit.translate)
    implementation(libs.mlkit.language.id)
    implementation(libs.mlkit.digital.ink)
    implementation(libs.kotlinx.coroutines.play.services)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
}
