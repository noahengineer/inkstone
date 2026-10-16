package dev.inkstone.app.handwriting

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import dev.inkstone.app.search.ModelPanel
import dev.inkstone.app.search.RecognitionModelPanel
import dev.inkstone.app.ui.ChipDivider
import dev.inkstone.app.ui.ChipGrid
import dev.inkstone.app.ui.GlassBar
import dev.inkstone.app.ui.GlassPanel
import dev.inkstone.app.ui.Ink
import dev.inkstone.app.ui.InkDims
import dev.inkstone.app.ui.MicroLabel
import dev.inkstone.app.ui.Readout
import dev.inkstone.app.ui.SegmentChip
import dev.inkstone.app.ui.Segmented
import dev.inkstone.app.ui.TextChip
import dev.inkstone.app.ui.rememberDotGridBrush
import dev.inkstone.core.handwriting.ClassCalibration
import dev.inkstone.core.handwriting.Coverage
import dev.inkstone.core.handwriting.LabelSource
import dev.inkstone.core.handwriting.ProfileState
import dev.inkstone.core.handwriting.QuickStart
import dev.inkstone.core.handwriting.SampleSplit
import dev.inkstone.core.handwriting.TokenClass
import dev.inkstone.core.handwriting.quality.Backend
import dev.inkstone.core.handwriting.quality.BackendChoice
import dev.inkstone.core.handwriting.quality.BackendPolicy
import dev.inkstone.core.handwriting.quality.QualityMeasure
import dev.inkstone.core.model.PenTrajectory
import dev.inkstone.core.model.RecognitionUnavailable
import kotlin.math.max
import kotlin.math.min

/**
 * ⭐ *My handwriting* — docs/HANDWRITING-SYNTHESIS.md §5.7.
 *
 * What handwriting synthesis has learned from the user's own writing, and every way to change it:
 * pause learning, choose whether notes written before the feature count, correct what a word says,
 * remove a word, ignore a whole note, and delete everything. Nothing here is a dialog that arrives
 * on its own (CLAUDE.md rule 6); the only two dialogs are ones the user opened.
 *
 * Each word is drawn **as the normaliser sees it** — on a baseline, with its x-height marked and its
 * slant removed — which makes this screen phase 2's check on real ink as well as the user's: a word
 * floating off its baseline is the normaliser being wrong, in plain sight (§5.2).
 */
@Composable
fun HandwritingScreen(
    viewModel: HandwritingViewModel,
    onDownloadModel: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var quickStart by remember { mutableStateOf(false) }
    // ⭐ D14 — over this screen rather than a route of its own: it is a few minutes long, and Back
    // from it is back here, which refreshes the list with what it recorded.
    if (quickStart) {
        BackHandler { quickStart = false }
        // Read once, as it opens: the first prompt not yet recorded (§18.10's maths prompts come last).
        val startAt = remember { QuickStart.firstToDo(viewModel.overview.value?.quickStartDone.orEmpty()) }
        QuickStartScreen(onSave = viewModel::saveQuickStart, onDone = { quickStart = false }, startAt = startAt)
    } else {
        HandwritingHome(viewModel, onDownloadModel, onBack, onQuickStart = { quickStart = true }, modifier)
    }
}

@Composable
private fun HandwritingHome(
    viewModel: HandwritingViewModel,
    onDownloadModel: () -> Unit,
    onBack: () -> Unit,
    onQuickStart: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onBack)
    val overview by viewModel.overview.collectAsState()
    val progress by viewModel.progress.collectAsState()
    val modelPanel by viewModel.modelPanel.collectAsState()
    var correcting by remember { mutableStateOf<SampleRow?>(null) }
    var confirmForget by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { viewModel.refresh() }

    Box(modifier = modifier.fillMaxSize().background(Ink.Canvas)) {
        Box(modifier = Modifier.fillMaxSize().background(rememberDotGridBrush()))
        Column(modifier = Modifier.fillMaxSize()) {
            GlassBar(modifier = Modifier.padding(InkDims.EdgePadding)) {
                TextChip(label = "‹ Back", onClick = onBack)
                ChipDivider()
                MicroLabel("My handwriting", color = Ink.TextStrong)
            }
            val data = overview
            if (data == null) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    MicroLabel("Reading what has been learned…")
                }
            } else {
                HandwritingList(
                    data = data,
                    progress = progress,
                    modelPanel = modelPanel,
                    viewModel = viewModel,
                    onDownloadModel = onDownloadModel,
                    onCorrect = { correcting = it },
                    onForget = { confirmForget = true },
                    onQuickStart = onQuickStart,
                )
            }
        }
    }

    correcting?.let { row ->
        CorrectDialog(
            row = row,
            onDismiss = { correcting = null },
            onSave = { text ->
                viewModel.correct(row, text)
                correcting = null
            },
        )
    }
    if (confirmForget) {
        ForgetDialog(
            onDismiss = { confirmForget = false },
            onForget = {
                viewModel.forgetEverything()
                confirmForget = false
            },
        )
    }
}

