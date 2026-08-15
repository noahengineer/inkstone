package dev.inkstone.app.ink

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import dev.inkstone.core.model.Camera
import dev.inkstone.core.model.ImageCrop
import dev.inkstone.core.model.Matrix3
import dev.inkstone.core.model.PagePoint
import dev.inkstone.core.model.pageToView
import dev.inkstone.core.model.viewToPage
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import dev.inkstone.core.model.Offset as ViewOffset

/**
 * ⭐ **The crop tool the user asked for** — a frame on the picture that you drag from the corners.
 *
 * ## What this replaced, and why none of it could be salvaged
 *
 * M8c's crop was *"arm a chip, then draw the region"*: two `FilterChip`s (`Crop ▭` / `Crop ✎`) set a
 * mode, the next drag anywhere on the page became the crop, and the mode disarmed itself. Three
 * things follow from that shape, and all three are what *"the cropping is horrible"* is about.
 *
 * - **There were no handles at all.** `CropMath.handlesOf` — the one function that produces them —
 *   had a single caller in the whole repo and it was a unit test. Nothing drew a crop rectangle;
 *   the only chrome during a crop was a plain blue outline of the live drag.
 * - **It was one-shot, so *adjusting* a crop was unrepresentable.** Not hard: unrepresentable. The
 *   gesture ended, the mode cleared, and the only way to change your mind was to draw a whole new
 *   region — from a picture that was now showing you the *previous* crop.
 * - **Which made the second crop select the wrong pixels.** The drag was read as fractions of the
 *   **source** while the object's box was displaying the already-cropped region, so cropping a crop
 *   compounded an error the user could see and not explain.
 *
 * This shows the **whole uncropped picture**, dims what is being cut, and puts eight grips on the
 * kept region — which is what `CropMath`'s own KDoc said the UI did all along (*"entering crop mode
 * shows the picture uncropped with the current crop as handles"*), and what makes its fractions
 * correct without any composition step.
 *
 * ## Nothing is written until Done
 *
 * The drag moves [CropSession.region] and nothing else. `SetFormat` and `SetTransform` are written
 * once, by the caller's Done, as one gesture — so one undo puts the picture back and a crop being
 * dragged is not fifty ops on the journal. Cancel writes nothing at all.
 *
 * @param source the whole picture's frame — `ImageEdit.uncroppedFrame`, **not** the object's current
 *   transform. Handing in the live transform would draw the handles around the region that is
 *   already kept, which is the compounding bug above wearing a new coat.
 */
@Composable
fun CropOverlay(
    session: CropSession,
    camera: Camera,
    modifier: Modifier = Modifier,
    onRegion: (ImageCrop.Rect) -> Unit,
) {
    var grabbed by remember(session.id) { mutableStateOf<CropGrip?>(null) }

    // ⚠️ **`session` is read through `rememberUpdatedState` and is NOT a `pointerInput` key**, and
    // that is the difference between the handles dragging and not. Keying on `session.region` reads
    // naturally — the gesture wants the current region — but the region changes on *every* delta
    // this gesture reports, so Compose tore the `pointerInput` down and restarted it mid-drag: the
    // corner moved by one frame's worth and then the pen was talking to a handler that had already
    // been cancelled. The symptom is a crop frame that twitches a few pixels and stops, which reads
    // as a hit-test problem and is not one. Found on the emulator, not in any test.
    val current by rememberUpdatedState(session)

    Box(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(session.id, camera) {
                cropGesture(
                    session = { current },
                    camera = camera,
                    onGrab = { grabbed = it },
                    onRegion = onRegion,
                )
            },
    ) {
        Canvas(Modifier.fillMaxSize()) { drawCrop(session, camera, grabbed) }
    }
}

/**
 * The picture being cropped, in the terms the overlay needs.
 *
 * [region] is in fractions of the source, exactly as [ImageCrop.Rect] is, so what the overlay holds
 * and what gets written are the same numbers in the same space — there is no conversion to get
 * wrong at the moment of commit.
 */
@Immutable
data class CropSession(
    /** Identity of the image, so a re-selection restarts the gesture rather than continuing it. */
    val id: String,
    /** The **whole source**'s frame. See [CropOverlay]. */
    val source: Matrix3,
    val widthPt: Float,
    val heightPt: Float,
    val region: ImageCrop.Rect,
) {

    /** [u], [v] in `0..1` of the source, as a page point. */
    fun toPage(u: Float, v: Float): PagePoint = source.map(PagePoint(u * widthPt, v * heightPt))
}

/** Which grip is under the pen. The four corners move two edges; the four edges move one. */
enum class CropGrip { TOP_LEFT, TOP, TOP_RIGHT, RIGHT, BOTTOM_RIGHT, BOTTOM, BOTTOM_LEFT, LEFT, INSIDE }

// ---------------------------------------------------------------------------
// Gesture
// ---------------------------------------------------------------------------

