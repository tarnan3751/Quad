package com.campusmarket.server.legal

import com.campusmarket.core.ApiRoutes
import com.campusmarket.server.AppConfig
import com.campusmarket.server.AppDeps
import com.campusmarket.server.TestEnv
import com.campusmarket.server.apiTest
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.withCharset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LegalRoutesTest {
    @Test
    fun pagesArePublicHtmlWithStrictCsp() = apiTest {
        for ((path, title) in listOf(
            ApiRoutes.LEGAL_TERMS to "Terms of Service",
            ApiRoutes.LEGAL_PRIVACY to "Privacy Policy",
            ApiRoutes.LEGAL_GUIDELINES to "Community Guidelines",
        )) {
            val res = client.get(path) // no Authorization header
            assertEquals(HttpStatusCode.OK, res.status, path)
            assertEquals(ContentType.Text.Html.withCharset(Charsets.UTF_8), res.contentType(), path)
            val csp = assertNotNull(res.headers["Content-Security-Policy"])
            assertTrue("default-src 'none'" in csp && "script-src" !in csp && "unsafe-inline" !in csp, csp)
            val html = res.bodyAsText()
            assertTrue("<h1>$title</h1>" in html)
            assertTrue("must be reviewed and adapted by qualified counsel" in html)
            assertTrue("prefers-color-scheme:dark" in html && "width=device-width" in html)
            assertFalse("<script" in html.lowercase())
        }
    }

    @Test
    fun cspStyleHashMatchesInlineStylesheet() = apiTest {
        val csp = client.get(ApiRoutes.LEGAL_TERMS).headers["Content-Security-Policy"]!!
        assertTrue("style-src 'sha256-" in csp)
        assertEquals(LEGAL_CSP, csp)
        val html = client.get(ApiRoutes.LEGAL_TERMS).bodyAsText()
        assertTrue("<style>$LEGAL_CSS_TEXT</style>" in html)
    }

    @Test
    fun guidelinesListProhibitedItems() = apiTest {
        val html = client.get(ApiRoutes.LEGAL_GUIDELINES).bodyAsText().lowercase()
        for (word in listOf("weapons", "drugs", "alcohol", "academic-dishonesty", "counterfeit")) assertTrue(word in html, word)
    }

    @Test
    fun placeholdersWhenUnconfigured() = apiTest {
        val bare = client.get(ApiRoutes.LEGAL_TERMS).bodyAsText()
        assertTrue("[COMPANY NAME]" in bare && "[SUPPORT EMAIL]" in bare)
    }

    @Test
    fun configuredEntityAndEmailAreShownAndEscaped() {
        val html = LegalPages("Acme <b>& Sons</b>", "help@acme.edu").render(LegalPage.PRIVACY)
        assertTrue("Acme &lt;b&gt;&amp; Sons&lt;/b&gt;" in html)
        assertFalse("<b>& Sons" in html)
        assertTrue("""<a href="mailto:help@acme.edu">help@acme.edu</a>""" in html)
        assertFalse("[COMPANY NAME]" in html || "[SUPPORT EMAIL]" in html)
    }

    @Test
    fun routesServeConfiguredValues() = apiTest(AppDeps(TestEnv.config.copy(legalEntityName = "Ivy Co", supportEmail = "hi@ivy.edu"), TestEnv.db)) {
        val html = client.get(ApiRoutes.LEGAL_GUIDELINES).bodyAsText()
        assertTrue("hi@ivy.edu" in html)
        assertTrue("Ivy Co" in client.get(ApiRoutes.LEGAL_TERMS).bodyAsText())
    }

    @Test
    fun configFromEnvReadsLegalSettings() {
        val c = AppConfig.fromEnv(mapOf("LEGAL_ENTITY_NAME" to "Ivy Co", "SUPPORT_EMAIL" to "hi@ivy.edu"))
        assertEquals("Ivy Co", c.legalEntityName)
        assertEquals("hi@ivy.edu", c.supportEmail)
        assertNull(AppConfig.fromEnv(emptyMap()).supportEmail)
    }
}