@Composable
@Suppress("LongParameterList") // The screen's state and one callback per thing it can open.
private fun HandwritingList(
    data: HandwritingOverview,
    progress: LearningProgress,
    modelPanel: ModelPanel?,
    viewModel: HandwritingViewModel,
    onDownloadModel: () -> Unit,
    onCorrect: (SampleRow) -> Unit,
    onForget: () -> Unit,
    onQuickStart: () -> Unit,
) {
    LazyColumn(
        contentPadding = PaddingValues(InkDims.EdgePadding),
        verticalArrangement = Arrangement.spacedBy(InkDims.GroupGap),
        modifier = Modifier.fillMaxSize(),
    ) {
        // Above the status, because until the model is here nothing below it can happen. Shown in
        // every state, offer included: on this screen the model is not an extra, it is the input.
        modelPanel?.let { panel ->
            item {
                RecognitionModelPanel(
                    panel = panel,
                    purpose = "Learning your handwriting needs the recognition model: it is what reads your words.",
                    onDownload = onDownloadModel,
                    onDismiss = viewModel::dismissModelPanel,
                )
            }
        }
        item { StatusPanel(data, progress, viewModel, onQuickStart) }
        item { CoveragePanel(data.coverage) }
        item { PersonalisationPanel(data.state, viewModel::setBackendChoice) }
        if (data.recent.isEmpty()) {
            item {
                GlassPanel(modifier = Modifier.fillMaxWidth()) {
                    MicroLabel("Nothing learned yet. Write in a note, then press Learn now.")
                }
            }
        }
        items(data.recent, key = { it.sample.id }) { row ->
            SampleEntry(
                row = row,
                onCorrect = { onCorrect(row) },
                onConfirm = { viewModel.confirm(row) },
                onRemove = { viewModel.remove(row) },
                onIgnoreNote = { viewModel.ignoreNote(row) },
            )
        }
        item {
            GlassPanel(modifier = Modifier.fillMaxWidth()) {
                MicroLabel("Everything here stays on this device. Deleting moves it to a trash for 30 days.")
                TextChip(label = "Delete my handwriting data", danger = true, onClick = onForget)
            }
        }
    }
}

