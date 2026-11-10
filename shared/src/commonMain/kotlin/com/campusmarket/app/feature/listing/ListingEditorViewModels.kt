package com.campusmarket.app.feature.listing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.campusmarket.app.data.ApiClient
import com.campusmarket.app.data.ApiError
import com.campusmarket.app.platform.PickedImage
import com.campusmarket.app.ui.Format
import com.campusmarket.app.ui.Validate
import com.campusmarket.app.ui.fieldErrorsOf
import com.campusmarket.app.ui.state.EventQueue
import com.campusmarket.app.ui.state.UiState
import com.campusmarket.app.ui.state.UserMessages
import com.campusmarket.app.ui.state.load
import com.campusmarket.app.ui.toFormErrors
import com.campusmarket.core.CreateListingRequest
import com.campusmarket.core.Limits
import com.campusmarket.core.ListingCategory
import com.campusmarket.core.ListingCondition
import com.campusmarket.core.ListingDetail
import com.campusmarket.core.ListingStatus
import com.campusmarket.core.UpdateListingRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/*
 * Create (Sell tab) and edit share one form: ListingFormViewModel holds the fields, photo uploads and validation;
 * SellViewModel and EditListingViewModel only differ in how they load and save.
 *
 * Photos upload as soon as they're picked (up to MAX_PARALLEL_UPLOADS at once), each with its own progress / failed
 * state and retry. The listing is saved with the uploaded photo ids in on-screen order; the first one is the cover.
 */

enum class UploadStatus { Uploading, Uploaded, Failed }

/** One photo in the editor: a local pick being uploaded, or an uploaded photo (possibly from the listing being edited). */
data class PhotoItem(
    /** Stable local key (list keys, actions). */
    val key: String,
    val status: UploadStatus,
    /** The picked image, kept for the preview and for retrying. Null for photos that were already on the listing. */
    val local: PickedImage? = null,
    val photoId: String? = null,
    val url: String? = null,
    /** 0..1 while uploading, once the upload has started and its size is known. */
    val progress: Float? = null,
    val error: String? = null,
    /** The server refused the file itself (unsupported or corrupt): sending it again won't help. */
    val rejected: Boolean = false,
) {
    /** A failed upload can be retried unless the file is over the size limit or was refused as a file. */
    val retryable: Boolean
        get() = status == UploadStatus.Failed && local != null && !rejected && local.bytes.size <= Limits.PHOTO_MAX_BYTES
}

data class ListingFormState(
    val photos: List<PhotoItem> = emptyList(),
    val title: String = "",
    val description: String = "",
    val category: ListingCategory? = null,
    val condition: ListingCondition? = null,
    /** Raw dollar input; converted with `Format.parsePriceCents` on submit. */
    val price: String = "",
    val pickupLocation: String = "",
    /** Keyed by request property: photoIds, title, description, category, condition, priceCents, pickupLocation. */
    val fieldErrors: Map<String, String> = emptyMap(),
    val formError: String? = null,
    val submitting: Boolean = false,
    /** Counts submits rejected by local validation, so the screen can bring the first error into view each time. */
    val rejectedSubmits: Int = 0,
) {
    val photosRemaining: Int get() = (Limits.PHOTOS_MAX - photos.size).coerceAtLeast(0)
    val uploading: Boolean get() = photos.any { it.status == UploadStatus.Uploading }
    val uploadingCount: Int get() = photos.count { it.status == UploadStatus.Uploading }

    /** True once the user has entered anything (Sell offers "Clear"). */
    val isBlank: Boolean
        get() = photos.isEmpty() && title.isBlank() && description.isBlank() && category == null && condition == null &&
            price.isBlank() && pickupLocation.isBlank()
}

sealed interface ListingEditorEvent {
    data class Created(val listingId: String) : ListingEditorEvent
    data object Saved : ListingEditorEvent
}

