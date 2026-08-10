package dev.inkstone.core.model

import kotlin.math.min

/**
 * SPEC §4.5 — the viewport.
 *
 * Maps **page space → view space**:
 *
 * ```
 * view = page * scale + translate
 * ```
 *
 * There is deliberately no rotation term. SPEC §4.5 defines the camera as
 * translate + scale, and a page that can be rotated on screen is a feature
 * nobody asked for that would have to be threaded through every conversion.
 *
 * @property translate where the page's top-left corner sits in the view, in pixels.
 * @property scale pixels per page point. Not a percentage — see [zoomPercent].
 */
public data class Camera(val translate: Offset, val scale: Float) {
    init {
        require(scale > 0f) { "Camera scale must be positive, was $scale" }
    }

    /**
     * Zoom as the user understands it: 100% means one page point is displayed at
     * its true physical size of 1/72 inch.
     *
     * @param pixelsPerPoint the display's `densityDpi / 72f`.
     */
    public fun zoomPercent(pixelsPerPoint: Float): Float = scale / pixelsPerPoint * PERCENT

    public companion object {
        private const val PERCENT = 100f

        public val Identity: Camera = Camera(Offset.Zero, 1f)
    }
}

/** The size of the drawing surface, in pixels. */
public data class Viewport(val widthPx: Float, val heightPx: Float)

/**
 * SPEC §4.5 — "zoom range 10%–1600%".
 *
 * Expressed as absolute [Camera.scale] bounds rather than percentages, because
 * that is the form every clamp actually needs, and deriving it once keeps the
 * device-density conversion in a single place.
 */
public data class ZoomLimits(val minScale: Float, val maxScale: Float) {
    init {
        require(minScale > 0f && maxScale >= minScale) { "Invalid zoom limits: $this" }
    }

    public fun clamp(scale: Float): Float = scale.coerceIn(minScale, maxScale)

    public companion object {
        public const val MIN_PERCENT: Float = 10f
        public const val MAX_PERCENT: Float = 1600f

        /** @param pixelsPerPoint the display's `densityDpi / 72f`. */
        public fun forDisplay(pixelsPerPoint: Float): ZoomLimits = ZoomLimits(
            minScale = pixelsPerPoint * MIN_PERCENT / 100f,
            maxScale = pixelsPerPoint * MAX_PERCENT / 100f,
        )
    }
}

// ---------------------------------------------------------------------------
// The two conversions. SPEC §4.4 names `viewToPage` as one of the three
// functions that live in :core:model and nowhere else.
// ---------------------------------------------------------------------------

/** View space → page space. The conversion every stroke passes through. */
public fun viewToPage(v: Offset, camera: Camera): PagePoint = PagePoint(
    x = (v.x - camera.translate.x) / camera.scale,
    y = (v.y - camera.translate.y) / camera.scale,
)

/** Page space → view space. */
public fun pageToView(p: PagePoint, camera: Camera): Offset = Offset(
    x = p.x * camera.scale + camera.translate.x,
    y = p.y * camera.scale + camera.translate.y,
)

/**
 * The page→view transform as a row-major 3×2 affine
 * `[m00, m01, m02, m10, m11, m12]`, where
 * `x' = m00·x + m01·y + m02` and `y' = m10·x + m11·y + m12`.
 *
 * This exists so `:android:ink` can build an `android.graphics.Matrix` without
 * either module re-deriving the arithmetic. [into] is caller-supplied because
 * this is called on the ink path, where CLAUDE.md rule 7 forbids allocation.
 */
public fun Camera.pageToViewAffine(into: FloatArray = FloatArray(AFFINE_SIZE)): FloatArray {
    require(into.size >= AFFINE_SIZE) { "Affine array must hold $AFFINE_SIZE values" }
    into[A_SCALE_X] = scale
    into[A_SKEW_X] = 0f
    into[A_TRANS_X] = translate.x
    into[A_SKEW_Y] = 0f
    into[A_SCALE_Y] = scale
    into[A_TRANS_Y] = translate.y
    return into
}

/** The inverse of [pageToViewAffine] — view space → page space. */
public fun Camera.viewToPageAffine(into: FloatArray = FloatArray(AFFINE_SIZE)): FloatArray {
    require(into.size >= AFFINE_SIZE) { "Affine array must hold $AFFINE_SIZE values" }
    val inverse = 1f / scale
    into[A_SCALE_X] = inverse
    into[A_SKEW_X] = 0f
    into[A_TRANS_X] = -translate.x * inverse
    into[A_SKEW_Y] = 0f
    into[A_SCALE_Y] = inverse
    into[A_TRANS_Y] = -translate.y * inverse
    return into
}

/** Number of values in a row-major 3×2 affine. */
public const val AFFINE_SIZE: Int = 6

private const val A_SCALE_X = 0
private const val A_SKEW_X = 1
private const val A_TRANS_X = 2
private const val A_SKEW_Y = 3
private const val A_SCALE_Y = 4
private const val A_TRANS_Y = 5

// ---------------------------------------------------------------------------
// Camera manipulation
// ---------------------------------------------------------------------------

