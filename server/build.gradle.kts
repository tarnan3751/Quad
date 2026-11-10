plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.kotlinxSerialization)
    application
}

kotlin {
    jvmToolchain(17)
}

application {
    mainClass = "com.campusmarket.server.ApplicationKt"
}

dependencies {
    implementation(projects.core)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.server.auth)
    implementation(libs.ktor.server.auth.jwt)
    implementation(libs.ktor.server.websockets)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.server.call.logging)
    implementation(libs.ktor.server.cors)
    implementation(libs.ktor.server.rate.limit)
    implementation(libs.logback)
    implementation(libs.postgres)
    implementation(libs.hikari)
    implementation(libs.stripe)
    implementation(libs.bcrypt)
    implementation(libs.angus.mail)

    testImplementation(libs.kotlin.test.junit5)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.ktor.client.content.negotiation)
    testImplementation(libs.ktor.client.websockets)
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.test {
    useJUnitPlatform()
    environment("BCRYPT_COST", "4")
    // tests that need the photo variant backfill run it directly
    environment("PHOTO_VARIANT_BACKFILL", "false")
    testLogging { events("failed"); showStandardStreams = false; exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}

// Dev seed: `./gradlew :server:seed` (or `:server:seed --args="--reset"`). Reads the same env vars as the server.
tasks.register<JavaExec>("seed") {
    group = "application"
    description = "Populates the dev database with a demo dataset (refuses to run when DEV_MODE=false)."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass = "com.campusmarket.server.seed.Seed"
}
