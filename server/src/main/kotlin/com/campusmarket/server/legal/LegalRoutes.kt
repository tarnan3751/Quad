package com.campusmarket.server.legal

import com.campusmarket.core.ApiRoutes
import com.campusmarket.server.AppDeps
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.withCharset
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import java.security.MessageDigest
import java.util.Base64

/**
 * Public, server-rendered legal pages: `/legal/terms`, `/legal/privacy`, `/legal/guidelines`.
 * Starter templates only; they must be reviewed by counsel before launch (each page says so).
 */
fun Route.legalRoutes(deps: AppDeps) {
    val pages = LegalPages(deps.config.legalEntityName, deps.config.supportEmail)
    for (page in LegalPage.entries) {
        val html = pages.render(page)
        get(page.path) { call.respondLegal(html) }
    }
}

private suspend fun ApplicationCall.respondLegal(html: String) {
    response.header("Content-Security-Policy", LEGAL_CSP)
    response.header(HttpHeaders.CacheControl, "public, max-age=300")
    response.header("X-Content-Type-Options", "nosniff")
    response.header("Referrer-Policy", "no-referrer")
    respondText(html, ContentType.Text.Html.withCharset(Charsets.UTF_8))
}

internal enum class LegalPage(val path: String, val title: String) {
    TERMS(ApiRoutes.LEGAL_TERMS, "Terms of Service"),
    PRIVACY(ApiRoutes.LEGAL_PRIVACY, "Privacy Policy"),
    GUIDELINES(ApiRoutes.LEGAL_GUIDELINES, "Community Guidelines"),
}

private fun sha256Base64(s: String): String =
    Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(s.toByteArray()))

/** No scripts, no network: only the single inline stylesheet (allowed by hash), nothing embeddable, no form targets. */
internal val LEGAL_CSP: String =
    "default-src 'none'; style-src 'sha256-${sha256Base64(LEGAL_CSS_TEXT)}'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'"

internal class LegalPages(entityName: String?, supportEmail: String?) {
    private val entity = entityName?.trim()?.takeIf { it.isNotEmpty() }
    private val email = supportEmail?.trim()?.takeIf { it.isNotEmpty() }