@Composable
private fun ForgetDialog(onDismiss: () -> Unit, onForget: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete what has been learned?") },
        text = {
            Text(
                "Every learned word and every correction moves to this profile's trash and is removed " +
                    "after 30 days. Nothing in your notes changes, and learning starts again from your notes.",
            )
        },
        confirmButton = { TextButton(onClick = onForget) { Text("Delete") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Keep") } },
    )
}

@Composable
private fun StatusPanel(
    data: HandwritingOverview,
    progress: LearningProgress,
    viewModel: HandwritingViewModel,
    onQuickStart: () -> Unit,
) {
    GlassPanel(modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(InkDims.GroupGap),
        ) {
            MicroLabel("Learning")
            Segmented {
                SegmentChip(label = "On", selected = data.state.learning, onClick = { viewModel.setLearning(true) })
                SegmentChip(label = "Paused", selected = !data.state.learning, onClick = {
                    viewModel.setLearning(false)
                })
            }
            MicroLabel("Learn from")
            Segmented {
                SegmentChip(
                    label = "New writing",
                    selected = !data.state.learnFromOlderNotes,
                    onClick = { viewModel.setLearnFromOlderNotes(false) },
                )
                SegmentChip(
                    label = "All my notes",
                    selected = data.state.learnFromOlderNotes,
                    onClick = { viewModel.setLearnFromOlderNotes(true) },
                )
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(InkDims.GroupGap),
        ) {
            val running = progress is LearningProgress.Running
            TextChip(
                label = if (running) "Learning…" else "Learn now",
                enabled = !running && data.modelReady && data.state.learning,
                onClick = viewModel::learnNow,
            )
            MicroLabel(progressText(progress, data))
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(InkDims.GroupGap),
        ) {
            // ⭐ D14 — optional, never required: a few prompted sentences, for a hand with no notes yet.
            TextChip(label = "Quick start", onClick = onQuickStart)
            MicroLabel("About three minutes copying sentences that cover the alphabet.")
        }
        if (data.unreadableShards > 0) {
            MicroLabel("${data.unreadableShards} file(s) were written by a newer version and are left untouched.")
        }
    }
}

private fun progressText(progress: LearningProgress, data: HandwritingOverview): String = when (progress) {
    is LearningProgress.Running -> "${progress.samples} words from ${progress.pages} pages so far"

    is LearningProgress.Idle -> {
        val last = progress.last
        when {
            !data.modelReady -> "Needs the recognition model."

            last == null -> "Learns overnight while charging, or now."

            last.paused -> "Paused."

            last.unavailable == RecognitionUnavailable.MODEL_NOT_DOWNLOADED -> "Needs the recognition model."

            last.unavailable != null -> "The recogniser could not run. It will try again."

            else ->
                "Last pass: ${last.added} words from ${last.pagesHarvested} pages" +
                    (if (last.withdrawn > 0) ", ${last.withdrawn} withdrawn" else "") +
                    (if (!last.finished) " — more to do next time" else "")
        }
    }
}

@Composable
private fun CoveragePanel(coverage: Coverage) {
    GlassPanel(modifier = Modifier.fillMaxWidth()) {
        MicroLabel(
            "${coverage.words} words learned · ${coverage.distinctWords} different · " +
                "${coverage.heldOut} kept back to measure with",
            color = Ink.TextStrong,
        )
        MicroLabel(
            "Letters cut out and checked — the number is how many of each" +
                if (coverage.joinedRuns > 0) " · ${coverage.joinedRuns} joined runs" else "",
        )
        val alphabet =
            ('a'..'z').map { it.toString() } + ('A'..'Z').map { it.toString() } + ('0'..'9').map { it.toString() }
        val others = coverage.letters.keys.filter { it !in alphabet }.sorted()
        ChipGrid(columns = LETTER_COLUMNS) {
            for (letter in alphabet + others) {
                val count = coverage.letters[letter] ?: 0
                // The gap after each pair has to be wider than the space inside it, or "a 0  b 0"
                // reads as "a 0b 0": each count closer to the next letter than to its own.
                Readout(
                    text = "$letter $count",
                    modifier = Modifier.padding(end = InkDims.GroupGap),
                    color = if (count > 0) Ink.TextStrong else Ink.TextMuted,
                )
            }
        }
    }
}

/**
 * ⭐ docs/HANDWRITING-SYNTHESIS.md §8 on this screen: who writes first (§8.4's *Auto / My letters /
 * Model*), how close each backend has measured to the user's own hand over time (§8.3's curve, the
 * evidence for *"improves with use"*), and what the gate trusts the recogniser to judge (§8.2).
 * Numbers only — no word the user wrote.
 */
@Composable
private fun PersonalisationPanel(state: ProfileState, onChoose: (BackendChoice) -> Unit) {
    GlassPanel(modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(InkDims.GroupGap),
        ) {
            MicroLabel("Who writes first")
            Segmented {
                SegmentChip(label = "Auto", selected = state.backend == BackendChoice.AUTO, onClick = {
                    onChoose(BackendChoice.AUTO)
                })
                SegmentChip(
                    label = "My letters",
                    selected = state.backend == BackendChoice.MY_LETTERS,
                    onClick = { onChoose(BackendChoice.MY_LETTERS) },
                )
                SegmentChip(label = "Model", selected = state.backend == BackendChoice.MODEL, onClick = {
                    onChoose(BackendChoice.MODEL)
                })
            }
        }
        MicroLabel(primaryText(state))
        // Not a MicroLabel: it upper-cases, and a routed `m` read as `M` on the tablet.
        routingText(state)?.let { Readout(it) }
        val history = state.quality
        if (history.isEmpty()) {
            MicroLabel("How close each one comes to your hand is measured overnight, while the tablet charges.")
        } else {
            MicroLabel(
                "How close to your hand: 0 is the model before it learned you, 1 is as close as you are to yourself",
                color = Ink.TextStrong,
            )
            for (m in history.takeLast(HISTORY_ROWS).asReversed()) Readout(measureText(m))
        }
        state.gate?.classes?.values?.sortedBy { it.tokenClass }?.forEach { c -> Readout(calibrationText(c)) }
    }
}

private fun primaryText(state: ProfileState): String {
    val primary = BackendPolicy.route(state.backend, state.policy).primary
    val who = if (primary == Backend.NEURAL) "the model" else "your own letters"
    val why = when (state.backend) {
        BackendChoice.AUTO -> if (state.policy.since >
            0L
        ) {
            "Auto chose this when it last measured"
        } else {
            "Auto, until the model measures closer"
        }

        BackendChoice.MY_LETTERS, BackendChoice.MODEL -> "your choice"
    }
    return "Writing with $who first ($why)."
}

/** §8.4's per-character routing, in the characters' own case; null when nothing is routed. */
private fun routingText(state: ProfileState): String? {
    val routed = state.policy.routedToAssembly
    if (routed.isEmpty()) return null
    return "From your letters whenever they can write the word: ${routed.sorted().joinToString(" ")}"
}

