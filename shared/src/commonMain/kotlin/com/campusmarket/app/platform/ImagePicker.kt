package com.campusmarket.app.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.ImageFormat
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.compressImage
import io.github.vinceglb.filekit.dialogs.FileKitMode
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.compose.rememberFilePickerLauncher
import io.github.vinceglb.filekit.name
import io.github.vinceglb.filekit.readBytes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** An image ready for `ApiClient.uploadPhoto(bytes, fileName, mimeType)`. */
class PickedImage(val bytes: ByteArray, val fileName: String, val mimeType: String)

class ImagePickerLauncher internal constructor(private val onLaunch: () -> Unit) {
    fun launch() = onLaunch()
}

/**
 * System image picker on every platform (Android photo picker, iOS PHPicker, desktop file dialog) via FileKit.
 * No runtime permissions needed.
 *
 * Picked images are re-encoded off the main thread to JPEG (≤ 2048 px on the long edge, quality 85), which converts
 * HEIC from iPhones and keeps uploads well under `Limits.PHOTO_MAX_BYTES`. [onPicked] is not called on cancel.
 *
 * ```
 * val picker = rememberImagePicker(maxItems = Limits.PHOTOS_MAX - photos.size) { images -> vm.upload(images) }
 * Button(onClick = picker::launch) { Text("Add photos") }
 * ```
 */
@Composable
fun rememberImagePicker(
    maxItems: Int = 1,
    onError: (Throwable) -> Unit = {},
    onPicked: (List<PickedImage>) -> Unit,
): ImagePickerLauncher {
    val scope = rememberCoroutineScope()
    val currentOnPicked = rememberUpdatedState(onPicked)
    val currentOnError = rememberUpdatedState(onError)

    fun handle(files: List<PlatformFile>) {
        if (files.isEmpty()) return
        scope.launch {
            try {
                val images = withContext(Dispatchers.Default) { files.map { prepareImage(it) } }
                currentOnPicked.value(images)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                currentOnError.value(e)
            }
        }
    }

    val launcher = if (maxItems <= 1) {
        rememberFilePickerLauncher(type = FileKitType.Image) { file -> if (file != null) handle(listOf(file)) }
    } else {
        rememberFilePickerLauncher(type = FileKitType.Image, mode = FileKitMode.Multiple(maxItems = maxItems)) { files ->
            if (files != null) handle(files)
        }
    }
    return remember(launcher) { ImagePickerLauncher { launcher.launch() } }
}

private suspend fun prepareImage(file: PlatformFile): PickedImage {
    val baseName = file.name.substringBeforeLast('.').ifBlank { "photo" }
    val jpeg = try {
        FileKit.compressImage(file = file, imageFormat = ImageFormat.JPEG, quality = 85, maxWidth = 2048, maxHeight = 2048)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }
    if (jpeg != null && jpeg.isNotEmpty()) return PickedImage(jpeg, "$baseName.jpg", "image/jpeg")

    // Fallback: upload the original bytes if they're a format the server accepts.
    val raw = file.readBytes()
    return PickedImage(raw, file.name, sniffMimeType(raw) ?: "application/octet-stream")
}

/** JPEG / PNG / WebP magic bytes (the only formats the server accepts). */
internal fun sniffMimeType(bytes: ByteArray): String? = when {
    bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte() -> "image/jpeg"
    bytes.size >= 8 && bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte() && bytes[2] == 'N'.code.toByte() -> "image/png"
    bytes.size >= 12 && bytes[0] == 'R'.code.toByte() && bytes[8] == 'W'.code.toByte() && bytes[9] == 'E'.code.toByte() -> "image/webp"
    else -> null
}
