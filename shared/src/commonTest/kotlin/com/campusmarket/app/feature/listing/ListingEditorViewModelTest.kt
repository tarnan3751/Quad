package com.campusmarket.app.feature.listing

import com.campusmarket.app.data.Harness
import com.campusmarket.app.data.awaitValue
import com.campusmarket.app.data.decode
import com.campusmarket.app.data.respondError
import com.campusmarket.app.data.respondJson
import com.campusmarket.app.platform.PickedImage
import com.campusmarket.app.ui.state.UiState
import com.campusmarket.app.ui.state.UserMessages
import com.campusmarket.core.ApiRoutes
import com.campusmarket.core.CreateListingRequest
import com.campusmarket.core.ErrorCodes
import com.campusmarket.core.Limits
import com.campusmarket.core.ListingCategory
import com.campusmarket.core.ListingCondition
import com.campusmarket.core.ListingDetail
import com.campusmarket.core.ListingStatus
import com.campusmarket.core.Photo
import com.campusmarket.core.UpdateListingRequest
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ListingEditorViewModelTest {

    @BeforeTest
    fun setUp() = Dispatchers.setMain(StandardTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun image(name: String, size: Int = 64) = PickedImage(ByteArray(size) { 1 }, "$name.jpg", "image/jpeg")

    private class Env(val h: Harness, val messages: UserMessages, val sync: ListingSync)

    private fun TestScope.env(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData): Env {
        val h = Harness(handler = handler)
        val messages = UserMessages()
        return Env(h, messages, ListingSync(h.api, messages, backgroundScope))
    }

    /**
     * Fake `POST /photos`: photo "id-<file name>"; the first attempt for [failFirst] fails, with a 503 or, when [refuse],
     * a 400 naming the file. Uploads run in parallel.
     */
    private class PhotoServer(private val failFirst: String? = null, private val refuse: Boolean = false) {
        private val lock = Mutex()
        private val attempts = mutableMapOf<String, Int>()

        suspend fun attempts(name: String): Int = lock.withLock { attempts[name] ?: 0 }

        suspend fun MockRequestHandleScope.respond(req: HttpRequestData): HttpResponseData {
            val name = req.uploadedFileName().removeSuffix(".jpg")
            val attempt = lock.withLock { (attempts[name] ?: 0).plus(1).also { attempts[name] = it } }
            return if (name == failFirst && attempt == 1 && refuse) {
                respondError(HttpStatusCode.BadRequest, ErrorCodes.VALIDATION, "Bad photo", mapOf("file" to "That file isn't a supported image"))
            } else if (name == failFirst && attempt == 1) {
                respondError(HttpStatusCode.ServiceUnavailable, ErrorCodes.INTERNAL, "Upload failed")
            } else {
                respondJson(Photo.serializer(), Photo("id-$name", "http://test.local/uploads/$name.jpg"), HttpStatusCode.Created)
            }
        }
    }

    private suspend fun MockRequestHandleScope.photos(req: HttpRequestData, server: PhotoServer): HttpResponseData =
        with(server) { respond(req) }

    private fun ListingFormViewModel.fillValidFields() {
        onTitle("  Calculus textbook  ")
        onPrice("12.50")
        onCategory(ListingCategory.TEXTBOOKS)
        onCondition(ListingCondition.LIKE_NEW)
        onDescription("8th edition")
        onPickupLocation("Library steps")
    }

    @Test
    fun emptyFormIsRejectedLocallyWithAnErrorPerField() = runTest {
        val e = env { respondError(HttpStatusCode.NotFound, ErrorCodes.NOT_FOUND) }
        val vm = SellViewModel(e.h.api, e.messages, e.sync)

        vm.submit()
        runCurrent()

        val errors = vm.form.value.fieldErrors
        assertEquals(setOf("photoIds", "title", "category", "condition", "priceCents"), errors.keys)
        assertEquals("Add at least one photo", errors["photoIds"])
        assertNotNull(vm.form.value.formError)
        assertTrue(e.h.requests.isEmpty(), "nothing is sent while the form is invalid")

        // Editing a field clears its error.
        vm.onTitle("Lamp")
        assertNull(vm.form.value.fieldErrors["title"])
    }

    @Test
    fun priceIsValidatedAgainstLimitsAndParsedToCents() = runTest {
        val e = env { respondError(HttpStatusCode.NotFound, ErrorCodes.NOT_FOUND) }
        val vm = SellViewModel(e.h.api, e.messages, e.sync)

        for ((input, ok) in listOf("0.50" to false, "5000.01" to false, "12.345" to false, "abc" to false, "12.5" to true, "$1,200" to true)) {
            vm.onPrice(input)
            vm.submit()
            assertEquals(!ok, "priceCents" in vm.form.value.fieldErrors, "price '$input'")
        }
        vm.onTitle("ab")
        vm.submit()
        assertEquals("Use at least ${Limits.TITLE_MIN} characters", vm.form.value.fieldErrors["title"])
    }

    @Test
    fun photosUploadThenSubmitCreatesTheListingInDisplayOrder() = runTest {
        val server = PhotoServer()
        lateinit var created: CreateListingRequest
        val e = env { req ->
            when (req.url.encodedPath) {
                ApiRoutes.PHOTOS -> photos(req, server)
                ApiRoutes.LISTINGS -> {
                    created = req.decode(CreateListingRequest.serializer())
                    respondJson(ListingDetail.serializer(), detail(id = "new-1", isMine = true), HttpStatusCode.Created)
                }
                else -> respondError(HttpStatusCode.NotFound, ErrorCodes.NOT_FOUND)
            }
        }
        val vm = SellViewModel(e.h.api, e.messages, e.sync)
        val changes = MutableSharedFlow<ListingChange>(replay = 8)
        backgroundScope.launch { e.sync.changes.collect { changes.emit(it) } }

        vm.addPhotos(listOf(image("a"), image("b"), image("c")))
        assertEquals(3, vm.form.value.photos.size)
        assertTrue(vm.form.value.uploading)

        // Submitting mid-upload explains why it can't post yet.
        vm.fillValidFields()
        vm.submit()
        assertEquals("Wait for your photos to finish uploading", vm.form.value.fieldErrors["photoIds"])

        val uploaded = vm.form.awaitValue { s -> s.photos.all { it.status == UploadStatus.Uploaded } }
        assertEquals(listOf("id-a", "id-b", "id-c"), uploaded.photos.map { it.photoId })

        // Reorder: c becomes the cover, then b moves right (to the end).
        vm.makeCover(uploaded.photos[2].key)
        vm.movePhoto(uploaded.photos[1].key, 2)
        assertEquals(listOf("id-c", "id-a", "id-b"), vm.form.value.photos.map { it.photoId })

        vm.submit()
        runCurrent()
        val event = vm.events.flow.awaitValue()
        assertEquals(ListingEditorEvent.Created("new-1"), event)

        assertEquals(listOf("id-c", "id-a", "id-b"), created.photoIds)
        assertEquals("Calculus textbook", created.title)
        assertEquals(1250, created.priceCents)
        assertEquals(ListingCategory.TEXTBOOKS, created.category)
        assertEquals(ListingCondition.LIKE_NEW, created.condition)
        assertEquals("Library steps", created.pickupLocation)

        // The form resets for the next listing, other screens hear about it, and the user gets a confirmation.
        assertTrue(vm.form.value.isBlank)
        val change = changes.awaitValue()
        assertTrue(change is ListingChange.Saved && change.created && change.listingId == "new-1")
        assertEquals("Your listing is live", e.messages.messages.awaitValue().text)
    }

    @Test
    fun failedUploadBlocksSubmitUntilRetried() = runTest {
        val server = PhotoServer(failFirst = "b")
        val e = env { req ->
            when (req.url.encodedPath) {
                ApiRoutes.PHOTOS -> photos(req, server)
                else -> respondError(HttpStatusCode.NotFound, ErrorCodes.NOT_FOUND)
            }
        }
        val vm = SellViewModel(e.h.api, e.messages, e.sync)
        vm.addPhotos(listOf(image("a"), image("b")))

        val settled = vm.form.awaitValue { s -> !s.uploading }
        val failed = settled.photos.single { it.status == UploadStatus.Failed }
        assertEquals("Upload failed", failed.error)
        assertTrue(failed.retryable)
        assertNull(failed.photoId)

        vm.fillValidFields()
        vm.submit()
        assertEquals("Retry or remove the photos that didn't upload", vm.form.value.fieldErrors["photoIds"])

        vm.retryUpload(failed.key)
        assertEquals(UploadStatus.Uploading, vm.form.value.photos.single { it.key == failed.key }.status)
        val retried = vm.form.awaitValue { s -> s.photos.all { it.status == UploadStatus.Uploaded } }
        assertEquals(listOf("id-a", "id-b"), retried.photos.map { it.photoId })
        assertEquals(2, server.attempts("b"))
        assertNull(retried.fieldErrors["photoIds"])
    }

    @Test
    fun aPhotoTheServerRefusesIsNotOfferedForRetry() = runTest {
        val server = PhotoServer(failFirst = "b", refuse = true)
        val e = env { req -> if (req.url.encodedPath == ApiRoutes.PHOTOS) photos(req, server) else respondError(HttpStatusCode.NotFound, ErrorCodes.NOT_FOUND) }
        val vm = SellViewModel(e.h.api, e.messages, e.sync)
        vm.addPhotos(listOf(image("a"), image("b")))

        val failed = vm.form.awaitValue { s -> !s.uploading }.photos.single { it.status == UploadStatus.Failed }
        assertEquals("That file isn't a supported image", failed.error)
        assertTrue(failed.rejected)
        assertFalse(failed.retryable)
        vm.retryUpload(failed.key) // ignored: sending the same file again can't help
        runCurrent()
        assertEquals(1, server.attempts("b"))
    }

    @Test
    fun leavingAsksFirstOnlyWhenSomethingWouldBeLost() = runTest {
        val e = env { req ->
            if (req.method == HttpMethod.Get) respondJson(ListingDetail.serializer(), detail(id = "l1", isMine = true))
            else respondError(HttpStatusCode.InternalServerError, ErrorCodes.INTERNAL)
        }
        val sell = SellViewModel(e.h.api, e.messages, e.sync)
        assertFalse(sell.hasUnsavedChanges)
        sell.onTitle("Desk lamp")
        assertTrue(sell.hasUnsavedChanges)
        sell.clearForm()
        assertFalse(sell.hasUnsavedChanges)

        val edit = EditListingViewModel(EditListingRoute("l1"), e.h.api, e.messages, e.sync)
        assertFalse(edit.hasUnsavedChanges, "nothing loaded yet")
        edit.original.awaitValue { it is UiState.Content }
        assertFalse(edit.hasUnsavedChanges, "the prefilled form is the listing")
        edit.onPrice("65.00")
        assertFalse(edit.hasUnsavedChanges, "same amount, typed differently")
        edit.onTitle("Campbell Biology, 12th edition")
        assertTrue(edit.hasUnsavedChanges)
        edit.onTitle("Campbell Biology ")
        assertFalse(edit.hasUnsavedChanges, "surrounding spaces aren't a change")
        edit.makeCover(edit.form.value.photos[1].key)
        assertTrue(edit.hasUnsavedChanges, "photo order is a change")
    }

    @Test
    fun removingAPhotoCancelsItAndOversizedPhotosNeverUpload() = runTest {
        val server = PhotoServer()
        val e = env { req -> if (req.url.encodedPath == ApiRoutes.PHOTOS) photos(req, server) else respondError(HttpStatusCode.NotFound, ErrorCodes.NOT_FOUND) }
        val vm = SellViewModel(e.h.api, e.messages, e.sync)

        vm.addPhotos(listOf(image("big", size = Limits.PHOTO_MAX_BYTES + 1), image("ok")))
        val big = vm.form.value.photos.first()
        assertEquals(UploadStatus.Failed, big.status)
        assertTrue(big.error!!.startsWith("Too large"))

        vm.removePhoto(big.key)
        val done = vm.form.awaitValue { s -> !s.uploading }
        assertEquals(listOf("id-ok"), done.photos.map { it.photoId })
        assertEquals(0, server.attempts("big"))
    }

    @Test
    fun photoCountIsCappedAtTheLimit() = runTest {
        val server = PhotoServer()
        val e = env { req -> if (req.url.encodedPath == ApiRoutes.PHOTOS) photos(req, server) else respondError(HttpStatusCode.NotFound, ErrorCodes.NOT_FOUND) }
        val vm = SellViewModel(e.h.api, e.messages, e.sync)

        vm.addPhotos((1..10).map { image("p$it") })
        assertEquals(Limits.PHOTOS_MAX, vm.form.value.photos.size)
        assertEquals(0, vm.form.value.photosRemaining)
        assertTrue(e.messages.messages.awaitValue().text.contains("up to ${Limits.PHOTOS_MAX}"))

        val done = vm.form.awaitValue { s -> s.photos.all { it.status == UploadStatus.Uploaded } }
        assertEquals((1..8).map { "id-p$it" }, done.photos.map { it.photoId })
    }

    @Test
    fun serverFieldErrorsAreShownOnTheirFields() = runTest {
        val server = PhotoServer()
        val e = env { req ->
            when (req.url.encodedPath) {
                ApiRoutes.PHOTOS -> photos(req, server)
                ApiRoutes.LISTINGS -> respondError(
                    HttpStatusCode.BadRequest, ErrorCodes.VALIDATION, "Title must be 3–80 characters",
                    mapOf("title" to "Title must be 3–80 characters", "photoIds" to "Some photos could not be found; upload them again"),
                )
                else -> respondError(HttpStatusCode.NotFound, ErrorCodes.NOT_FOUND)
            }
        }
        val vm = SellViewModel(e.h.api, e.messages, e.sync)
        vm.addPhotos(listOf(image("a")))
        vm.form.awaitValue { s -> !s.uploading }
        vm.fillValidFields()

        vm.submit()
        val failed = vm.form.awaitValue { s -> !s.submitting && s.fieldErrors.isNotEmpty() }
        assertEquals("Title must be 3–80 characters", failed.fieldErrors["title"])
        assertEquals("Some photos could not be found; upload them again", failed.fieldErrors["photoIds"])
        assertEquals(1, e.h.requestsTo(ApiRoutes.LISTINGS).size)
        assertEquals(false, failed.isBlank, "the form keeps what the user typed")
    }

    @Test
    fun editPrefillsFromTheListingAndSendsOnlyChangedFields() = runTest {
        val original = detail(id = "l1", isMine = true)
        lateinit var patch: UpdateListingRequest
        val e = env { req ->
            when {
                req.url.encodedPath == ApiRoutes.listing("l1") && req.method == HttpMethod.Get -> respondJson(ListingDetail.serializer(), original)
                req.url.encodedPath == ApiRoutes.listing("l1") && req.method == HttpMethod.Patch -> {
                    patch = req.decode(UpdateListingRequest.serializer())
                    respondJson(ListingDetail.serializer(), original.copy(priceCents = 5000, photos = original.photos.reversed()))
                }
                else -> respondError(HttpStatusCode.NotFound, ErrorCodes.NOT_FOUND)
            }
        }
        val vm = EditListingViewModel(EditListingRoute("l1"), e.h.api, e.messages, e.sync)

        vm.original.awaitValue { it is UiState.Content }
        val form = vm.form.value
        assertEquals("Campbell Biology", form.title)
        assertEquals("65", form.price)
        assertEquals(ListingCategory.TEXTBOOKS, form.category)
        assertEquals(listOf("ph1", "ph2"), form.photos.map { it.photoId })
        assertTrue(form.photos.all { it.status == UploadStatus.Uploaded })

        vm.onPrice("50")
        vm.makeCover(form.photos[1].key)
        vm.submit()
        runCurrent()
        assertEquals(ListingEditorEvent.Saved, vm.events.flow.awaitValue())

        assertEquals(5000, patch.priceCents)
        assertEquals(listOf("ph2", "ph1"), patch.photoIds)
        assertNull(patch.title)
        assertNull(patch.description)
        assertNull(patch.category)
        assertNull(patch.condition)
        assertNull(patch.pickupLocation)
        assertEquals("Changes saved", e.messages.messages.awaitValue().text)
    }

    @Test
    fun editWithoutChangesJustCloses() = runTest {
        val e = env { req ->
            if (req.method == HttpMethod.Get) respondJson(ListingDetail.serializer(), detail(id = "l1", isMine = true))
            else respondError(HttpStatusCode.InternalServerError, ErrorCodes.INTERNAL)
        }
        val vm = EditListingViewModel(EditListingRoute("l1"), e.h.api, e.messages, e.sync)
        vm.original.awaitValue { it is UiState.Content }

        vm.submit()
        runCurrent()
        assertEquals(ListingEditorEvent.Saved, vm.events.flow.awaitValue())
        assertTrue(e.h.requests.none { it.method == HttpMethod.Patch })
    }

    @Test
    fun editConflictShowsTheServerMessage() = runTest {
        val e = env { req ->
            if (req.method == HttpMethod.Get) respondJson(ListingDetail.serializer(), detail(id = "l1", isMine = true))
            else respondError(HttpStatusCode.Conflict, ErrorCodes.CONFLICT, "Only active listings can be edited")
        }
        val vm = EditListingViewModel(EditListingRoute("l1"), e.h.api, e.messages, e.sync)
        vm.original.awaitValue { it is UiState.Content }

        vm.onTitle("Campbell Biology, 12th ed.")
        vm.submit()
        val failed = vm.form.awaitValue { it.formError != null && !it.submitting }
        assertEquals("Only active listings can be edited", failed.formError)
    }

    @Test
    fun editBlockedReasonFollowsServerRules() {
        assertNull(detail(isMine = true).editBlockedReason())
        assertNotNull(detail(isMine = false).editBlockedReason())
        for (status in listOf(ListingStatus.RESERVED, ListingStatus.SOLD, ListingStatus.REMOVED)) {
            assertNotNull(detail(isMine = true, status = status).editBlockedReason(), "$status")
        }
    }
}