    private fun esc(s: String) = buildString(s.length) {
        for (ch in s) when (ch) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            '\'' -> append("&#39;")
            else -> append(ch)
        }
    }

    /** Company name, or a clearly visible placeholder. */
    private val company get() = entity?.let(::esc) ?: "<mark>[COMPANY NAME]</mark>"
    private val contact get() = email?.let { """<a href="mailto:${esc(it)}">${esc(it)}</a>""" } ?: "<mark>[SUPPORT EMAIL]</mark>"
    private val jurisdiction get() = "<mark>[GOVERNING LAW / JURISDICTION]</mark>"

    fun render(page: LegalPage): String {
        val body = when (page) {
            LegalPage.TERMS -> terms()
            LegalPage.PRIVACY -> privacy()
            LegalPage.GUIDELINES -> guidelines()
        }
        val nav = LegalPage.entries.joinToString(" · ") {
            if (it == page) "<strong>${it.title}</strong>" else """<a href="${it.path}">${it.title}</a>"""
        }
        return """<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="color-scheme" content="light dark">
<meta name="robots" content="noindex">
<title>${page.title} · Quad</title>
<style>$LEGAL_CSS_TEXT</style>
</head>
<body>
<header class="brand"><div class="brand-in">$BRAND_MARK_SVG<span class="word">quad</span></div></header>
<main>
<div class="notice" role="note"><strong>Template, not legal advice.</strong> This document is a starter template and must be reviewed and adapted by qualified counsel before launch. Highlighted items are placeholders.</div>
<h1>${page.title}</h1>
<p class="meta">Quad · Last updated: <mark>[DATE]</mark></p>
<article class="doc">
$body
</article>
<footer><p>$nav</p><p>Questions? Contact $contact.</p></footer>
</main>
</body>
</html>
"""
    }

    private fun terms() = """
<p>These Terms of Service ("Terms") govern your use of the Quad mobile apps and services (the "Service"), operated by $company ("we", "us"). By creating an account or using the Service you agree to these Terms and to our <a href="${ApiRoutes.LEGAL_GUIDELINES}">Community Guidelines</a> and <a href="${ApiRoutes.LEGAL_PRIVACY}">Privacy Policy</a>.</p>

<h2>1. What Quad is</h2>
<p>Quad is a marketplace that lets students of the same campus list, discover and buy items from each other. We provide the platform; we are not the buyer or seller of any item, we do not inspect items, and we are not a party to the sale between users. Sellers are responsible for their listings and buyers are responsible for deciding whether to buy.</p>

<h2>2. Eligibility and accounts</h2>
<ul>
<li>You must be at least 18 years old, or the age of majority where you live, and be able to form a binding contract.</li>
<li>You sign up with a school email address (for example, one ending in <code>.edu</code>) and must confirm it with a verification code. Your campus is determined by your email domain, and you can only see and trade with users on your campus.</li>
<li>Keep your password secret. You are responsible for activity on your account. Tell us promptly if you suspect unauthorised use.</li>
<li>Provide accurate information. One person, one account.</li>
</ul>

<h2>3. Listings and in-person handoff</h2>
<ul>
<li>Listings must be truthful, describe the item's condition accurately, and comply with the <a href="${ApiRoutes.LEGAL_GUIDELINES}">Community Guidelines</a>, including the list of prohibited items.</li>
<li>Items are handed off in person. Choose a public, well-lit place on or near campus, consider bringing a friend, and inspect the item before you confirm receipt.</li>
<li>We may remove listings and take action against accounts that break these rules, with or without notice.</li>
</ul>

<h2>4. Payments, holds and payouts</h2>
<ul>
<li>Payments are processed by our payment partner, Stripe. Card details are entered on Stripe's hosted pages and never reach our servers. By paying or receiving payouts you also accept Stripe's terms, including the Stripe Connected Account Agreement for sellers.</li>
<li>When a buyer pays, the funds are held by us. They are released to the seller, minus the platform fee shown at checkout, when the buyer confirms in the app that the handoff took place, or automatically 72 hours after payment unless the buyer has reported a problem with the order first. Reporting a problem keeps the funds on hold until the buyer withdraws the report or confirms receipt, the seller cancels and refunds, or our team reviews the order and either refunds the buyer or releases the payment to the seller.</li>
<li>If an order is cancelled after payment, the buyer is refunded in full to the original payment method. Refund timing depends on the buyer's bank.</li>
<li>Sellers must complete Stripe's identity and payout onboarding before they can sell. Sellers are responsible for any taxes on their sales.</li>
<li>We may delay, withhold or reverse a payout where we reasonably suspect fraud, a chargeback, or a violation of these Terms. <mark>[Counsel: confirm dispute, chargeback and fee language.]</mark></li>
</ul>

<h2>5. Ratings and reviews</h2>
<p>After a completed order, buyer and seller may each rate the other. Ratings must be honest and based on your own experience. We may remove ratings that are abusive, retaliatory or otherwise break the Community Guidelines.</p>

<h2>6. Messages, reporting, blocking and moderation</h2>
<ul>
<li>You can message other users about listings. Don't harass or spam anyone, and don't try to move transactions off the platform to avoid fees or protections.</li>
<li>You can report listings, users and messages, and you can block users. Blocked users cannot message you or buy from you, and their listings are hidden from you.</li>
<li>Our moderators review reports. They may remove content, warn, suspend or permanently ban accounts. Moderation actions are logged. A suspended user cannot sign in for the duration of the suspension.</li>
</ul>

<h2>7. Your content</h2>
<p>You keep ownership of the photos, text and other content you post. You grant us a worldwide, non-exclusive, royalty-free licence to host, display and distribute that content as needed to operate and promote the Service. You promise that you have the rights to what you post and that it doesn't infringe anyone else's rights.</p>

<h2>8. Account deletion and termination</h2>
<p>You can delete your account in the app under Settings. Deletion removes your profile details, takes down your active listings and signs you out everywhere; it is blocked while you have orders awaiting payment or handoff. Some records (for example completed orders, ratings, reports and moderation logs) may be retained as described in the <a href="${ApiRoutes.LEGAL_PRIVACY}">Privacy Policy</a>. We may suspend or terminate accounts that violate these Terms.</p>

<h2>9. Disclaimers</h2>
<p>The Service is provided "as is" and "as available". We do not guarantee the quality, safety, legality or authenticity of items, or the identity, honesty or conduct of users. Meetings and transactions with other users are at your own risk.</p>

<h2>10. Limitation of liability</h2>
<p><mark>[Counsel: insert limitation of liability, indemnity and any mandatory consumer-law carve-outs for your jurisdiction.]</mark> To the maximum extent permitted by law, we are not liable for indirect, incidental or consequential damages arising from your use of the Service or from transactions between users.</p>

<h2>11. Changes and governing law</h2>
<p>We may update these Terms; if the changes are material we will notify you in the app or by email. Continued use after the effective date means you accept them. These Terms are governed by the laws of $jurisdiction, and disputes will be resolved in the courts or forum designated there.</p>

<h2>12. Contact</h2>
<p>$company · $contact</p>
"""

    private fun privacy() = """
<p>This Privacy Policy explains what information $company ("we", "us") collects through Quad, how we use it, and the choices you have.</p>

<h2>1. Information we collect</h2>
<ul>
<li><strong>Account information:</strong> school email address, display name, password (stored only as a salted hash), optional bio and profile photo, and your campus (derived from your email domain).</li>
<li><strong>Content you create:</strong> listings, listing photos, prices, pickup notes, messages to other users, ratings and reviews, favourites.</li>
<li><strong>Transaction information:</strong> orders, amounts, fees, order status and payout status. Payment card and bank details are collected directly by Stripe and are never stored on our servers.</li>
<li><strong>Safety information:</strong> reports you make or that are made about you, blocks, moderation actions and suspension reasons.</li>
<li><strong>Technical information:</strong> device and app version, IP address and server logs, and sign-in session tokens, used to run and secure the Service.</li>
</ul>

<h2>2. How we use it</h2>
<ul>
<li>To create and secure your account, verify your school email and keep you signed in.</li>
<li>To show listings and profiles to people on your campus, and to deliver messages.</li>
<li>To process payments, holds, refunds and payouts through Stripe.</li>
<li>To investigate reports, enforce our rules, prevent fraud and keep users safe.</li>
<li>To send service emails such as verification codes and order notices.</li>
<li>To maintain, debug and improve the Service, and to comply with legal obligations.</li>
</ul>
<p>We do not sell your personal information. <mark>[Counsel: confirm statements for CCPA/GDPR or other applicable regimes.]</mark></p>

<h2>3. What other users see</h2>
<p>Other users on your campus can see your display name, photo, bio, rating, join date and your active listings. Your email address is never shown to other users. Messages are visible to you, the other participant, and to moderators when a message is reported.</p>

<h2>4. Who we share it with</h2>
<ul>
<li><strong>Stripe</strong> processes payments and payouts and performs identity verification for sellers under its own privacy policy.</li>
<li><strong>Service providers</strong> that host our infrastructure and deliver email, bound by confidentiality and data-protection terms. <mark>[List providers.]</mark></li>
<li><strong>Authorities and others</strong> when required by law, to protect safety, or in a business transfer.</li>
</ul>

<h2>5. Retention and deletion</h2>
<p>You can delete your account in the app under Settings. On deletion we remove or anonymise your email, name, bio and photo, revoke your sessions and take down your active listings. We keep a limited set of records where we have a legitimate need or legal obligation: for example completed order and payment records, ratings you left for others, and reports and moderation logs relating to safety. <mark>[Counsel: specify retention periods.]</mark></p>

<h2>6. Your choices and rights</h2>
<p>You can edit your profile, block users and delete your account in the app. Depending on where you live you may have rights to access, correct, export or delete your data, or to object to some processing. Contact us at $contact to make a request.</p>

<h2>7. Security</h2>
<p>We use encryption in transit, hashed passwords and short-lived access tokens, and limit staff access to personal data. No system is perfectly secure, so please use a strong, unique password.</p>

<h2>8. Children</h2>
<p>Quad is intended for university students aged 18 or older. We do not knowingly collect information from anyone under 18.</p>

<h2>9. Changes and contact</h2>
<p>We will post updates here and notify you of material changes. Contact: $company · $contact · $jurisdiction.</p>
"""

    private fun guidelines() = """
<p>Quad works because students can trust each other. These guidelines apply to every listing, message, rating and profile. Breaking them can lead to content removal, suspension or a permanent ban.</p>

<h2>Be honest and respectful</h2>
<ul>
<li>Describe items accurately and show real photos of the actual item.</li>
<li>Price fairly and honour your agreements; don't no-show or change the price at handoff.</li>
<li>No harassment, hate speech, threats, sexual content, or discrimination. Treat everyone the way you'd want to be treated at your own school.</li>
<li>No spam, duplicate listings, or fake ratings. Only rate real completed orders, and only about the experience.</li>
<li>No scams: never ask for payment outside the app, for gift cards, or for codes sent to someone's phone or email.</li>
</ul>

<h2>Prohibited items</h2>
<p>You may not list, offer or ask for:</p>
<ul>
<li><strong>Weapons:</strong> firearms, ammunition, explosives, knives designed as weapons, stun guns, pepper spray, and related parts.</li>
<li><strong>Drugs:</strong> illegal drugs, controlled or prescription medication, drug paraphernalia, vapes and nicotine or tobacco products, cannabis and related products.</li>
<li><strong>Alcohol</strong> in any form.</li>
<li><strong>Academic-dishonesty material:</strong> essays or assignments for submission, exam answers or leaked exams, paid test-taking or homework services, and access codes that would let a buyer cheat. Selling your own used textbooks and notes you wrote is fine.</li>
<li><strong>Counterfeit or stolen goods:</strong> fakes, replicas passed off as genuine, items you don't have the right to sell, and pirated software or media.</li>
<li><strong>Fake IDs</strong> and identity documents, or anything intended to deceive authorities or institutions.</li>
<li><strong>Tickets and passes</strong> that are not transferable or that you cannot verify, and student discount or meal-plan credentials that violate the issuer's rules.</li>
<li><strong>Recalled or unsafe items,</strong> including recalled products, car seats of unknown history, and damaged electronics with battery hazards.</li>
<li><strong>Hazardous materials,</strong> animals, human remains or body parts, adult content, and personal data about other people.</li>
<li><strong>Services</strong> that aren't tied to a physical item, and anything illegal where you or the buyer live.</li>
</ul>
<p>If something isn't listed but would be illegal, unsafe or against your school's rules, don't post it. <mark>[Counsel/ops: tailor this list to your campuses and local law.]</mark></p>

<h2>Meeting safely</h2>
<ul>
<li>Meet in public, well-lit, busy places on campus; many schools have safe-exchange zones.</li>
<li>Bring a friend or tell someone where you're going, especially in the evening.</li>
<li>Inspect the item before confirming receipt. Only tap "confirm" once you have the item and are happy with it; confirming releases the seller's payment.</li>
<li>Trust your instincts: you can cancel and walk away at any time.</li>
</ul>

<h2>Reporting, blocking and enforcement</h2>
<ul>
<li>Use <strong>Report</strong> on any listing, user or message that breaks these guidelines. Reports are reviewed by our moderators, and false or retaliatory reports may themselves be penalised.</li>
<li>Use <strong>Block</strong> on a user's profile to stop all contact and hide their listings from you.</li>
<li>Moderators may remove listings, warn users, or suspend or ban accounts. Serious or illegal conduct may be reported to campus security or law enforcement.</li>
<li>If you think a decision was a mistake, contact us at $contact.</li>
</ul>
"""
}