private fun measureText(m: QualityMeasure): String {
    val day = java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(java.util.Date(m.epochMillis))
    fun f(v: Float?) = v?.let { String.format(java.util.Locale.ROOT, "%.2f", it) } ?: "–"
    val firstPass = m.neuralFirstPass?.let { " · model reads first time ${(it * PERCENT).toInt()}%" }.orEmpty()
    return "$day · ${m.words} words · your letters ${f(
        m.fAssembly,
    )} · model ${f(m.fNeural)}$firstPass · over ${m.heldOut} kept back"
}

private fun calibrationText(c: ClassCalibration): String {
    val what = when (c.tokenClass) {
        TokenClass.WORD -> "Words"
        TokenClass.NUMBER -> "Numbers"
        TokenClass.FORMULA -> "Formulas"
        TokenClass.SYMBOL -> "Symbols"
    }
    val own = ((c.positive[c.rule] ?: 0f) * PERCENT).toInt()
    return if (c.judgeable) {
        "$what: read back before drawing — $own% of yours read right"
    } else {
        "$what: built only from letters you have written, never read back — the recogniser reads only $own% of yours right"
    }
}

@Composable
private fun SampleEntry(
    row: SampleRow,
    onCorrect: () -> Unit,
    onConfirm: () -> Unit,
    onRemove: () -> Unit,
    onIgnoreNote: () -> Unit,
) {
    GlassPanel(modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(InkDims.GroupGap),
        ) {
            WordDrawing(row, modifier = Modifier.width(WordWidth).height(WordHeight))
            Column(modifier = Modifier.weight(1f)) {
                // The user's own words: plain text, never uppercased by a chip.
                Text(text = row.label, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                MicroLabel(describe(row))
            }
            TextChip(label = "Correct", onClick = onCorrect)
            if (row.source == LabelSource.AGREED) TextChip(label = "Looks right", onClick = onConfirm)
            TextChip(label = "Remove", onClick = onRemove)
            TextChip(label = "Ignore note", onClick = onIgnoreNote)
        }
    }
}

private fun describe(row: SampleRow): String {
    val source = when (row.source) {
        LabelSource.AGREED -> "read twice, agreed"
        LabelSource.CONFIRMED -> "you confirmed it"
        LabelSource.CORRECTED -> "you corrected it"
    }
    val split = if (row.sample.split == SampleSplit.HELD_OUT) " · kept back to measure with" else ""
    val letters = if (row.sample.glyphs.isEmpty()) "" else " · ${row.sample.glyphs.size} letters cut"
    val readings = row.sample.alternatives
    val alternatives = if (readings.isEmpty()) "" else " · also read as ${readings.joinToString()}"
    return source + split + letters + alternatives
}

/**
 * The word as the normaliser sees it: slant removed, its baseline drawn, its x-height marked. Fitted
 * to the box at one scale for both axes, so a word is not stretched into a different hand.
 */
@Composable
private fun WordDrawing(row: SampleRow, modifier: Modifier) {
    val normal = remember(row.sample.id) { row.sample.frame.normalise(row.sample.trajectory) }
    Canvas(modifier = modifier) {
        val word: PenTrajectory = normal ?: return@Canvas
        var minU = Float.MAX_VALUE
        var maxU = -Float.MAX_VALUE
        for (u in word.x) {
            minU = min(minU, u)
            maxU = max(maxU, u)
        }
        val scale = min(size.height / VERTICAL_SPAN, size.width / max(maxU - minU, MIN_SPAN))
        val baseline = size.height * (ABOVE_BASELINE / VERTICAL_SPAN)
        val guide = 1.dp.toPx()
        drawLine(Ink.Hairline, Offset(0f, baseline), Offset(size.width, baseline), guide)
        drawLine(Ink.Hairline, Offset(0f, baseline - scale), Offset(size.width, baseline - scale), guide / 2)
        val pen = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        for (stroke in 0 until word.strokeCount) {
            val path = Path()
            for (i in word.strokeStart(stroke) until word.strokeEnd(stroke)) {
                val x = (word.x[i] - minU) * scale
                val y = baseline - word.y[i] * scale
                if (i == word.strokeStart(stroke)) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, color = Ink.TextStrong, style = pen)
        }
    }
}

@Composable
private fun CorrectDialog(row: SampleRow, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember(row.sample.id) { mutableStateOf(row.label) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("What does this say?") },
        text = { OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true) },
        confirmButton = { TextButton(onClick = { onSave(text) }, enabled = text.isNotBlank()) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Normalised units shown: one and a half above the baseline-to-x-height band, and one below. */
private const val ABOVE_BASELINE = 2.1f
private const val VERTICAL_SPAN = 3.2f
private const val MIN_SPAN = 0.5f
private const val LETTER_COLUMNS = 13

/** How many of the fidelity curve's latest points the screen lists. */
private const val HISTORY_ROWS = 6
private const val PERCENT = 100
private val WordWidth = 180.dp
private val WordHeight = 56.dp
