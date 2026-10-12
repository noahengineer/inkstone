package dev.inkstone.android.ink

import android.graphics.Bitmap

/**
 * ⭐ M8c — where an [dev.inkstone.core.model.ImageObject]'s pixels come from.
 *
 * ## Why this is an interface and not a loader
 *
 * SPEC §4.9.4: image bytes are **not** in the op log. An `ImageObject` names an entry under
 * `assets/` in the bundle, and reading that entry means opening a ZIP inside a PDF — which is
 * `:core:storage`'s job, and decoding the WebP it holds is a hundred milliseconds of work that
 * CLAUDE.md rule 7 forbids anywhere near the ink path. So this module never loads anything: it is
 * handed a lookup, exactly as [PagePainter] is handed a rasterised source page rather than
 * fetching one and as [ThumbnailSourcePages] is handed a rasteriser.
 *
 * `:app` owns the cache behind it because `:app` is the module that can see the bundle *and* the
 * screen, and because a cache with a memory budget is a decision about the whole app rather than
 * about one page.
 *
 * ## ⚠️ Null is a picture that must still be visible
 *
 * A null answer means *"this asset is not loaded, or not there"*, and both are ordinary: an image
 * merged from a peer arrives as an object one poll before its bytes do, and a note recovered from
 * a stranger's PDF may name assets that were never in it. [DryImage] draws a neutral placeholder
 * box for a null, and the reason it is visible rather than blank is SPEC §4.9.4's own framing —
 * every asset failure *"produces a blank rectangle rather than an error"*, and a blank rectangle
 * the user cannot distinguish from empty paper is a picture that has silently vanished. The
 * placeholder is what turns that into something a person can report.
 *
 * ## Threading
 *
 * Called from `onDraw` on the UI thread **and** from [InkPageThumbnailRenderer] on a background
 * dispatcher, so an implementation must be safe on both. A plain `LruCache` is; a lazily populated
 * `HashMap` is not.
 *
 * @return the decoded bitmap for [asset] — an entry name such as `assets/img-<objectId>.webp` — or
 *   null when it is not available. Never a recycled bitmap; the painter checks anyway, because a
 *   page scrolled away mid-render is not a reason to crash.
 */
public fun interface PageAssets {
    public fun bitmap(asset: String): Bitmap?

    /**
     * True when a null from [bitmap] means *"being decoded — ask again shortly"* rather than *"not
     * here"*.
     *
     * ⚠️ **The two nulls must look different on the glass.** The missing-asset placeholder is a
     * crossed box precisely so that it reads as a fault; a cache that decodes on request answers
     * null for every picture until its first batch lands, and drawing a page of crosses for the
     * second that takes is a page that reports eleven faults it does not have. The default is
     * false, so an implementation that cannot tell keeps the old honest-but-alarming picture.
     */
    public fun isPending(asset: String): Boolean = false
}