/** The Quad mark (a white ring with a round-capped tail) for the gradient header band. Inline: nothing is fetched. */
internal const val BRAND_MARK_SVG =
    """<svg class="mark" viewBox="18 18 60 60" fill="#fff" width="34" height="34" aria-hidden="true" focusable="false"><path d="M18 44V30a12 12 0 0 1 12-12H44V44Z"/><circle cx="65" cy="31" r="13"/><path d="M18 52H44V78H30a12 12 0 0 1-12-12Z"/><path d="M52 52H78V66a12 12 0 0 1-12 12H52Z"/></svg>"""

/*
 * Golden Hour: warm white / after-dark pages, the brand gradient only on the header band. Plus Jakarta Sans is used
 * if the reader has it installed (the strict CSP allows no web fonts), otherwise the platform's system sans.
 * Links use a deeper berry (#C42752) so they stay above 4.5:1 on the warm-white page as well as on white cards.
 */
internal const val LEGAL_CSS_TEXT = """
:root{color-scheme:light dark;--bg:#fff8f3;--fg:#2a1320;--muted:#7a5a66;--accent:#d6305c;--link:#c42752;--card:#ffffff;--code:#fbede8;--line:#f0ddd7;--warn-bg:#fff0d4;--warn-fg:#5a3600;--mark:#ffe0a3}
@media (prefers-color-scheme:dark){:root{--bg:#1d0f18;--fg:#ffeef0;--muted:#c49aa8;--accent:#ff9166;--link:#ff9166;--card:#2e1826;--code:#3b2232;--line:#46293b;--warn-bg:#3d2614;--warn-fg:#ffdda8;--mark:#6b4510}}
*{box-sizing:border-box}
body{margin:0;background:var(--bg);color:var(--fg);font:500 17px/1.65 "Plus Jakarta Sans",-apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,"Helvetica Neue",Arial,sans-serif;-webkit-text-size-adjust:100%}
.brand{background:linear-gradient(135deg,#ffc35a,#ff6b81 55%,#b02a78);color:#fff}
.brand-in{max-width:42rem;margin:0 auto;padding:2.25rem 1rem 2rem;display:flex;align-items:center;gap:.6rem}
.mark{flex:none;display:block}
.word{font-weight:800;font-size:2.1rem;line-height:1;letter-spacing:-.04em}
main{max-width:42rem;margin:0 auto;padding:1.25rem 1rem 3rem}
h1{font-weight:800;font-size:2rem;line-height:1.15;letter-spacing:-.03em;margin:1.5rem 0 .25rem}
h2{font-weight:800;font-size:1.2rem;line-height:1.3;letter-spacing:-.01em;margin:2rem 0 .5rem;color:var(--accent)}
.doc{background:var(--card);border-radius:18px;padding:.4rem 1.25rem 1.25rem;margin-top:1.25rem}
.doc>h2:first-child,.doc>p:first-child{margin-top:1rem}
p,ul{margin:.6rem 0}
li{margin:.3rem 0}
ul{padding-left:1.3rem}
strong{font-weight:700}
a{color:var(--link);font-weight:600;text-underline-offset:2px}
code{background:var(--code);padding:.1em .4em;border-radius:6px;font-size:.9em}
mark{background:var(--mark);color:var(--fg);padding:0 .25em;border-radius:4px}
.meta{color:var(--muted);margin-top:0}
.notice{background:var(--warn-bg);color:var(--warn-fg);border-radius:18px;padding:.85rem 1.1rem;font-size:.95rem;line-height:1.5}
footer{margin-top:2rem;padding-top:1rem;border-top:1px solid var(--line);color:var(--muted);font-size:.95rem}
"""
