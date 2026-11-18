import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.room)
}

/** Supabase URL + publishable key come from local.properties (gitignored). Missing = offline-only build. */
val localProps: Properties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use(::load)
}

fun String.asBuildConfigString(): String = "\"" + replace("\\", "\\\\").replace("\"", "\\\"") + "\""

/**
 * Release signing: local.properties `release.*` keys, or KRONO_RELEASE_* env vars (CI). The keystore lives
 * outside the repo. Without all four values, release builds stay unsigned rather than failing.
 */
fun releaseSecret(key: String): String? =
    System.getenv("KRONO_RELEASE_" + key.uppercase())?.takeIf { it.isNotBlank() }
        ?: localProps.getProperty("release.$key")?.takeIf { it.isNotBlank() }

val releaseStoreFile = releaseSecret("storeFile")?.let(::file)?.takeIf { it.exists() }
val releaseStorePassword = releaseSecret("storePassword")
val releaseKeyAlias = releaseSecret("keyAlias")
val releaseKeyPassword = releaseSecret("keyPassword")
val hasReleaseSigning = releaseStoreFile != null && releaseStorePassword != null &&
    releaseKeyAlias != null && releaseKeyPassword != null
if (!hasReleaseSigning) logger.warn("KronoKeep: no release signing configured; release APK will be unsigned.")

android {
    namespace = "com.kronokeep.app"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.kronokeep.app"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "SUPABASE_URL", localProps.getProperty("supabase.url", "").asBuildConfigString())
        buildConfigField(
            "String",
            "SUPABASE_PUBLISHABLE_KEY",
            localProps.getProperty("supabase.publishableKey", "").asBuildConfigString(),
        )
        // FCM, initialised by hand in KronoKeepApplication. Any key missing = no push, app runs as before.
        val firebaseKeys = listOf(
            "FIREBASE_PROJECT_ID" to "firebase.projectId",
            "FIREBASE_APPLICATION_ID" to "firebase.applicationId",
            "FIREBASE_API_KEY" to "firebase.apiKey",
            "FIREBASE_GCM_SENDER_ID" to "firebase.gcmSenderId",
        )
        for ((field, key) in firebaseKeys) {
            buildConfigField("String", field, localProps.getProperty(key, "").asBuildConfigString())
        }
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = releaseStoreFile
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            if (hasReleaseSigning) signingConfig = signingConfigs.getByName("release")
            // Whole-app R8. A packageScope limited to androidx/kotlin/kotlinx crashed at launch: R8 made
            // kotlin.text classes package-private while out-of-scope code (Ktor/supabase) still extended them.
            optimization {
                enable = true
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

room {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)

    // Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.navigation.suite)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    // Lifecycle + Navigation 3
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.viewmodel.navigation3)
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)

    // Persistence / background work
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work.runtime.ktx)

    // Supabase
    implementation(platform(libs.supabase.bom))
    implementation(libs.supabase.auth)
    implementation(libs.supabase.postgrest)
    implementation(libs.supabase.storage)
    implementation(libs.supabase.functions)
    implementation(libs.ktor.client.okhttp)

    // Push reminders
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)

    // Smart capture
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.androidx.camera.mlkit.vision)
    implementation(libs.mlkit.text.recognition)
    implementation(libs.mlkit.barcode.scanning)

    // Serialization / images
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.coil.compose)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
}
