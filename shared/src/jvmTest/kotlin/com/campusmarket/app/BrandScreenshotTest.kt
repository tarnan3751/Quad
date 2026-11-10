package com.campusmarket.app

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SkikoComposeUiTest
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import com.campusmarket.app.data.InMemoryTokenStore
import com.campusmarket.app.platform.DeepLinks
import com.campusmarket.core.OrderRole
import com.campusmarket.core.OrderStatus
import kotlinx.coroutines.runBlocking
import kotlin.test.Test

/**
 * Phone-sized screenshots of the main screens in light AND dark, against a running dev server with the seeded demo
 * data, for reviewing the Quad / Golden Hour look. Read-only (it only signs in and navigates). Opt-in:
 * ```
 * CAMPUS_MARKET_LIVE=http://127.0.0.1:8080 CAMPUS_MARKET_SCREENSHOTS=/some/dir \
 *   ./gradlew :shared:jvmTest --tests '*BrandScreenshotTest*'
 * ```
 */
@OptIn(ExperimentalTestApi::class)
class BrandScreenshotTest : ScreenshotGallery(settleMillis = 2_500) {

    /** The real App() signed in as [email] (or signed out). */
    private fun app(dark: Boolean, email: String?, block: SkikoComposeUiTest.() -> Unit) {
        startTestApp(server = null, tokens = email?.let(LiveServer::tokens) ?: InMemoryTokenStore())
        prefix = if (dark) "dark-" else "light-"
        screens(dark, block = block)
    }

    @Test
    fun brandScreenshots() {
        if (!enabled(live = true)) return
        val buyer = "liam@state.edu"
        val seller = "maya@state.edu"
        val conversation = runBlocking { LiveServer.api(buyer).conversations().items.first() }
        // An order still waiting for the handoff shows the most UI; fall back to any order.
        val orderOwner = "sofia@state.edu"
        val order = runBlocking {
            val orders = LiveServer.api(orderOwner).orders(OrderRole.BUYER).items
            orders.firstOrNull { it.status == OrderStatus.PAID } ?: orders.first()
        }

        for (dark in listOf(false, true)) {
            app(dark, email = null) {
                awaitText("Create account")
                shot("01-welcome")
                click("Sign in")
                awaitText("Welcome back")
                shot("02-login")
            }
            app(dark, buyer) {
                awaitText("Campbell Biology")
                shot("03-browse")
                clickDescription("Filters", substring = true)
                awaitText("Show results")
                click("Like new")
                shot("04-filters")
                click("Show results")
                click("Clear all")
                awaitText("Campbell Biology")
                // An empty state (its call to action is a primary button).
                onAllNodes(hasSetTextAction()).onFirst().performTextInput("zzqxv")
                onAllNodes(hasSetTextAction()).onFirst().performImeAction()
                awaitText("No results")
                shot("04b-empty-search")
                click("Clear search")
                awaitText("Campbell Biology")
                click("Campbell Biology", substring = true)
                awaitText("Buy now")
                shot("05-listing-detail")
                back()
                clickDescription("Favorites")
                awaitText("Favorites")
                shot("05b-favorites")
                back()
                click("Sell")
                awaitText("Post listing")
                shot("06-sell")
                click("Post listing")
                awaitText("Add at least one photo")
                shot("07-sell-errors")
                click("Inbox")
                awaitText(conversation.otherUser.displayName)
                shot("08-inbox")
                click(conversation.otherUser.displayName)
                waitForIdle()
                shot("09-conversation")
                back()
                click("Profile")
                awaitText("My listings")
                shot("10-profile")
                click("Settings")
                awaitText("Sign out")
                shot("11-settings")
            }
            app(dark, orderOwner) {
                awaitText("Campbell Biology")
                DeepLinks.handle("campusmarket://orders/${order.id}")
                awaitText(order.listing.title)
                shot("12-order-detail")
            }
            app(dark, "moderator@state.edu") {
                awaitText("Browse")
                click("Profile")
                click("Reports")
                waitForIdle()
                shot("13-moderation-queue")
            }
            app(dark, seller) {
                awaitText("Campbell Biology")
                click("Campbell Biology", substring = true)
                awaitText("Edit listing")
                shot("14-owner-detail")
                click("Edit listing")
                awaitText("Save changes")
                shot("15-edit-listing")
                back()
                back()
                click("Sell")
                clickDescription("My listings")
                awaitText("Reserved")
                shot("16-my-listings")
                click("Sold")
                click("Mini fridge", substring = true)
                awaitText("This item has found a new home")
                shot("17-owner-sold")
            }
        }
    }
}