abstract class ListingFormViewModel(
    protected val api: ApiClient,
    protected val messages: UserMessages,
    protected val sync: ListingSync,
) : ViewModel() {
    protected val _form = MutableStateFlow(ListingFormState())
    val form: StateFlow<ListingFormState> = _form.asStateFlow()

    val events = EventQueue<ListingEditorEvent>()

    private val uploadJobs = mutableMapOf<String, Job>()
    private val uploadPermits = Semaphore(MAX_PARALLEL_UPLOADS)
    private var nextKey = 0

    // ------------------------------------------------------------------------------------------------ fields

    fun onTitle(value: String) = edit("title") { copy(title = value) }
    fun onDescription(value: String) = edit("description") { copy(description = value) }
    fun onCategory(value: ListingCategory) = edit("category") { copy(category = value) }
    fun onCondition(value: ListingCondition) = edit("condition") { copy(condition = value) }
    fun onPrice(value: String) = edit("priceCents") { copy(price = value) }
    fun onPickupLocation(value: String) = edit("pickupLocation") { copy(pickupLocation = value) }

    private inline fun edit(field: String, crossinline change: ListingFormState.() -> ListingFormState) =
        _form.update { it.change().copy(fieldErrors = it.fieldErrors - field, formError = null) }

    // ------------------------------------------------------------------------------------------------ photos

    /** Adds picked images (up to the 8-photo limit) and starts uploading them. */
    fun addPhotos(images: List<PickedImage>) {
        if (images.isEmpty()) return
        val room = _form.value.photosRemaining
        if (room == 0) {
            messages.show("A listing can have up to ${Limits.PHOTOS_MAX} photos.")
            return
        }
        val accepted = images.take(room)
        if (images.size > room) {
            messages.show("Added ${accepted.size} of ${images.size} photos. A listing can have up to ${Limits.PHOTOS_MAX}.")
        }
        val items = accepted.map { image ->
            if (image.bytes.size > Limits.PHOTO_MAX_BYTES) {
                PhotoItem(newKey(), UploadStatus.Failed, local = image, error = "Too large (max ${Limits.PHOTO_MAX_BYTES / (1024 * 1024)} MB)")
            } else {
                PhotoItem(newKey(), UploadStatus.Uploading, local = image)
            }
        }
        _form.update { it.copy(photos = it.photos + items, fieldErrors = it.fieldErrors - "photoIds", formError = null) }
        items.filter { it.status == UploadStatus.Uploading }.forEach { startUpload(it.key, it.local!!) }
    }

    fun onPickFailed() {
        messages.show("Couldn't open that image. Try a different photo.")
    }

    fun retryUpload(key: String) {
        val item = _form.value.photos.firstOrNull { it.key == key } ?: return
        val image = item.local ?: return
        if (!item.retryable) return
        updatePhoto(key) { it.copy(status = UploadStatus.Uploading, progress = null, error = null) }
        _form.update { it.copy(fieldErrors = it.fieldErrors - "photoIds", formError = null) }
        startUpload(key, image)
    }

    fun removePhoto(key: String) {
        uploadJobs.remove(key)?.cancel()
        _form.update { s -> s.copy(photos = s.photos.filterNot { it.key == key }, fieldErrors = s.fieldErrors - "photoIds") }
    }

    /** Moves a photo to [toIndex] (clamped). Index 0 is the cover photo. */
    fun movePhoto(key: String, toIndex: Int) = _form.update { s ->
        val from = s.photos.indexOfFirst { it.key == key }
        if (from < 0) return@update s
        val list = s.photos.toMutableList()
        val item = list.removeAt(from)
        list.add(toIndex.coerceIn(0, list.size), item)
        s.copy(photos = list)
    }

    fun makeCover(key: String) = movePhoto(key, 0)

    private fun startUpload(key: String, image: PickedImage) {
        uploadJobs[key]?.cancel()
        val job = viewModelScope.launch {
            try {
                val photo = uploadPermits.withPermit {
                    updatePhoto(key) { it.copy(progress = 0f) }
                    api.uploadPhoto(image.bytes, image.fileName, image.mimeType) { fraction ->
                        updatePhoto(key) { if (it.status == UploadStatus.Uploading) it.copy(progress = fraction) else it }
                    }
                }
                updatePhoto(key) { it.copy(status = UploadStatus.Uploaded, photoId = photo.id, url = photo.url, progress = null, error = null) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiError) {
                // 400/413 name the file as the problem; network errors, 5xx and rate limits are worth a retry.
                val rejected = e.status == 400 || e.status == 413
                updatePhoto(key) { it.copy(status = UploadStatus.Failed, progress = null, error = e.field("file") ?: e.message, rejected = rejected) }
            }
        }
        uploadJobs[key] = job
        job.invokeOnCompletion { if (uploadJobs[key] === job) uploadJobs.remove(key) }
    }

    private inline fun updatePhoto(key: String, crossinline transform: (PhotoItem) -> PhotoItem) =
        _form.update { s -> s.copy(photos = s.photos.map { if (it.key == key) transform(it) else it }) }

    protected fun newKey(): String = "photo-${nextKey++}"

    // ------------------------------------------------------------------------------------------------ submit

    /** The validated form, ready to send. */
    protected data class ValidForm(
        val photoIds: List<String>,
        val title: String,
        val description: String,
        val category: ListingCategory,
        val condition: ListingCondition,
        val priceCents: Int,
        val pickupLocation: String,
    )

    fun submit() {
        val s = _form.value
        if (s.submitting) return
        val errors = validate(s)
        if (errors.isNotEmpty()) {
            _form.update { it.copy(fieldErrors = errors, formError = "Check the highlighted fields.", rejectedSubmits = it.rejectedSubmits + 1) }
            return
        }
        val valid = ValidForm(
            photoIds = s.photos.map { it.photoId!! },
            title = s.title.trim(),
            description = s.description.trim(),
            category = s.category!!,
            condition = s.condition!!,
            priceCents = Format.parsePriceCents(s.price)!!,
            pickupLocation = s.pickupLocation.trim(),
        )
        _form.update { it.copy(submitting = true, formError = null) }
        viewModelScope.launch {
            try {
                save(valid)
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiError) {
                val (fields, form) = e.toFormErrors()
                _form.update {
                    it.copy(fieldErrors = fields, formError = form ?: if (fields.isNotEmpty()) "Check the highlighted fields." else null)
                }
            } finally {
                _form.update { it.copy(submitting = false) }
            }
        }
    }

    /** Sends [form] to the server and reports success (event + message). Throws [ApiError]. */
    protected abstract suspend fun save(form: ValidForm)

    /** Whether leaving now would throw away something the user entered (asks before discarding). */
    open val hasUnsavedChanges: Boolean get() = !_form.value.isBlank && !_form.value.submitting

    protected fun validate(s: ListingFormState): Map<String, String> = fieldErrorsOf(
        "photoIds" to when {
            s.photos.isEmpty() -> "Add at least one photo"
            s.photos.any { it.status == UploadStatus.Uploading } -> "Wait for your photos to finish uploading"
            s.photos.any { it.status == UploadStatus.Failed } -> "Retry or remove the photos that didn't upload"
            else -> null
        },
        "title" to Validate.listingTitle(s.title),
        "description" to Validate.listingDescription(s.description.trim()),
        "category" to if (s.category == null) "Choose a category" else null,
        "condition" to if (s.condition == null) "Choose a condition" else null,
        "priceCents" to Validate.price(s.price),
        "pickupLocation" to Validate.pickupLocation(s.pickupLocation.trim()),
    )

    protected fun cancelUploads() {
        uploadJobs.values.toList().forEach { it.cancel() }
        uploadJobs.clear()
    }

    companion object {
        const val MAX_PARALLEL_UPLOADS = 3
    }
}

// ---------------------------------------------------------------------------------------------------- create

/** Sell tab. After posting, the form resets and the screen opens the new listing. */
class SellViewModel(api: ApiClient, messages: UserMessages, sync: ListingSync) : ListingFormViewModel(api, messages, sync) {

    override suspend fun save(form: ValidForm) {
        val listing = api.createListing(
            CreateListingRequest(
                title = form.title,
                description = form.description,
                category = form.category,
                condition = form.condition,
                priceCents = form.priceCents,
                pickupLocation = form.pickupLocation,
                photoIds = form.photoIds,
            ),
        )
        sync.publish(ListingChange.Saved(listing, created = true))
        clearForm()
        messages.show("Your listing is live")
        events.send(ListingEditorEvent.Created(listing.id))
    }

    /** Discards everything entered so far (uploaded photos simply go unused). */
    fun clearForm() {
        cancelUploads()
        _form.value = ListingFormState()
    }
}

// ---------------------------------------------------------------------------------------------------- edit

/** Edit an ACTIVE listing of mine. Only changed fields are sent. */
class EditListingViewModel(
    route: EditListingRoute,
    api: ApiClient,
    messages: UserMessages,
    sync: ListingSync,
) : ListingFormViewModel(api, messages, sync) {
    val listingId: String = route.listingId

    private val _original = MutableStateFlow<UiState<ListingDetail>>(UiState.Loading)

    /** The listing as loaded; the form is prefilled from it. */
    val original: StateFlow<UiState<ListingDetail>> = _original.asStateFlow()

    init {
        reload()
    }

    fun reload() {
        load(_original) { api.listing(listingId).also(::prefill) }
    }

    private fun prefill(listing: ListingDetail) {
        cancelUploads()
        _form.value = ListingFormState(
            photos = listing.photos.map { PhotoItem(newKey(), UploadStatus.Uploaded, photoId = it.id, url = it.url) },
            title = listing.title,
            description = listing.description,
            category = listing.category,
            condition = listing.condition,
            price = Format.priceInput(listing.priceCents),
            pickupLocation = listing.pickupLocation,
        )
    }

    override val hasUnsavedChanges: Boolean
        get() {
            val before = (_original.value as? UiState.Content)?.value ?: return false
            val f = _form.value
            if (f.submitting) return false
            return f.title.trim() != before.title || f.description.trim() != before.description ||
                f.category != before.category || f.condition != before.condition ||
                Format.parsePriceCents(f.price) != before.priceCents || f.pickupLocation.trim() != before.pickupLocation ||
                f.photos.map { it.photoId } != before.photos.map { it.id }
        }

    override suspend fun save(form: ValidForm) {
        val before = (_original.value as? UiState.Content)?.value ?: return
        val request = UpdateListingRequest(
            title = form.title.takeIf { it != before.title },
            description = form.description.takeIf { it != before.description },
            category = form.category.takeIf { it != before.category },
            condition = form.condition.takeIf { it != before.condition },
            priceCents = form.priceCents.takeIf { it != before.priceCents },
            pickupLocation = form.pickupLocation.takeIf { it != before.pickupLocation },
            photoIds = form.photoIds.takeIf { it != before.photos.map { p -> p.id } },
        )
        if (request == UpdateListingRequest()) {
            events.send(ListingEditorEvent.Saved) // nothing changed
            return
        }
        val updated = api.updateListing(listingId, request)
        _original.value = UiState.Content(updated)
        sync.publish(ListingChange.Saved(updated, created = false))
        messages.show("Changes saved")
        events.send(ListingEditorEvent.Saved)
    }
}

/** Why a loaded listing can't be edited, or null if it can. */
fun ListingDetail.editBlockedReason(): String? = when {
    !isMine -> "Only the seller can edit this listing."
    status == ListingStatus.RESERVED -> "An order is in progress, so this listing can't be edited right now."
    status == ListingStatus.SOLD -> "Sold listings can't be edited."
    status == ListingStatus.REMOVED -> "A moderator removed this listing, so it can't be edited."
    status == ListingStatus.DELETED -> "This listing was deleted."
    else -> null
}
