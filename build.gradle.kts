plugins {
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.androidMultiplatformLibrary) apply false
    alias(libs.plugins.composeCompiler) apply false
    alias(libs.plugins.composeMultiplatform) apply false
    alias(libs.plugins.kotlinJvm) apply false
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.kotlinxSerialization) apply false
}

// iOS reads its version from Config.xcconfig (Info.plist uses $(MARKETING_VERSION) / $(CURRENT_PROJECT_VERSION)).
// gradle.properties (APP_VERSION_NAME / APP_VERSION_CODE) is the single source of truth: run this after bumping it.
tasks.register("syncIosVersion") {
    val appVersionName = providers.gradleProperty("APP_VERSION_NAME")
    val appVersionCode = providers.gradleProperty("APP_VERSION_CODE")
    val iosConfig = layout.projectDirectory.file("iosApp/Configuration/Config.xcconfig")
    group = "versioning"
    description = "Copies APP_VERSION_NAME / APP_VERSION_CODE from gradle.properties into iosApp/Configuration/Config.xcconfig"
    inputs.property("name", appVersionName)
    inputs.property("code", appVersionCode)
    outputs.file(iosConfig)
    doLast {
        val file = iosConfig.asFile
        val kept = file.readLines().filterNot { it.startsWith("MARKETING_VERSION") || it.startsWith("CURRENT_PROJECT_VERSION") }
        file.writeText(kept.joinToString("\n").trimEnd() + "\nMARKETING_VERSION=${appVersionName.get()}\nCURRENT_PROJECT_VERSION=${appVersionCode.get()}\n")
    }
}
