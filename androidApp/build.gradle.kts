import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeCompiler)
}

dependencies {
    implementation(projects.shared)
    implementation(libs.androidx.activity.compose)
}

android {
    namespace = "com.campusmarket.app"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.campusmarket.app"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        // Single source of truth: APP_VERSION_NAME / APP_VERSION_CODE in gradle.properties.
        versionCode = providers.gradleProperty("APP_VERSION_CODE").get().toInt()
        versionName = providers.gradleProperty("APP_VERSION_NAME").get()
    }
    androidResources {
        // The UI is English only: drop the other languages libraries bring for their few built-in strings.
        localeFilters += "en"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    buildTypes {
        getByName("release") {
            // R8 (full mode) shrinks, optimizes and obfuscates; unused resources are dropped afterwards.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        // Release code (R8, not debuggable) signed with the local debug key and allowed to reach the HTTP dev server
        // (src/benchmark/res/xml): for measuring performance and checking R8 on an emulator. Never distribute it.
        create("benchmark") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_11
    }
}

androidComponents {
    onVariants { variant ->
        if (variant.buildType == "debug") return@onVariants
        // Only kotlin-reflect reads the builtins, and only the IDE reads the library version stamps.
        variant.packaging.resources.excludes.addAll("/kotlin/**", "/META-INF/*.version", "/DebugProbesKt.bin")
        // OkHttp reads its public suffix list only to validate cookies, and the API client keeps none.
        variant.androidResources.ignoreAssetsPatterns.add("!PublicSuffixDatabase.list")
    }
}