private suspend fun PointerInputScope.cropGesture(
    session: () -> CropSession,
    camera: Camera,
    onGrab: (CropGrip?) -> Unit,
    onRegion: (ImageCrop.Rect) -> Unit,
) {
    awaitEachGesture {
        val down = awaitFirstDown()
        // Read at the press, not at the top of the loop: `awaitEachGesture` resumes here as soon as
        // the previous gesture ends, so a session read before the down is one drag out of date.
        val live = session()
        down.consume()
        val grip = gripAt(live, camera, camera.toPage(down.position))
        onGrab(grip)
        var region = live.region
        var pressed = true
        while (pressed) {
            val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id }
            if (change == null) {
                pressed = false
            } else {
                val delta = change.positionChange()
                change.consume()
                pressed = change.pressed
                if (grip != null && (delta.x != 0f || delta.y != 0f)) {
                    region = region.dragged(grip, delta, camera, live)
                    onRegion(region)
                }
            }
        }
        onGrab(null)
    }
}

/**
 * [region] after [grip] has been dragged by [delta] **view** pixels.
 *
 * ⚠️ The delta is converted into the *source's own* space, not merely divided by the camera scale.
 * A picture can be rotated and flipped ([ImageEdit.rotated] / [ImageEdit.flipped] write exactly
 * that), and dragging the top-left grip of a picture standing on its head must still move the
 * corner the user has their pen on. Mapping two page points through the inverse and subtracting is
 * what makes the grips follow the picture rather than the page.
 */
private fun ImageCrop.Rect.dragged(
    grip: CropGrip,
    delta: Offset,
    camera: Camera,
    session: CropSession,
): ImageCrop.Rect {
    val inverse = session.source.inverse() ?: return this
    val from = inverse.map(camera.toPage(Offset.Zero))
    val to = inverse.map(camera.toPage(delta))
    val du = (to.x - from.x) / session.widthPt
    val dv = (to.y - from.y) / session.heightPt

    var l = left
    var t = top
    var r = right
    var b = bottom
    when (grip) {
        CropGrip.INSIDE -> {
            // Moving the window keeps its size and stops at the edges of the picture, rather than
            // shrinking against them — a crop that silently resized itself while being moved would
            // be impossible to place.
            val shiftU = du.coerceIn(-l, 1f - r)
            val shiftV = dv.coerceIn(-t, 1f - b)
            l += shiftU
            r += shiftU
            t += shiftV
            b += shiftV
        }

        CropGrip.TOP_LEFT -> {
            l += du
            t += dv
        }

        CropGrip.TOP -> t += dv

        CropGrip.TOP_RIGHT -> {
            r += du
            t += dv
        }

        CropGrip.RIGHT -> r += du

        CropGrip.BOTTOM_RIGHT -> {
            r += du
            b += dv
        }

        CropGrip.BOTTOM -> b += dv

        CropGrip.BOTTOM_LEFT -> {
            l += du
            b += dv
        }

        CropGrip.LEFT -> l += du
    }
    // ⚠️ Corners move in x and y independently — deliberately NOT `SelectionHandle.resize`, whose
    // corners project the drag onto the box diagonal to preserve aspect. That is right for resizing
    // an object and wrong for a crop: "I want the top off" is the commonest crop there is, and an
    // aspect-locked corner cannot say it.
    return ImageCrop.Rect(
        left = l.coerceIn(0f, r - MIN_FRACTION),
        top = t.coerceIn(0f, b - MIN_FRACTION),
        right = r.coerceIn(l + MIN_FRACTION, 1f),
        bottom = b.coerceIn(t + MIN_FRACTION, 1f),
    )
}

/** The grip under [page], or null when the touch is outside the picture entirely. */
private fun gripAt(session: CropSession, camera: Camera, page: PagePoint): CropGrip? {
    val reach = GRIP_TOUCH_PX / camera.scale
    var best: CropGrip? = null
    var bestDistance = Float.MAX_VALUE
    for (grip in CropGrip.entries) {
        if (grip == CropGrip.INSIDE) continue
        val at = session.gripPoint(grip)
        val distance = hypotenuse(at.x - page.x, at.y - page.y)
        if (distance <= reach && distance < bestDistance) {
            bestDistance = distance
            best = grip
        }
    }
    if (best != null) return best
    // Not on a grip: inside the kept region means move the window, and anywhere else on the
    // picture is a miss rather than a new region — the frame is adjusted, never redrawn.
    return if (session.containsInRegion(page)) CropGrip.INSIDE else null
}

private fun CropSession.gripPoint(grip: CropGrip): PagePoint {
    val midU = (region.left + region.right) / 2f
    val midV = (region.top + region.bottom) / 2f
    return when (grip) {
        CropGrip.TOP_LEFT -> toPage(region.left, region.top)
        CropGrip.TOP -> toPage(midU, region.top)
        CropGrip.TOP_RIGHT -> toPage(region.right, region.top)
        CropGrip.RIGHT -> toPage(region.right, midV)
        CropGrip.BOTTOM_RIGHT -> toPage(region.right, region.bottom)
        CropGrip.BOTTOM -> toPage(midU, region.bottom)
        CropGrip.BOTTOM_LEFT -> toPage(region.left, region.bottom)
        CropGrip.LEFT -> toPage(region.left, midV)
        CropGrip.INSIDE -> toPage(midU, midV)
    }
}

