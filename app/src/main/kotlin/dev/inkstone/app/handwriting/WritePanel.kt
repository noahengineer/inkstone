package dev.inkstone.app.handwriting

import android.util.Log
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import dev.inkstone.app.ink.ChromeGeometry
import dev.inkstone.app.ink.DockSlot
import dev.inkstone.app.ink.EditorTool
import dev.inkstone.app.ink.InkUiState
import dev.inkstone.app.ink.ObjectActions
import dev.inkstone.app.ink.ObjectToolState
import dev.inkstone.app.ink.PanelWindow
import dev.inkstone.app.ink.TextFocus
import dev.inkstone.app.note.NoteEditorViewModel
import dev.inkstone.app.ui.ChipKind
import dev.inkstone.app.ui.GlassPanel
import dev.inkstone.app.ui.Ink
import dev.inkstone.app.ui.InkDims
import dev.inkstone.app.ui.MicroLabel
import dev.inkstone.app.ui.PanelHeader
import dev.inkstone.app.ui.Readout
import dev.inkstone.app.ui.SegmentChip
import dev.inkstone.app.ui.Segmented
import dev.inkstone.app.ui.SlimSlider
import dev.inkstone.app.ui.TextChip
import dev.inkstone.core.handwriting.MathsPhrase
import dev.inkstone.core.handwriting.PageWriting
import dev.inkstone.core.handwriting.PlaceMode
import dev.inkstone.core.handwriting.Placement
import dev.inkstone.core.handwriting.PlacementSource
import dev.inkstone.core.handwriting.Placements
import dev.inkstone.core.handwriting.Refusal
import dev.inkstone.core.handwriting.SourcePreference
import dev.inkstone.core.handwriting.WordOutcome
import dev.inkstone.core.handwriting.WriteStyle
import dev.inkstone.core.handwriting.WrittenPhrase
import dev.inkstone.core.model.Camera
import dev.inkstone.core.model.FoldedPage
import dev.inkstone.core.model.GeneratedInk
import dev.inkstone.core.model.ObjectId
import dev.inkstone.core.model.PagePoint
import dev.inkstone.core.model.RecognitionUnavailable
import dev.inkstone.core.model.viewToPage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import dev.inkstone.core.model.Offset as ViewOffset

/**
 * ⭐ The Write tool's layer — docs/HANDWRITING-SYNTHESIS.md §6.2: the panel, the preview's drag onto
 * the page, and every [WriteCommand], run one at a time.
 *
 * Built like `ElementPanelLayer`, for its reasons: it fills the screen with no input of its own, so
 * everything outside the panel still reaches the ink surface, and its local space is the surface's
 * view space, which is what a drop's window position is converted into. Letting go over the panel is
 * *"never mind"*.
 *
 * Choosing the Write tool opens the panel; ✕ closes it and leaves the tool alone.
 */