/** Pan by a view-space delta. */
public fun Camera.panned(deltaView: Offset): Camera = copy(translate = translate + deltaView)

/**
 * Put the page-space point ([pageX], [pageY]) in the middle of a [viewWidth] x [viewHeight]
 * viewport, keeping the current zoom (SPEC §8.4 — a search hit scrolls to the word).
 *
 * The scale is deliberately untouched. Zooming to the word as well would be helpful exactly
 * once and disorienting every other time, and it would also throw away a zoom the user chose.
 */
public fun Camera.centredOn(pageX: Float, pageY: Float, viewWidth: Float, viewHeight: Float): Camera =
    copy(translate = Offset(viewWidth / 2f - pageX * scale, viewHeight / 2f - pageY * scale))

/**
 * Zoom by [factor] while holding the page point currently under [focusView]
 * stationary — the behaviour a pinch must have to feel attached to the fingers.
 *
 * The result is clamped to [limits], and the focus point stays fixed even when
 * the clamp bites, because the correction is computed from the *clamped* scale.
 */
public fun Camera.zoomedAround(focusView: Offset, factor: Float, limits: ZoomLimits): Camera {
    val target = limits.clamp(scale * factor)
    val focusPage = viewToPage(focusView, this)
    // Solve translate' such that focusPage maps back to focusView at the new scale.
    return Camera(
        translate = Offset(focusView.x - focusPage.x * target, focusView.y - focusPage.y * target),
        scale = target,
    )
}

/**
 * Keep the page *reachable* without pinning it in place.
 *
 * The first version of this centred any axis that fitted and hard-stopped at the
 * page edge otherwise. On the tablet that reads as the app fighting you: zoomed
 * out you could not move the page at all, and zoomed in it stopped dead at the
 * margins. Rejected on the device, in those words — *"it sticks you to the page
 * instead of letting you move around"*.
 *
 * So panning is free, and the only rule is that you cannot throw the page away:
 * at least [minVisiblePx] of it stays inside the viewport on each axis. The page
 * may sit anywhere, off-centre, mostly off-screen, at any zoom — but there is
 * always an edge left to grab, so no gesture can strand the user with a blank
 * screen and no way back.
 *
 * If the page is smaller than [minVisiblePx] on an axis, the whole of it is the
 * bound, otherwise the leash would be unsatisfiable.
 */
public fun Camera.leashedTo(page: PageSize, viewport: Viewport, minVisiblePx: Float): Camera = copy(
    translate = Offset(
        x = leashAxis(translate.x, page.widthPt * scale, viewport.widthPx, minVisiblePx),
        y = leashAxis(translate.y, page.heightPt * scale, viewport.heightPx, minVisiblePx),
    ),
)

private fun leashAxis(translate: Float, scaledExtent: Float, viewportExtent: Float, minVisible: Float): Float {
    val keep = min(minVisible, scaledExtent)
    // The page spans [translate, translate + scaledExtent] in view space. Its
    // near edge must not pass the far side of the viewport, and its far edge
    // must not pass the near side — each with `keep` still showing.
    return translate.coerceIn(keep - scaledExtent, viewportExtent - keep)
}

/** Centres [page] in [viewport]. Used by the fit modes, and by nothing else. */
private fun Camera.centredIn(page: PageSize, viewport: Viewport): Camera = copy(
    translate = Offset(
        x = (viewport.widthPx - page.widthPt * scale) / 2f,
        y = (viewport.heightPx - page.heightPt * scale) / 2f,
    ),
)

// ---------------------------------------------------------------------------
// Fit modes (SPEC §4.5)
// ---------------------------------------------------------------------------

/** SPEC §4.5 — "fit modes: fit-width (default), fit-page, actual size." */
public enum class FitMode { FIT_WIDTH, FIT_PAGE, ACTUAL_SIZE }

/**
 * The camera for [mode], centred in [viewport].
 *
 * @param pixelsPerPoint the display's `densityDpi / 72f`, which is what
 *   [FitMode.ACTUAL_SIZE] means: one point rendered at 1/72 inch.
 */
public fun cameraFor(
    mode: FitMode,
    page: PageSize,
    viewport: Viewport,
    pixelsPerPoint: Float,
    limits: ZoomLimits,
): Camera {
    val scale = when (mode) {
        FitMode.FIT_WIDTH -> viewport.widthPx / page.widthPt
        FitMode.FIT_PAGE -> min(viewport.widthPx / page.widthPt, viewport.heightPx / page.heightPt)
        FitMode.ACTUAL_SIZE -> pixelsPerPoint
    }
    val clamped = limits.clamp(scale)
    val centred = Camera(Offset.Zero, clamped).centredIn(page, viewport)
    // Fit-width means "show the full width and start at the top", so a page
    // taller than the viewport keeps its top edge rather than being centred
    // vertically on a page the user has not scrolled yet.
    return if (mode == FitMode.FIT_WIDTH && page.heightPt * clamped > viewport.heightPx) {
        centred.copy(translate = Offset(centred.translate.x, 0f))
    } else {
        centred
    }
}
