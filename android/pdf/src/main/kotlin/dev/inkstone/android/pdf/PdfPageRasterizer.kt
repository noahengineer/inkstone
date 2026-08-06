package dev.inkstone.android.pdf

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.graphics.pdf.PdfRendererPreV
import android.graphics.pdf.RenderParams
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.ext.SdkExtensions
import androidx.annotation.RequiresExtension
import dev.inkstone.core.model.PageSize
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

/**
 * SPEC §6.1 — rendering a source page, so the ink has paper under it.
 *
 * ## Why the framework renderer and not `androidx.pdf`
 *
 * The M4.0 spike (docs/M4-PDF.md §1) ruled `androidx.pdf` out for *writing*, and
 * once writing is gone the only thing left to want from it is a bitmap.
 * `androidx.pdf` 1.0.0-alpha19 demands compile SDK extension level ≥ 19 and the
 * API 36 platform ships 17, so taking it would mean either the `android-36-ext19`
 * platform package or `compileSdk 37` — a toolchain change (SPEC §0.2 pinned AGP
 * around compileSdk 36) to obtain one method. It would also bring
 * `PdfViewerFragment`, whose own gesture handling would be competing with
 * `InkSurfaceView` for every touch.
 *
 * SPEC §6.1's stated fallback is the framework renderer, and it is in plain
 * `android-36`: [PdfRenderer] on API 35+, [PdfRendererPreV] on 30–34, which is
 * exactly this app's `minSdk 30` floor. That is the whole reason `PdfRendererPreV`
 * exists.
 *
 * ## Rendering, not compositing
 *
 * This returns a bitmap of the page and nothing else. `:android:ink` composites it
 * under the strokes through the same camera, so a source page and the ink on it
 * can never be a transform apart.
 *
 * **Off the main thread.** Rasterising a page of a scanned lecture is tens of
 * milliseconds, and nothing that runs while the user might be writing may cost
 * that (CLAUDE.md rule 7).
 */
public class PdfPageRasterizer(private val dispatcher: CoroutineDispatcher = Dispatchers.IO) {

    private companion object {
        /**
         * The S extension level that ships `android.graphics.pdf`'s renderer.
         *
         * A *mainline* version, not a platform one: it arrives with the PDF module,
         * so an API 30-34 device that never took that update does not have it.
         */
        const val PDF_EXTENSION_VERSION = 13
    }

    /**
     * @param widthPx the bitmap's width; height follows the page's aspect ratio.
     * @return the rendered page, or null when the file cannot be opened or the
     *   index is out of range. Null is a page with no paper under it, never a
     *   crash — a note whose source PDF went missing must still open and still
     *   show its ink.
     */
    public suspend fun rasterize(file: File, pageIndex: Int, widthPx: Int, pageSize: PageSize): Bitmap? =
        withContext(dispatcher) {
            if (widthPx <= 0 || pageIndex < 0 || !file.exists()) return@withContext null
            val heightPx = (widthPx * pageSize.heightPt / pageSize.widthPt).roundToInt().coerceAtLeast(1)

            runCatching {
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                    val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
                    // PDF pages are transparent where nothing is drawn, and a transparent
                    // scan over the editor's background reads as a rendering failure.
                    bitmap.eraseColor(Color.WHITE)
                    if (renderInto(descriptor, pageIndex, bitmap)) bitmap else null.also { bitmap.recycle() }
                }
            }.getOrNull()
        }

    private fun renderInto(descriptor: ParcelFileDescriptor, pageIndex: Int, bitmap: Bitmap): Boolean = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM ->
            renderModern(descriptor, pageIndex, bitmap)

        // API 30-34, and only where the mainline PDF module is actually installed.
        hasPreVRenderer() -> renderPreV(descriptor, pageIndex, bitmap)

        // ⚠️ SPEC §6.1 is slightly wrong and has been amended. It says PdfRendererPreV
        // "backports to 30-34", which is true only where **S extension level >= 13** is
        // present — it ships in the mainline PDF module, not in the platform, and a
        // device that has never taken that update does not have it. On such a device
        // there is no PDF renderer at all.
        //
        // The consequence is bounded and is the right one: an imported page draws on
        // white paper and the ink still draws on top of it, in the right place, because
        // the geometry never came from the renderer. Nothing is lost; the scan is just
        // not shown. The Galaxy Tab this is built for is API 35+, where the first branch
        // applies.
        else -> false
    }

    private fun hasPreVRenderer(): Boolean =
        SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= PDF_EXTENSION_VERSION

    private fun renderModern(descriptor: ParcelFileDescriptor, pageIndex: Int, bitmap: Bitmap): Boolean =
        PdfRenderer(descriptor).use { renderer ->
            if (pageIndex >= renderer.pageCount) return false
            renderer.openPage(pageIndex).use { page ->
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            }
            true
        }

    @RequiresExtension(extension = Build.VERSION_CODES.S, version = PDF_EXTENSION_VERSION)
    private fun renderPreV(descriptor: ParcelFileDescriptor, pageIndex: Int, bitmap: Bitmap): Boolean {
        // Not `use`: PdfRendererPreV is AutoCloseable only from the API level that
        // replaced it, so the two halves of this method cannot share a shape.
        val renderer = PdfRendererPreV(descriptor)
        try {
            if (pageIndex >= renderer.pageCount) return false
            val page = renderer.openPage(pageIndex)
            try {
                val params = RenderParams.Builder(RenderParams.RENDER_MODE_FOR_DISPLAY).build()
                page.render(bitmap, null, null, params)
            } finally {
                page.close()
            }
            return true
        } finally {
            renderer.close()
        }
    }
}