@Composable
internal fun WritePanelLayer(
    ui: InkUiState,
    objects: ObjectToolState,
    editor: NoteEditorViewModel,
    actions: ObjectActions,
    geometry: ChromeGeometry,
    modifier: Modifier = Modifier,
) {
    val write = objects.write
    val writer = editor.handwriting
    val revision by writer.revision.collectAsState()
    var profile by remember { mutableStateOf<HandwritingWriter.Profile?>(null) }
    var preview by remember { mutableStateOf<WrittenPhrase?>(null) }
    var previewing by remember { mutableStateOf(false) }
    val drop = remember { WriteDrop() }
    val runner = remember(editor, actions) { WriteRunner(editor, ui, write, actions) }

    LaunchedEffect(ui.tool) { if (ui.tool == EditorTool.WRITE) write.open = true }
    LaunchedEffect(write.open, revision) { if (write.open) profile = writer.profile() }
    // ⭐ The live preview, generated as the user types, after a pause (§6.2). Read back like any drop,
    // so a word that would be refused is struck through before anyone tries to place it.
    LaunchedEffect(write.text, ui.handwritingNeatness, write.wrapWidthPt, write.maths, profile) {
        val loaded = profile?.takeIf { it.modelReady && !it.index.isEmpty }
        val text = write.text.trim()
        if (loaded == null || text.isEmpty()) {
            preview = null
            return@LaunchedEffect
        }
        delay(PREVIEW_DEBOUNCE_MS)
        previewing = true
        // In a finally: a keystroke cancels this effect mid-write, and the status line must not go on
        // saying "Reading it back…" over an empty box.
        try {
            preview = previewOf(editor, loaded, text, write.wrapWidthPt, ui.handwritingNeatness, write.maths)
        } finally {
            previewing = false
        }
    }
    LaunchedEffect(runner) {
        for (command in write.commands) runner.run(command)
    }
    MeasureWhileTyping(write, editor)
    PrimeWhileTyping(write, editor)

    Box(modifier = modifier.fillMaxSize().onGloballyPositioned { drop.layer = it }) {
        if (write.open) {
            PanelWindow(DockSlot.WRITE, ui, geometry) {
                WritePanel(
                    write = write,
                    ui = ui,
                    profile = profile,
                    preview = preview,
                    previewing = previewing,
                    drop = drop,
                    modifier = Modifier.onGloballyPositioned { drop.panelBounds = it.boundsInWindow() },
                )
            }
        }
        drop.dragging?.let { window ->
            val local = drop.layer?.windowToLocal(window)
            val phrase = preview
            if (local != null && phrase != null) PhraseGhost(phrase, local, ui.camera)
        }
    }
    drop.onDrop = { window ->
        val local = drop.layer?.windowToLocal(window)
        if (local != null && drop.panelBounds?.contains(window) != true) {
            write.send(WriteCommand.Place(viewToPage(ViewOffset(local.x, local.y), ui.camera), PlaceMode.DROP))
        }
    }
}

/**
 * §6.2's preview at the profile's own size. §10's budget is the first word within 300 ms of the
 * pause; the log line has counts and times only, never words.
 */
private suspend fun previewOf(
    editor: NoteEditorViewModel,
    profile: HandwritingWriter.Profile,
    text: String,
    wrapWidthPt: Float,
    neatness: Float,
    maths: Boolean,
): WrittenPhrase {
    val writer = editor.handwriting
    val started = System.nanoTime()
    val style = WriteStyle(
        xHeightPt = profile.index.stats.xHeightPt,
        slantRad = profile.index.stats.slantRad,
        angleRad = 0f,
        wrapWidthPt = wrapWidthPt,
        linePitchPt = profile.index.stats.xHeightPt * PREVIEW_PITCH,
        neatness = neatness,
    )
    var first = 0L
    val preference = SourcePreference(editor.openNoteId?.value, null, writer.today())
    val phrase = writer.write(text, style, profile, preference, maths) { if (first == 0L) first = System.nanoTime() }
    Log.i(
        TAG,
        "preview: words=${phrase.words.size} complete=${phrase.complete} maths=$maths " +
            "mathsWords=${phrase.words.count { (it as? WordOutcome.Written)?.isMaths == true }} " +
            "firstWordMs=${(first - started) / NANOS_PER_MS} allMs=${(System.nanoTime() - started) / NANOS_PER_MS} " +
            "debounceMs=$PREVIEW_DEBOUNCE_MS " +
            "neural=${phrase.words.count { (it as? WordOutcome.Written)?.backend == GeneratedInk.NEURAL }} " +
            "refused=${phrase.refusals.map { it.reason::class.simpleName }}",
    )
    return phrase
}

/**
 * ⭐ D15's measurement, made while the user types rather than when they let go: on the emulator's
 * debug build it took 4.2 s for the chemistry page, all of it after the drop. Cached by the page's
 * own writing (`HandwritingWriter.pageWriting`), so a phrase dropped does not make the next drop
 * measure again.
 */
