package dev.inkstone.app.ink

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import dev.inkstone.android.ink.ElementBackdrop
import dev.inkstone.app.ui.ChipDivider
import dev.inkstone.app.ui.ChipKind
import dev.inkstone.app.ui.GlassBar
import dev.inkstone.app.ui.MicroLabel
import dev.inkstone.app.ui.SegmentChip
import dev.inkstone.app.ui.Segmented
import dev.inkstone.app.ui.TextChip
import dev.inkstone.core.model.Camera
import dev.inkstone.core.model.PagePoint
import dev.inkstone.core.model.PageRect
import dev.inkstone.core.model.ShapeKind
import dev.inkstone.core.model.pageToView

/**
 * *"Just make it so you know you are editing an Element."* — the bar that replaces the page bar while
 * an Element is open: its name, its tools, the backdrop, and the way out.
 *
 * ⚠️ **Done and Cancel are words, not icons**: they are the two consequential presses on this
 * screen, and one of them can update copies in other notes.
 */
@Composable
fun ElementEditorBar(
    objects: ObjectToolState,
    onFit: () -> Unit,
    onCancel: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    GlassBar(modifier = modifier) {
        Segmented {
            ElementEditorTool.entries.forEach { tool ->
                SegmentChip(
                    label = tool.label(),
                    selected = objects.elementTool == tool,
                    onClick = { objects.elementTool = if (objects.elementTool == tool) null else tool },
                )
            }
        }
        // ⭐ The bounds made the content's own box, on the grid — and the way back after deleting them.
        TextChip(label = "Fit", onClick = onFit)
        ChipDivider()
        TextChip(
            label = if (objects.backdrop == ElementBackdrop.DARK) "Dark" else "Light",
            onClick = {
                objects.backdrop =
                    if (objects.backdrop == ElementBackdrop.DARK) ElementBackdrop.LIGHT else ElementBackdrop.DARK
            },
        )
        ChipDivider()
        TextChip(label = "Cancel", onClick = onCancel)
        TextChip(label = "Done", selected = true, onClick = onDone)
    }
}

/**
 * *"Make it so you know you are editing an Element"* — in the top-left corner, where a note has its
 * calculator chip. In its own pill rather than on the tool bar, which then had no room for Done.
 */
@Composable
fun ElementEditorLabel(name: String?, modifier: Modifier = Modifier) {
    GlassBar(modifier = modifier, scrollable = false) {
        MicroLabel("Editing element")
        TextChip(label = name ?: "…", kind = ChipKind.VALUE, onClick = {})
    }
}

private fun ElementEditorTool.label(): String = when (this) {
    ElementEditorTool.AREA_RECT -> "Area ▭"
    ElementEditorTool.AREA_ELLIPSE -> "Area ◯"
    ElementEditorTool.PIVOT -> "Pivot"
}

/**
 * The Element editor's two gestures: drag out a knockout area, or tap to put the pivot down.
 *
 * Present only while one of [ElementEditorTool] is armed, for [PageOverlay]'s reason — an overlay in
 * front of the ink surface is a pen that draws nothing. Each tool disarms after one use, so the next
 * touch is the ordinary tool again and the area just drawn can be selected and moved.
 */
@Composable
fun ElementEditorOverlay(
    objects: ObjectToolState,
    camera: Camera,
    onArea: (ShapeKind, PageRect) -> Unit,
    onPivot: (PagePoint) -> Unit,
) {
    val tool = objects.elementTool ?: return
    var preview by remember { mutableStateOf<PageRect?>(null) }
    val area by rememberUpdatedState(onArea)
    val pivot by rememberUpdatedState(onPivot)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(tool, camera) {
                val shape = tool.shape
                if (shape == null) {
                    tapGesture(camera) { point ->
                        pivot(point)
                        objects.elementTool = null
                    }
                } else {
                    boxGesture(camera) { box, done ->
                        preview = if (done) null else box
                        if (done && box != null) {
                            area(shape, box)
                            objects.elementTool = null
                        }
                    }
                }
            },
    ) {
        Canvas(Modifier.fillMaxSize()) { preview?.let { drawPageBox(it, camera) } }
    }
}

/**
 * The pivot: a ring with a cross through it, drawn on top of everything — the Element editor shows
 * it always, a note shows it for a selected copy.
 *
 * Drawn the same size at every zoom, because it marks a point and not an area.
 */
@Composable
fun PivotMarker(at: PagePoint, camera: Camera) {
    Canvas(Modifier.fillMaxSize()) {
        val view = pageToView(at, camera)
        val centre = Offset(view.x, view.y)
        val radius = PIVOT_RADIUS_DP * density
        val arm = PIVOT_ARM_DP * density
        val stroke = PIVOT_STROKE_DP * density
        drawCircle(Color(PIVOT_HALO_ARGB), radius + stroke, centre, style = Stroke(stroke * 2))
        drawCircle(Color(PIVOT_ARGB), radius, centre, style = Stroke(stroke))
        drawLine(Color(PIVOT_ARGB), Offset(centre.x - arm, centre.y), Offset(centre.x + arm, centre.y), stroke)
        drawLine(Color(PIVOT_ARGB), Offset(centre.x, centre.y - arm), Offset(centre.x, centre.y + arm), stroke)
    }
}

/**
 * ⭐ *"When you save an element, if there are other copies of that element it asks if you want to
 * update them all."*
 *
 * A question with two honest answers and no third: update every copy this device placed, or leave
 * them all as they are. Not a conflict dialog (CLAUDE.md rule 6 is about sync) — it is the user's own
 * edit asking how far it should reach.
 */
@Composable
fun UpdateCopiesDialog(name: String, copies: Int, notes: Int, onUpdate: () -> Unit, onKeep: () -> Unit) {
    val where = if (notes == 1) "1 note" else "$notes notes"
    val what = if (copies == 1) "1 copy" else "$copies copies"
    AlertDialog(
        onDismissRequest = onKeep,
        title = { Text("Update copies of “$name”?") },
        text = {
            val one = copies == 1
            val them = if (one) "it" else "them"
            val asIs = if (one) "it is" else "they are"
            Text(
                "There ${if (one) "is" else "are"} $what in $where. Update $them to this version, or " +
                    "leave $them as $asIs? New copies will always use this version.",
            )
        },
        confirmButton = { TextButton(onClick = onUpdate) { Text("Update all") } },
        dismissButton = {
            TextButton(onClick = onKeep) {
                Text(
                    if (copies ==
                        1
                    ) {
                        "Keep it as it is"
                    } else {
                        "Keep as they are"
                    },
                )
            }
        },
    )
}

private const val PIVOT_RADIUS_DP = 9f
private const val PIVOT_ARM_DP = 15f
private const val PIVOT_STROKE_DP = 2f

/** Amber — not the selection's sky blue, so the pivot never reads as another handle. */
private const val PIVOT_ARGB = 0xFFF59E0B.toInt()
private const val PIVOT_HALO_ARGB = 0x66000000
