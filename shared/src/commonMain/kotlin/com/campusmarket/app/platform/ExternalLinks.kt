package com.campusmarket.app.platform

import com.campusmarket.app.data.ServerConfig

/**
 * Opens web pages in the external browser: Stripe Checkout (`Order.checkoutUrl`), payout onboarding
 * (`OnboardingLinkResponse.url`), and the dev fake-Stripe pages. Inject it into view models.
 *
 * Loopback URLs from a dev server are rewritten to the configured base URL so they also work on the emulator.
 */
class ExternalLinks(
    private val launcher: PlatformUrlLauncher,
    private val server: ServerConfig,
) {
    fun open(url: String): Boolean = launcher.open(server.resolve(url) ?: url)
}