@Composable
private fun MeasureWhileTyping(write: WriteToolState, editor: NoteEditorViewModel) {
    val note by editor.state.collectAsState()
    LaunchedEffect(write.open, write.matchNote, note.pageIndex, note.localRev) {
        val noteId = editor.openNoteId
        if (!write.open || !write.matchNote || noteId == null) return@LaunchedEffect
        delay(PREMEASURE_DELAY_MS)
        editor.currentFold()?.let { fold -> editor.handwriting.pageWriting(noteId, fold) }
    }
}

/**
 * ⭐ §19.1 — the neural backend's prime, computed when the panel opens and when the page changes,
 * so that it is off the typing path: the spike measured it at 143 ms of a 385 ms first word (§19.0).
 * Whether *Match the note* is on or not, because the prime is the user's own writing either way.
 */
@Composable
private fun PrimeWhileTyping(write: WriteToolState, editor: NoteEditorViewModel) {
    val note by editor.state.collectAsState()
    LaunchedEffect(write.open, note.pageIndex) {
        val noteId = editor.openNoteId
        if (!write.open || noteId == null) return@LaunchedEffect
        val writer = editor.handwriting
        val fold = editor.currentFold() ?: return@LaunchedEffect
        writer.primeAhead(writer.profile(), SourcePreference(noteId.value, fold.pageId.value, writer.today()))
    }
}

/**
 * ⭐ The selection bar's handwriting chips: *Write again* and *Edit text* on a generated phrase, and
 * *Handwrite* on a text box (§6.2). Each sends a command; the layer does the work.
 */
@Composable
fun HandwritingChips(write: WriteToolState, box: TextFocus?) {
    if (write.phrase != null) {
        TextChip(label = "Write again", enabled = !write.busy, onClick = { write.send(WriteCommand.WriteAgain) })
        TextChip(label = "Edit text", enabled = !write.busy, onClick = { write.send(WriteCommand.EditText) })
    }
    if (box != null && box.text.isNotBlank()) {
        TextChip(label = "Handwrite", enabled = !write.busy, onClick = { write.send(WriteCommand.Handwrite(box)) })
    }
}

@Composable
@Suppress("LongParameterList", "LongMethod")
private fun WritePanel(
    write: WriteToolState,
    ui: InkUiState,
    profile: HandwritingWriter.Profile?,
    preview: WrittenPhrase?,
    previewing: Boolean,
    drop: WriteDrop,
    modifier: Modifier = Modifier,
) {
    GlassPanel(modifier = modifier.width(PanelWidth)) {
        PanelHeader(if (write.editing != null) "Edit writing" else "Write", onClose = {
            write.open = false
            write.editing = null
        })
        when {
            profile == null -> MicroLabel("Reading what has been learned…")

            !profile.modelReady -> MicroLabel(
                "Writing needs the recognition model: every word is read back before it is drawn. " +
                    "Download it from My handwriting.",
            )

            profile.index.isEmpty -> MicroLabel(
                "Nothing learned yet. Write in your notes and let them be learned, or try the quick start " +
                    "on My handwriting.",
            )

            else -> MicroLabel("Writes from ${profile.words} words you wrote")
        }
        // The field keeps its own cursor so a maths key can insert at it; [WriteToolState.text] stays the
        // one truth, and a change from outside (Edit text, Done) resets the cursor to the end.
        var field by remember { mutableStateOf(TextFieldValue(write.text)) }
        val shown = if (field.text == write.text) field else TextFieldValue(write.text, TextRange(write.text.length))
        OutlinedTextField(
            value = shown,
            onValueChange = {
                field = it
                write.text = it.text
            },
            minLines = 2,
            maxLines = MAX_LINES,
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Words to write" },
        )
        if (write.maths) {
            MathsKeys { key ->
                val next = key.insertInto(shown)
                field = next
                write.text = next.text
            }
        }
        PreviewBox(preview, enabled = preview?.complete == true && !write.busy, drop = drop)
        MicroLabel(statusOf(write, preview, previewing))
        SettingRow("Neat") {
            SlimSlider(
                value = ui.handwritingNeatness,
                valueRange = 0f..1f,
                onValueChange = { ui.handwritingNeatness = it },
                label = "Neatness",
            )
        }
        SettingRow("Wrap") {
            SlimSlider(
                value = write.wrapWidthPt,
                valueRange = WriteToolState.MIN_WRAP_PT..WriteToolState.MAX_WRAP_PT,
                onValueChange = { write.wrapWidthPt = it },
                label = "Wrap width",
            )
            Readout(text = "${write.wrapWidthPt.roundToInt()} pt")
        }
        SettingRow("Match the note") {
            Segmented {
                SegmentChip(label = "On", selected = write.matchNote, onClick = { write.matchNote = true })
                SegmentChip(label = "Off", selected = !write.matchNote, onClick = { write.matchNote = false })
            }
        }
        SettingRow("Maths") {
            Segmented {
                SegmentChip(label = "On", selected = write.maths, onClick = { write.maths = true })
                SegmentChip(label = "Off", selected = !write.maths, onClick = { write.maths = false })
            }
        }
        if (write.editing != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(InkDims.GroupGap)) {
                TextChip(
                    label = "Done",
                    selected = true,
                    enabled = !write.busy && write.text.isNotBlank(),
                    onClick = { write.send(WriteCommand.Replace) },
                )
                TextChip(label = "Cancel", onClick = { write.editing = null })
            }
        }
    }
}

