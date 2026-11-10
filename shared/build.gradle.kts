import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinxSerialization)
}

// Exposes the app version to common code as com.campusmarket.app.BuildInfo (source: gradle.properties).
val generateBuildInfo = tasks.register("generateBuildInfo") {
    val versionName = providers.gradleProperty("APP_VERSION_NAME")
    val versionCode = providers.gradleProperty("APP_VERSION_CODE")
    val outDir = layout.buildDirectory.dir("generated/buildInfo/commonMain/kotlin")
    inputs.property("versionName", versionName)
    inputs.property("versionCode", versionCode)
    outputs.dir(outDir)
    doLast {
        val dir = outDir.get().asFile.resolve("com/campusmarket/app")
        dir.mkdirs()
        dir.resolve("BuildInfo.kt").writeText(
            """
            |package com.campusmarket.app
            |
            |/** Generated from gradle.properties (APP_VERSION_NAME / APP_VERSION_CODE). Do not edit. */
            |object BuildInfo {
            |    const val VERSION_NAME: String = "${versionName.get()}"
            |    const val VERSION_CODE: Int = ${versionCode.get().toInt()}
            |}
            |
            """.trimMargin(),
        )
    }
}

kotlin {
    listOf(
        iosArm64(),
        iosSimulatorArm64()
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "Shared"
            isStatic = true
        }
    }

    jvm()

    androidLibrary {
        namespace = "com.campusmarket.app.shared"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()

        compilerOptions {
            jvmTarget = JvmTarget.JVM_11
        }
        androidResources {
            enable = true
        }
    }

    compilerOptions {
        optIn.addAll(
            "kotlin.time.ExperimentalTime",
            "androidx.compose.material3.ExperimentalMaterial3Api",
        )
    }

    sourceSets {
        commonMain.configure { kotlin.srcDir(generateBuildInfo.map { it.outputs.files }) }
        androidMain.dependencies {
            implementation(libs.androidx.activity.compose)
            implementation(libs.ktor.client.okhttp)
        }
        iosMain.dependencies {
            implementation(libs.ktor.client.darwin)
        }
        jvmMain.dependencies {
            implementation(libs.ktor.client.okhttp)
        }
        jvmTest.dependencies {
            // Desktop UI smoke tests (AppSmokeTest): real App() against a MockEngine server.
            implementation(libs.compose.uiTest)
            implementation(compose.desktop.currentOs)
            implementation(libs.kotlinx.coroutines.swing)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.ktor.client.mock)
        }
        commonMain.dependencies {
            api(projects.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.datetime)
            implementation(libs.ktor.client.websockets)
            implementation(libs.ktor.client.logging)
            implementation(libs.multiplatform.settings)
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.ui)
            implementation(libs.compose.ui.backhandler)
            implementation(libs.compose.components.resources)

            implementation(libs.navigation.compose)
            implementation(libs.androidx.lifecycle.runtimeCompose)
            implementation(libs.compose.material.icons.core)
            // Already in the graph via navigation-compose; declared so screens can intercept system back.

            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.kotlinx.json)

            implementation(libs.coil.compose)
            implementation(libs.coil.network.ktor)
            implementation(libs.koin.core)
            implementation(libs.koin.compose)
            implementation(libs.koin.compose.viewmodel)
            implementation(libs.filekit.dialogs.compose)
        }
    }
}

composeCompiler {
    stabilityConfigurationFiles.add(layout.projectDirectory.file("compose-stability.conf"))
    // Compiler reports for checking which composables skip: ./gradlew :shared:compileKotlinJvm -PcomposeReports
    // (written to shared/build/compose-reports).
    if (providers.gradleProperty("composeReports").isPresent) {
        reportsDestination = layout.buildDirectory.dir("compose-reports")
        metricsDestination = layout.buildDirectory.dir("compose-reports")
    }
}
