package com.campusmarket.app

import java.io.File
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The app version has one source of truth (gradle.properties); this keeps the generated constant and iOS in step. */
class BuildInfoTest {
    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "gradle.properties").exists() }

    private val props = Properties().apply { File(root, "gradle.properties").inputStream().use(::load) }

    @Test
    fun generatedConstantMatchesGradleProperties() {
        assertEquals(props.getProperty("APP_VERSION_NAME"), BuildInfo.VERSION_NAME)
        assertEquals(props.getProperty("APP_VERSION_CODE").toInt(), BuildInfo.VERSION_CODE)
        assertTrue(Regex("""\d+\.\d+\.\d+""").matches(BuildInfo.VERSION_NAME), BuildInfo.VERSION_NAME)
    }

    @Test
    fun iosConfigIsInSyncWithGradleProperties() {
        val xc = File(root, "iosApp/Configuration/Config.xcconfig").readLines().associate { line ->
            line.substringBefore('=').trim() to line.substringAfter('=', "").trim()
        }
        assertEquals(BuildInfo.VERSION_NAME, xc["MARKETING_VERSION"], "run ./gradlew syncIosVersion")
        assertEquals(BuildInfo.VERSION_CODE.toString(), xc["CURRENT_PROJECT_VERSION"], "run ./gradlew syncIosVersion")
    }
}