/**
 * One of the *Maths* keys — the calculator's templates (`:core:calc`'s `TemplateKind`) as text: what it
 * types before and after the cursor, wrapping whatever is selected, so `√` over a selected `x+1` is
 * `√(x+1)` with the cursor after it.
 */
internal class MathsKey(val label: String, private val before: String, private val after: String) {
    fun insertInto(value: TextFieldValue): TextFieldValue {
        val start = value.selection.min.coerceIn(0, value.text.length)
        val end = value.selection.max.coerceIn(start, value.text.length)
        val selected = value.text.substring(start, end)
        val text = value.text.substring(0, start) + before + selected + after + value.text.substring(end)
        return TextFieldValue(text, TextRange(start + before.length + selected.length))
    }

    companion object {
        /** Two rows of four: a panel 320 dp wide holds them without a scroll that hides the last ones. */
        val ROWS: List<List<MathsKey>> = listOf(
            listOf(
                MathsKey("a/b", "(", ")/()"),
                MathsKey("√", "√(", ")"),
                MathsKey("∛", "∛(", ")"),
                MathsKey("ⁿ√", "root(", ", )"),
            ),
            listOf(
                MathsKey("xⁿ", "^(", ")"),
                MathsKey("x₁", "_(", ")"),
                MathsKey("( )", "(", ")"),
                MathsKey("|x|", "|", "|"),
            ),
        )

        /**
         * The quick start's symbols prompt, as keys (docs/HANDWRITING-MATHS.md §2.4): a tablet keyboard
         * hides most of them and has no `θ` or `Δ` without a Greek layout. Written from the user's own
         * copies, never the network's (`GateCalibration.UNREADABLE`).
         */
        val SYMBOL_ROWS: List<List<MathsKey>> = listOf(
            listOf("±", "×", "÷", "π", "θ", "Δ").map { MathsKey(it, it, "") },
            listOf("≤", "≥", "≠", "≈", "°").map { MathsKey(it, it, "") },
        )
    }
}

@Composable
private fun MathsKeys(onKey: (MathsKey) -> Unit) {
    for (row in MathsKey.ROWS + MathsKey.SYMBOL_ROWS) {
        Row(horizontalArrangement = Arrangement.spacedBy(InkDims.GroupGap)) {
            // GLYPH: set as typed. A WORD chip upper-cases, and `π` would read `Π`, `θ` `Θ`, `xⁿ` `Xⁿ`.
            for (key in row) TextChip(label = key.label, kind = ChipKind.GLYPH, onClick = { onKey(key) })
        }
    }
}

/** A label and its control, on one line. */
@Composable
private fun SettingRow(label: String, content: @Composable RowScope.() -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(InkDims.GroupGap),
    ) {
        MicroLabel(label)
        content()
    }
}

