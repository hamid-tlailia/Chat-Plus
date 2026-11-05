import groovy.json.JsonSlurper

/**
 * Firebase config is read from app/google-services.json (Firebase console → Project settings →
 * Your apps → google-services.json). No Google Services Gradle plugin needed. Without the file the
 * app builds normally and push wake-ups are simply disabled.
 */
@Suppress("UNCHECKED_CAST")
val firebase: Map<String, String> = run {
    val f = file("google-services.json")
    if (!f.exists()) return@run emptyMap()
    val root = JsonSlurper().parse(f) as Map<String, Any?>
    val project = root["project_info"] as Map<String, Any?>
    val clients = root["client"] as List<Map<String, Any?>>
    val client = clients.firstOrNull {
        ((it["client_info"] as Map<String, Any?>)["android_client_info"] as Map<String, Any?>)["package_name"] == "app.zura"
    } ?: clients.first()
    mapOf(
        "PROJECT_ID" to project["project_id"].toString(),
        "SENDER_ID" to project["project_number"].toString(),
        "APP_ID" to (client["client_info"] as Map<String, Any?>)["mobilesdk_app_id"].toString(),
        "API_KEY" to ((client["api_key"] as List<Map<String, Any?>>).first()["current_key"]).toString(),
    )
}

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "app.zura"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.zura"
        minSdk = 26
        targetSdk = 35
        versionCode = 31
        versionName = "0.9.14"
        // Supabase project "zura". The publishable key is designed to ship in clients:
        // every table is protected by Row Level Security, the key alone grants nothing.
        buildConfigField("String", "SUPABASE_URL", "\"https://vsilugnplrtguvwlmzjc.supabase.co\"")
        listOf("PROJECT_ID", "SENDER_ID", "APP_ID", "API_KEY").forEach { k ->
            buildConfigField("String", "FIREBASE_$k", "\"${firebase[k].orEmpty()}\"")
        }
        buildConfigField("String", "SUPABASE_KEY", "\"sb_publishable_0gbWNjqx9KQG719r0By2YQ_KTzn6mpB\"")
        // Ship only the two supported locales: strips every AndroidX translation from the APK.
        resourceConfigurations += listOf("en", "ar")
    }

    // Only ship the two locales we support: trims every AndroidX/Material translation from the APK.
    androidResources {
        generateLocaleConfig = true
    }

    // One APK per CPU architecture: a phone downloads only its own WebRTC native library
    // (the Play Store does the same automatically from the .aab).
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = false
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("debug") // replace with release keystore in CI
        }
        debug {
            // Same package as release so one Firebase app config serves both.
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf("-opt-in=kotlin.RequiresOptIn")
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            it.systemProperty("roborazzi.test.record", "true")
            it.systemProperty("roborazzi.record.filePathStrategy", "relativePathFromCurrentDirectory")
            it.maxHeapSize = "3g"
            // Live backend test (opt-in): ZURA_LIVE=1 ZURA_QA_PASSWORD=… ./gradlew testDebugUnitTest --tests '*LiveBackendTest*'
            System.getenv("ZURA_LIVE")?.let { v -> it.environment("ZURA_LIVE", v) }
            System.getenv("ZURA_QA_PASSWORD")?.let { v -> it.environment("ZURA_QA_PASSWORD", v) }
            it.testLogging { showStandardStreams = System.getenv("ZURA_LIVE") == "1" }
        }
    }
    packaging {
        resources.excludes += listOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "/META-INF/*.version",
            "DebugProbesKt.bin",
            "kotlin/**",
        )
        // Compress native libs in the APK (WebRTC + on-device translation are ~28 MB raw):
        // a much smaller download; Android unpacks them once at install.
        jniLibs.useLegacyPackaging = true
    }
}

// Strong skipping is on by default with Kotlin 2.0.20+: unstable params no longer force recomposition.
composeCompiler {
    stabilityConfigurationFile = rootProject.layout.projectDirectory.file("compose-stability.conf")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.profileinstaller)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(platform(libs.supabase.bom))
    implementation(libs.supabase.auth)
    implementation(libs.supabase.postgrest)
    implementation(libs.supabase.realtime)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.webrtc)
    implementation(libs.haze)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)
    implementation(libs.kotlinx.coroutines.play.services)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.transformer)
    implementation(libs.media3.effect)
    // On-device translation (models downloaded per language on first use; text never leaves the phone)
    implementation(libs.mlkit.translate)
    implementation(libs.mlkit.langid)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
}
