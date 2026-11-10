package com.campusmarket.app.feature.profile

import com.campusmarket.app.data.Harness
import com.campusmarket.app.data.SessionManager
import com.campusmarket.app.data.awaitValue
import com.campusmarket.app.data.respondJson
import com.campusmarket.app.data.testUser
import com.campusmarket.app.data.withMainDispatcher
import com.campusmarket.app.ui.Format
import com.campusmarket.app.ui.state.UiState
import com.campusmarket.app.ui.state.UserMessages
import com.campusmarket.core.ApiRoutes
import com.campusmarket.core.Limits
import com.campusmarket.core.ListingCategory
import com.campusmarket.core.ListingCondition
import com.campusmarket.core.ListingStatus
import com.campusmarket.core.ListingSummary
import com.campusmarket.core.OkResponse
import com.campusmarket.core.Page
import com.campusmarket.core.Rating
import com.campusmarket.core.UserPrivate
import com.campusmarket.core.UserPublic
import io.ktor.http.HttpMethod
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun listing(id: String, status: ListingStatus) = ListingSummary(
    id = id, title = "Item $id", priceCents = 1000, category = ListingCategory.OTHER, condition = ListingCondition.GOOD,
    status = status, sellerId = "u1", sellerName = "Ada", createdAt = 1L,
)

private val rater = UserPublic("r", "Rita", campusDomain = "uni.edu", joinedAt = 1L)

class ProfileStatsTest {

    @Test
    fun listingStatsCountActiveAndSoldAndSayWhenCapped() {
        val page = Page(listOf(listing("a", ListingStatus.ACTIVE), listing("b", ListingStatus.SOLD), listing("c", ListingStatus.RESERVED), listing("d", ListingStatus.ACTIVE)))
        val stats = MyListingStats.of(page)
        assertEquals(2, stats.active)
        assertEquals(1, stats.sold)
        assertEquals("2", stats.format(stats.active))
        assertEquals("2+", MyListingStats.of(page.copy(nextCursor = "next")).format(2))
    }

    @Test
    fun ratingBreakdownCountsEachStar() {
        val ratings = listOf(5, 5, 4, 1).mapIndexed { i, s -> Rating("r$i", "o$i", rater, "u1", s, createdAt = 1L) }
        val b = RatingBreakdown.of(ratings, complete = true)
        assertEquals(listOf(1, 0, 0, 1, 2), b.counts)
        assertEquals(0.5f, b.share(5))
        assertEquals(0f, b.share(3))
        assertEquals(0f, RatingBreakdown.of(emptyList(), complete = true).share(5))
    }

    @Test
    fun ratingStatIsSpokenWithTheRightPlural() {
        assertEquals("Rated ${Format.rating(4.0)} out of 5 from 1 rating", ratingStat(4.0, 1) {}.description)
        assertEquals("Rated ${Format.rating(4.5)} out of 5 from 2 ratings", ratingStat(4.5, 2) {}.description)
        assertEquals("No ratings yet", ratingStat(null, 0) {}.description)
    }

    @Test
    fun profileTabLoadsListingCountsWithOneBigPage() = withMainDispatcher {
        runTest {
            val h = Harness { req ->
                when (req.url.encodedPath) {
                    ApiRoutes.ME -> respondJson(UserPrivate.serializer(), testUser)
                    else -> respondJson(Page.serializer(ListingSummary.serializer()), Page(listOf(listing("a", ListingStatus.ACTIVE), listing("b", ListingStatus.SOLD))))
                }
            }
            val vm = ProfileViewModel(SessionManager(h.api, h.tokens, backgroundScope), h.api)
            assertNull(vm.state.value.stats)
            vm.refresh(pulled = true)
            val s = vm.state.awaitValue { it.stats != null && !it.refreshing }
            assertEquals(MyListingStats(active = 1, sold = 1, capped = false), s.stats)
            assertEquals("${Limits.PAGE_MAX}", h.requestsTo(ApiRoutes.MY_LISTINGS).single().url.parameters["limit"])
        }
    }

    @Test
    fun publicProfileLoadsListingsUpFrontForTheHeaderCount() = withMainDispatcher {
        runTest {
            val other = UserPublic("u2", "Liam", campusDomain = "uni.edu", joinedAt = 1L)
            val h = Harness { req ->
                when (req.url.encodedPath) {
                    ApiRoutes.user("u2") -> respondJson(UserPublic.serializer(), other)
                    else -> respondJson(Page.serializer(ListingSummary.serializer()), Page(listOf(listing("a", ListingStatus.ACTIVE))))
                }
            }
            val vm = UserProfileViewModel(UserProfileRoute("u2", showRatings = true), h.api, SessionManager(h.api, h.tokens, backgroundScope), UserMessages())
            vm.user.awaitValue { it is UiState.Content }
            val listings = vm.listings.state.awaitValue { it.loaded }
            assertEquals(1, listings.items.size)
            assertTrue(h.requestsTo(ApiRoutes.userRatings("u2")).isEmpty()) // ratings wait for their tab
        }
    }
}

class EditProfileLiveValidationTest {

    @Test
    fun nameIsCheckedAsYouTypeAndBlocksSaving() = withMainDispatcher {
        runTest {
            val h = Harness { error("no request expected") }
            val vm = EditProfileViewModel(h.api, SessionManager(h.api, h.tokens, backgroundScope), UserMessages())
            vm.onDisplayName("A")
            assertNotNull(vm.state.value.fieldErrors["displayName"])
            assertFalse(vm.state.value.canSave)
            vm.onDisplayName("Ada L")
            assertNull(vm.state.value.fieldErrors["displayName"])
            assertTrue(vm.state.value.canSave)
        }
    }
}

class UnblockUndoTest {

    @Test
    fun undoBlocksAgainAndPutsTheRowBack() = withMainDispatcher {
        runTest {
            val someone = UserPublic("u2", "Liam", campusDomain = "uni.edu", joinedAt = 1L, blockedByMe = true)
            val h = Harness { req ->
                if (req.method == HttpMethod.Get) respondJson(ListSerializer(UserPublic.serializer()), listOf(someone))
                else respondJson(OkResponse.serializer(), OkResponse())
            }
            val messages = UserMessages()
            val vm = BlockedUsersViewModel(h.api, messages)
            vm.state.awaitValue { it is UiState.Content }
            vm.unblock(someone)
            vm.state.awaitValue { it is UiState.Content && it.value.isEmpty() }

            val snack = messages.messages.first()
            assertEquals("Undo", snack.actionLabel)
            snack.onAction!!.invoke()
            vm.state.awaitValue { it is UiState.Content && it.value.map(UserPublic::id) == listOf("u2") }
            assertEquals(listOf(HttpMethod.Delete, HttpMethod.Post), h.requestsTo(ApiRoutes.userBlock("u2")).map { it.method })
        }
    }
}