private fun statusOf(write: WriteToolState, preview: WrittenPhrase?, previewing: Boolean): String = when {
    write.busy -> "Writing…"
    previewing -> "Reading it back…"
    write.text.isBlank() -> "Type, then drag the preview onto the page — or tap the page."
    preview == null -> ""
    !preview.complete -> preview.refusals.joinToString("  ") { "✕ ${it.text}: ${reasonOf(it.reason)}" }
    write.editing != null -> "Done replaces the phrase, as one undo."
    else -> readyLine(preview)
}

private fun readyLine(preview: WrittenPhrase): String = "Drag onto the page, or tap the page. " +
    checkedLine(preview.words.filterIsInstance<WordOutcome.Written>().map { it.readBack })

/**
 * What was checked, from each written word's [WordOutcome.Written.readBack]. A word the recogniser
 * cannot read (a maths symbol, a class it cannot judge, §8.2) is built from the user's own copies and
 * never read back, so "every word was read back" would claim a check that did not happen
 * (docs/HANDWRITING-MATHS.md §2.4).
 */
internal fun checkedLine(readBack: List<Boolean>): String {
    val unread = readBack.count { !it }
    return when {
        unread == 0 -> "Every word was read back."
        unread == readBack.size -> "Built from your own copies: nothing here can be read back."
        unread == 1 -> "Every word was read back but one, built from your own copies."
        else -> "Every word was read back but $unread, built from your own copies."
    }
}

/** What a refusal says (§8.2): the word, and what would fix it. */
internal fun reasonOf(reason: Refusal): String = when (reason) {
    is Refusal.MissingLetters ->
        "can't write ${reason.letters.joinToString(" ") { "'$it'" }} yet — write it a few times"

    is Refusal.Illegible -> "didn't read back" + (reason.readAs?.let { " (read as '$it')" } ?: "")

    Refusal.NothingLearned -> "nothing learned yet"

    Refusal.Signature -> "a name on its own would be a signature"

    is Refusal.Unavailable -> when (reason.reason) {
        RecognitionUnavailable.MODEL_NOT_DOWNLOADED -> "needs the recognition model"
        RecognitionUnavailable.PLAY_SERVICES_MISSING -> "needs Google Play services"
        RecognitionUnavailable.RECOGNISER_FAILED -> "the recogniser failed; try again"
    }
}

/** The preview, drawn to fit, and the handle a drag onto the page starts from. */
@Composable
private fun PreviewBox(preview: WrittenPhrase?, enabled: Boolean, drop: WriteDrop) {
    var coordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val pen = Ink.TextStrong
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(PreviewHeight)
            .background(Ink.Canvas, InkDims.ChipCorner)
            .semantics { contentDescription = "Handwriting preview" }
            .onGloballyPositioned { coordinates = it }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectDragGestures(
                    onDragStart = { at -> coordinates?.localToWindow(at)?.let { drop.dragging = it } },
                    onDrag = { change, _ ->
                        change.consume()
                        coordinates?.localToWindow(change.position)?.let { drop.dragging = it }
                    },
                    onDragEnd = { drop.release() },
                    onDragCancel = { drop.dragging = null },
                )
            },
    ) {
        val phrase = preview ?: return@Canvas
        val strokes = phrase.strokes(PagePoint(0f, 0f))
        val box = strokes.map { it.bounds() }.reduceOrNull { a, b -> a.union(b) } ?: return@Canvas
        val margin = PREVIEW_MARGIN_DP.dp.toPx()
        val scale = min(
            (size.width - 2 * margin) / max(box.width, 1f),
            (size.height - 2 * margin) / max(box.height, 1f),
        ).coerceAtMost(MAX_PREVIEW_SCALE * density)
        val dx = margin - box.left * scale
        val dy = margin - box.top * scale
        drawStrokes(strokes, scale, Offset(dx, dy), pen, PREVIEW_PEN_DP.dp.toPx())
    }
}

