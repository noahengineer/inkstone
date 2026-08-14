package dev.inkstone.app.note

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

/**
 * ⭐ ROADMAP M8c — *"insert an image from the gallery **or camera**"*.
 *
 * `ActivityResultContracts.TakePicture` writes the full-size photograph into a `Uri` the caller
 * supplies, so something has to supply one that the camera app is allowed to write to. That is a
 * `FileProvider` grant over this app's cache directory; `res/xml/file_paths.xml` declares the
 * `camera/` sub-path beside the one `NoteSharing` already uses.
 *
 * ## ⚠️ No `CAMERA` permission, deliberately
 *
 * `TakePicture` sends `MediaStore.ACTION_IMAGE_CAPTURE` to whichever camera app the user has, and
 * the platform only *requires* `android.permission.CAMERA` from an app that **declares** it. This
 * app does not, so there is no runtime prompt at all and no permission in the manifest — the same
 * reasoning `MainActivity` records for `PickVisualMedia`, where asking for `READ_MEDIA_IMAGES` to
 * set a folder icon would have been wildly out of proportion. M12 added the microphone; an app
 * that now asks for a microphone should be asking for less of everything else, not more.
 *
 * ## The file is a scratch file
 *
 * It lives in the cache directory and is swept on the next capture. The photograph that matters is
 * the one that reached the note's bundle — downscaled and re-encoded by `InsertedImage` — and
 * keeping the multi-megabyte original beside it would double what a picture costs for no benefit
 * anybody could reach.
 */
object CameraCapture {

    /**
     * A fresh target for the camera app to write into.
     *
     * @return the `content://` Uri to hand to the contract, or null when the directory cannot be
     *   made — which is a message to the user, not a crash.
     */
    fun target(context: Context): Uri? {
        val dir = File(context.cacheDir, CAPTURE_DIR)
        dir.deleteRecursively()
        if (!dir.mkdirs()) return null
        val file = File(dir, "capture.jpg")
        return runCatching {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        }.getOrNull()
    }

    private const val CAPTURE_DIR = "camera"
}
