package com.campusmarket.server.payments

import com.campusmarket.core.ApiRoutes
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.withCharset
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import kotlinx.serialization.json.JsonPrimitive
import java.util.UUID

/** Every URL the payments feature hands to the processor, the browser or the apps. */
internal class PaymentUrls(publicBaseUrl: String, private val appDeepLinkBase: String) {
    private val origin = publicBaseUrl.trimEnd('/')
    private val api = origin + ApiRoutes.BASE

    /** [url] without our origin, for links inside our own pages (works whichever host the browser used to reach us). */
    fun local(url: String) = url.removePrefix(origin)

    // browser pages that bounce back into the app (Account Link return/refresh, Checkout success/cancel)
    fun onboardingReturn() = "$api$RETURN_PATH?to=payouts"
    fun onboardingRefresh() = "$api$RETURN_PATH?to=payouts&refresh=1"
    fun checkoutSuccess(orderId: UUID) = "$api$RETURN_PATH?to=order&id=$orderId&result=success"
    fun checkoutCancel(orderId: UUID) = "$api$RETURN_PATH?to=order&id=$orderId&result=cancel"

    // app deep links
    fun appPayouts() = "${appDeepLinkBase}payouts"
    fun appOrder(orderId: UUID) = "${appDeepLinkBase}orders/$orderId"

    // fake processor's hosted pages (dev only)
    fun fakeOnboarding(accountId: String) = "$api$FAKE_PATH/onboarding/$accountId"
    fun fakeCheckout(orderId: UUID) = "$api$FAKE_PATH/checkout/$orderId"

    companion object {
        const val RETURN_PATH = "/payments/return"
        const val FAKE_PATH = "/dev/fake-stripe"
    }
}

/** Minimal server-rendered HTML for the bounce pages and the fake processor's hosted pages. */
internal object Html {
    fun escape(s: String): String = buildString(s.length) {
        for (ch in s) when (ch) {
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '&' -> append("&amp;")
            '"' -> append("&quot;")
            '\'' -> append("&#39;")
            else -> append(ch)
        }
    }

    /** A JS string literal that is also safe inside a `<script>` element. */
    fun jsString(s: String): String = JsonPrimitive(s).toString().replace("</", "<\\/")

    fun page(title: String, body: String, head: String = ""): String = """<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="color-scheme" content="light dark">
<title>${escape(title)}</title>$head
<style>
  :root{color-scheme:light dark;--bg:#fff8f3;--fg:#2a1320;--muted:#7a5a66;--card:#fff;--accent:#d6305c;--price:#d6305c;--badge-bg:#ffe7b8;--badge-fg:#6b3d00}
  @media (prefers-color-scheme:dark){:root{--bg:#1d0f18;--fg:#ffeef0;--muted:#c49aa8;--card:#2e1826;--accent:#ff9166;--price:#ffb347;--badge-bg:#4a3210;--badge-fg:#ffd08a}}
  body{font:500 16px/1.5 "Plus Jakarta Sans",-apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,"Helvetica Neue",Arial,sans-serif;margin:0;background:var(--bg);color:var(--fg);-webkit-text-size-adjust:100%}
  .brand{background:linear-gradient(135deg,#ffc35a,#ff6b81 55%,#b02a78);color:#fff;padding:28px 16px 88px}
  .brand-in{max-width:420px;margin:0 auto;display:flex;align-items:center;gap:8px}
  .brand svg{flex:none;display:block} .word{font-weight:800;font-size:1.75rem;line-height:1;letter-spacing:-.04em}
  main{max-width:420px;margin:-64px auto 32px;background:var(--card);border-radius:18px;padding:24px;box-sizing:border-box;box-shadow:0 8px 28px rgba(42,19,32,.12)}
  @media (max-width:452px){main{margin-left:16px;margin-right:16px}}
  h1{font-size:1.4rem;font-weight:800;line-height:1.25;letter-spacing:-.02em;margin:0 0 12px} p{line-height:1.5} .muted{color:var(--muted);font-size:.95rem}
  .amount{font-size:2.25rem;font-weight:800;letter-spacing:-.04em;color:var(--price);margin:8px 0 12px}
  .btn{display:block;width:100%;box-sizing:border-box;text-align:center;padding:14px 20px;border-radius:999px;border:0;font:inherit;font-size:1rem;
       font-weight:700;text-decoration:none;cursor:pointer;margin-top:12px;color:#fff;background:linear-gradient(120deg,#f0603a,#d6305c 30%,#b02a78)}
  .btn:active{filter:brightness(.92)} .btn:focus-visible{outline:3px solid var(--accent);outline-offset:3px}
  .btn.secondary{background:transparent;color:var(--accent);box-shadow:inset 0 0 0 1.5px var(--accent)}
  .badge{display:inline-block;background:var(--badge-bg);color:var(--badge-fg);border-radius:999px;padding:3px 10px;font-size:.8rem;font-weight:700;margin-bottom:8px}
  form{margin:0}
</style></head><body><header class="brand"><div class="brand-in"><svg viewBox="18 18 60 60" fill="#fff" width="30" height="30" aria-hidden="true" focusable="false"><path d="M18 44V30a12 12 0 0 1 12-12H44V44Z"/><circle cx="65" cy="31" r="13"/><path d="M18 52H44V78H30a12 12 0 0 1-12-12Z"/><path d="M52 52H78V66a12 12 0 0 1-12 12H52Z"/></svg><span class="word">quad</span></div></header><main>$body</main></body></html>"""

    fun postButton(action: String, label: String, secondary: Boolean = false): String =
        """<form method="post" action="${escape(action)}"><button class="btn${if (secondary) " secondary" else ""}" type="submit">${escape(label)}</button></form>"""

    fun linkButton(href: String, label: String, secondary: Boolean = false): String =
        """<a class="btn${if (secondary) " secondary" else ""}" href="${escape(href)}">${escape(label)}</a>"""

    /** Sends the browser into the app: immediate redirect attempts plus a button (custom schemes may need a tap). */
    fun bounce(deepLink: String, message: String): String = page(
        title = "Returning to Quad",
        head = """<meta http-equiv="refresh" content="0;url=${escape(deepLink)}">""",
        body = """<h1>${escape(message)}</h1>
<p class="muted">Taking you back to Quad…</p>
${linkButton(deepLink, "Open Quad")}
<script>window.location.replace(${jsString(deepLink)});</script>""",
    )
}

/**
 * Our pages need only inline style and the bounce script; nothing may frame them (the fake processor's Pay /
 * Complete buttons would be clickjackable), and their URLs (order ids) aren't sent on as referrers.
 */
internal const val HTML_CSP =
    "default-src 'none'; style-src 'unsafe-inline'; script-src 'unsafe-inline'; form-action 'self'; frame-ancestors 'none'; base-uri 'none'"

internal suspend fun ApplicationCall.respondHtml(html: String, status: HttpStatusCode = HttpStatusCode.OK) {
    response.header(HttpHeaders.CacheControl, "no-store")
    response.header("Content-Security-Policy", HTML_CSP)
    response.header("X-Frame-Options", "DENY")
    response.header("X-Content-Type-Options", "nosniff")
    response.header("Referrer-Policy", "no-referrer")
    respondText(html, ContentType.Text.Html.withCharset(Charsets.UTF_8), status)
}

/** 303 See Other: after a form POST, continue with a GET of [location]. */
internal suspend fun ApplicationCall.seeOther(location: String) {
    response.header(HttpHeaders.Location, location)
    respond(HttpStatusCode.SeeOther)
}