/** True when [page] falls inside the kept region — tested in the source's space, so rotation works. */
private fun CropSession.containsInRegion(page: PagePoint): Boolean {
    val inverse = source.inverse() ?: return false
    val local = inverse.map(page)
    val u = local.x / widthPt
    val v = local.y / heightPt
    return u >= region.left && u <= region.right && v >= region.top && v <= region.bottom
}

// ---------------------------------------------------------------------------
// Chrome
// ---------------------------------------------------------------------------

private fun DrawScope.drawCrop(session: CropSession, camera: Camera, grabbed: CropGrip?) {
    val whole = listOf(
        session.toPage(0f, 0f),
        session.toPage(1f, 0f),
        session.toPage(1f, 1f),
        session.toPage(0f, 1f),
    )
    val kept = listOf(
        session.toPage(session.region.left, session.region.top),
        session.toPage(session.region.right, session.region.top),
        session.toPage(session.region.right, session.region.bottom),
        session.toPage(session.region.left, session.region.bottom),
    )

    // What is being cut, shaded — the whole picture with the kept region punched out of it. An
    // even-odd fill rather than four rectangles, because the picture can be rotated and four
    // axis-aligned bands cannot cover a rotated frame without overlapping at the corners (which
    // double-darkens them and reads as a bug).
    val shade = Path().apply {
        addQuad(whole, camera)
        addQuad(kept, camera)
        fillType = PathFillType.EvenOdd
    }
    drawPath(shade, Color(SHADE_ARGB))

    // The kept frame, and the thirds that make a crop feel like a crop.
    val outline = Path().apply { addQuad(kept, camera) }
    drawPath(outline, Color(FRAME_ARGB), style = Stroke(width = FRAME_PX))
    drawThirds(session, camera)

    for (grip in CropGrip.entries) {
        if (grip == CropGrip.INSIDE) continue
        val at = camera.toView(session.gripPoint(grip))
        val radius = if (grip == grabbed) GRIP_RADIUS_PX * GRABBED_SCALE else GRIP_RADIUS_PX
        drawCircle(Color(FRAME_ARGB), radius = radius, center = at)
        drawCircle(Color(GRIP_FILL_ARGB), radius = radius - FRAME_PX, center = at)
    }
}

private fun DrawScope.drawThirds(session: CropSession, camera: Camera) {
    val r = session.region
    for (step in 1 until THIRDS) {
        val u = r.left + (r.right - r.left) * step / THIRDS
        val v = r.top + (r.bottom - r.top) * step / THIRDS
        drawLine(
            Color(THIRDS_ARGB),
            camera.toView(session.toPage(u, r.top)),
            camera.toView(session.toPage(u, r.bottom)),
            strokeWidth = THIRDS_PX,
        )
        drawLine(
            Color(THIRDS_ARGB),
            camera.toView(session.toPage(r.left, v)),
            camera.toView(session.toPage(r.right, v)),
            strokeWidth = THIRDS_PX,
        )
    }
}

private fun Path.addQuad(corners: List<PagePoint>, camera: Camera) {
    val first = camera.toView(corners[0])
    moveTo(first.x, first.y)
    for (index in 1 until corners.size) {
        val point = camera.toView(corners[index])
        lineTo(point.x, point.y)
    }
    close()
}

private fun Camera.toPage(view: Offset): PagePoint = viewToPage(ViewOffset(view.x, view.y), this)

private fun Camera.toView(page: PagePoint): Offset {
    val view = pageToView(page, this)
    return Offset(view.x, view.y)
}

private fun hypotenuse(dx: Float, dy: Float): Float {
    val ax = abs(dx)
    val ay = abs(dy)
    if (ax == 0f && ay == 0f) return 0f
    val large = max(ax, ay)
    val small = min(ax, ay)
    val ratio = small / large
    return large * kotlin.math.sqrt(1f + ratio * ratio)
}

/** Below this the crop is a line, which `ObjectFormatCodec.decodeRect` refuses to decode. */
private const val MIN_FRACTION = 0.02f

/** The rule-of-thirds grid every crop tool draws — two lines each way, so three bands. */
private const val THIRDS = 3

private const val GRIP_TOUCH_PX = 56f
private const val GRIP_RADIUS_PX = 14f
private const val GRABBED_SCALE = 1.35f
private const val FRAME_PX = 3f
private const val THIRDS_PX = 1f

/**
 * ⚠️ The frame is **not white**, though every photo-editor crop tool's is.
 *
 * A crop overlay normally sits on a photograph, where white reads against almost anything. This one
 * sits on a *note* — white paper, and a picture that may itself be a screenshot of a white app — so
 * a white frame was invisible in exactly the case the emulator walk happened to use. The frame and
 * the grips are the same blue the selection chrome already uses, which also says *"this is chrome,
 * not part of your page"*.
 */
private const val SHADE_ARGB = 0x99101010.toInt()
private const val FRAME_ARGB = 0xFF1D4ED8.toInt()
private const val GRIP_FILL_ARGB = 0xFFFFFFFF.toInt()
private const val THIRDS_ARGB = 0x662C6BED