/** What the finger carries: the preview at the page's own scale, its first baseline at the finger. */
@Composable
private fun PhraseGhost(phrase: WrittenPhrase, at: Offset, camera: Camera) {
    Canvas(Modifier.fillMaxSize()) {
        val strokes = phrase.strokes(PagePoint(0f, 0f))
        val width = max(1f, phrase.style.xHeightPt * GHOST_PEN * camera.scale)
        drawStrokes(strokes, camera.scale, at, Color(GHOST_ARGB), width)
    }
}

private fun DrawScope.drawStrokes(
    strokes: List<dev.inkstone.core.model.PenTrajectory>,
    scale: Float,
    offset: Offset,
    color: Color,
    width: Float,
) {
    val pen = Stroke(width = width, cap = StrokeCap.Round, join = StrokeJoin.Round)
    for (stroke in strokes) {
        val path = Path()
        for (i in 0 until stroke.pointCount) {
            val x = stroke.x[i] * scale + offset.x
            val y = stroke.y[i] * scale + offset.y
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, color = color, style = pen)
    }
}

/** A drag in flight out of the panel, in window coordinates; see `ElementPanelLayer`'s `DropState`. */
@Stable
private class WriteDrop {
    var layer by mutableStateOf<LayoutCoordinates?>(null)
    var panelBounds by mutableStateOf<Rect?>(null)
    var dragging by mutableStateOf<Offset?>(null)
    var onDrop: (Offset) -> Unit = {}

    fun release() {
        val at = dragging ?: return
        dragging = null
        onDrop(at)
    }
}

/**
 * Runs [WriteCommand]s, one at a time, from the layer's own coroutine. Everything slow — measuring
 * the page, generating, reading back, encoding — is off the UI thread inside [HandwritingWriter]; the
 * one write is `stampHandwriting`, through the editor's own `editShownPage` and its lock.
 */
