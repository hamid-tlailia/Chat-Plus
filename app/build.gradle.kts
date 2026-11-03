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
        versionCode = 1
        versionName = "0.1.0"
        // Supabase project "zura". The publishable key is designed to ship in clients:
        // every table is protected by Row Level Security, the key alone grants nothing.
        buildConfigField("String", "SUPABASE_URL", "\"https://vsilugnplrtguvwlmzjc.supabase.co\"")
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
            applicationIdSuffix = ".debug"
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
        }
    }
    packaging {
        resources.excludes += listOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "/META-INF/*.version",
            "DebugProbesKt.bin",
            "kotlin/**",
        )
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
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.transformer)
    implementation(libs.media3.effect)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
}