private class WriteRunner(
    private val editor: NoteEditorViewModel,
    private val ui: InkUiState,
    private val write: WriteToolState,
    private val actions: ObjectActions,
) {
    private val writer get() = editor.handwriting
    private val phrases = PhraseWriter(editor, ui)

    @Suppress("TooGenericExceptionCaught")
    suspend fun run(command: WriteCommand) {
        write.busy = true
        try {
            when (command) {
                is WriteCommand.Place -> place(command.point, command.mode)
                WriteCommand.WriteAgain -> again(write.phrase?.ids, text = null)
                WriteCommand.EditText -> openForEdit()
                WriteCommand.Replace -> write.editing?.let { again(it.strokes, write.text.trim()) }
                is WriteCommand.Handwrite -> handwrite(command.box)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            // One phrase's failure is that phrase's: the editor keeps running, and the user is told.
            Log.w(TAG, "write failed", failure)
            editor.report("Couldn't write that: ${failure.message ?: failure::class.simpleName}")
        } finally {
            write.busy = false
        }
    }

    private suspend fun place(point: PagePoint, mode: PlaceMode) {
        val text = write.text.trim()
        if (text.isEmpty()) return editor.report("Type something in the Write panel first.")
        val profile = ready() ?: return
        val fold = editor.currentFold() ?: return
        val stats = profile.index.stats
        val placement = Placements.resolve(point, mode, measure(fold), stats, write.wrapWidthPt, write.matchNote)
        val written =
            writeAt(text, placement, ui.handwritingNeatness, profile, fold, replacing = emptyList(), write.maths)
        if (written) write.editing = null
    }

    /** *Write again* ([text] null) and *Edit text*'s Done: the phrase's own place, new strokes. */
    private suspend fun again(ids: List<ObjectId>?, text: String?) {
        val selected = ids ?: return
        val profile = ready() ?: return
        val fold = editor.currentFold() ?: return
        val found = editor.phraseOf(selected)
            ?: return editor.report("This writing has lost its words — its first stroke was erased.")
        val at = found.placement ?: return editor.report("This writing does not say where it was put.")
        val placement = Placement(
            origin = PagePoint(at.originX, at.originY),
            xHeightPt = at.xHeightPt,
            slantRad = at.slantRad,
            angleRad = at.angleRad,
            linePitchPt = at.linePitchPt,
            wrapWidthPt = at.wrapWidthPt,
            pen = writer.penOf(fold, found.first),
            source = PlacementSource.PARAGRAPH,
            wordGapXHeights = at.wordGapXHeights,
        )
        val words = text ?: found.record.text
        // Write again keeps how the phrase was written; Edit text's Done takes the panel's switch, which
        // opening the phrase set from the same record.
        val maths = if (text == null) MathsPhrase.of(found.record) else write.maths
        if (writeAt(words, placement, at.neatness, profile, fold, replacing = found.strokes, maths)) {
            write.editing = null
            if (text != null) write.text = ""
        }
    }

    private suspend fun openForEdit() {
        val selected = write.phrase ?: return
        val found = editor.phraseOf(selected.ids)
            ?: return editor.report("This writing has lost its words — its first stroke was erased.")
        write.editing = found
        write.text = found.record.text
        write.maths = MathsPhrase.of(found.record)
        write.open = true
    }

    /** *Text box → handwriting*: the box's words, wrapped at its frame, the box tombstoned in the same entry. */
    private suspend fun handwrite(box: TextFocus) {
        val profile = ready() ?: return
        val fold = editor.currentFold() ?: return
        val corner = PagePoint(box.bounds.left, box.bounds.top)
        val writing = measure(fold)
        val width = box.bounds.width
        val measured = Placements.resolve(corner, PlaceMode.DROP, writing, profile.index.stats, width, write.matchNote)
        val placement = measured.copy(
            origin = PagePoint(box.bounds.left, box.bounds.top + measured.xHeightPt * FIRST_BASELINE_X_HEIGHTS),
            wrapWidthPt = box.bounds.width,
            angleRad = 0f,
        )
        writeAt(
            box.text.trim(),
            placement,
            ui.handwritingNeatness,
            profile,
            fold,
            replacing = listOf(box.id),
            write.maths,
        )
    }

    private suspend fun ready(): HandwritingWriter.Profile? = when (val hand = phrases.ready()) {
        is HandReadiness.Ready -> hand.profile
        is HandReadiness.NotReady -> null.also { editor.report(hand.reason) }
    }

    private suspend fun measure(fold: FoldedPage): PageWriting = phrases.measure(fold, write.matchNote)

    /** @return whether anything was written. The write itself is [PhraseWriter]'s, shared with the calculator. */
    @Suppress("LongParameterList")
    private suspend fun writeAt(
        text: String,
        placement: Placement,
        neatness: Float,
        profile: HandwritingWriter.Profile,
        fold: FoldedPage,
        replacing: List<ObjectId>,
        maths: Boolean,
    ): Boolean = when (val written = phrases.write(text, placement, neatness, profile, fold, replacing, maths)) {
        is PhraseWritten.NotWritten -> false.also { editor.report(written.reason) }

        is PhraseWritten.Stamped -> {
            actions.onSelectPlaced(written.ids)
            written.ids.isNotEmpty()
        }
    }
    private companion object {
        /** A text box's first baseline sits about two x-heights below its top. An estimate. */
        const val FIRST_BASELINE_X_HEIGHTS = 2.2f
    }
}

private const val TAG = "HandwritingWrite"
private const val NANOS_PER_MS = 1_000_000L
private val PanelWidth = 320.dp
private val PreviewHeight = 132.dp
private const val MAX_LINES = 6
private const val PREVIEW_DEBOUNCE_MS = 150L
private const val PREMEASURE_DELAY_MS = 500L
private const val PREVIEW_PITCH = 3.2f
private const val PREVIEW_MARGIN_DP = 8
private const val PREVIEW_PEN_DP = 1.6f
private const val MAX_PREVIEW_SCALE = 6f
private const val GHOST_ARGB = 0xCC0EA5E9.toInt()
private const val GHOST_PEN = 0.12f
