package dev.inkstone.app.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.inkstone.android.ink.EraseMode
import dev.inkstone.android.ink.InkInputModel
import dev.inkstone.app.ink.EditorPrefs
import dev.inkstone.app.ui.ChipDivider
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
import dev.inkstone.core.sync.SyncAccount

/**
 * Everything about the app that is a *preference* rather than an action on a note, in one place.
 *
 * *"right now everything is just random buttons everywhere and its a bit confusing"*. Before this
 * screen the library's toolbar carried a `NEXTCLOUD` chip and a `MEASURE` chip beside the things a
 * person does to their notes, the writing options (finger drawing, smoothing) were reachable only
 * from the editor's `⋯` diagnostics panel, and the handwriting-model download appeared only while a
 * search was being typed. Each one had a reason to be where it was; together they read as noise.
 *
 * ## What lives here and what deliberately does not
 *
 * Here: the account and its sync health, how the pen behaves, resetting the toolbars, the
 * recognition model, and the measurement tool (last, and labelled as for development).
 *
 * Not here: anything that acts on *one* note or folder — saving a note to Nextcloud, a folder's
 * auto-record — which stays on that item's own menu, because that is where a person is standing
 * when they want it. Nor the layout editor itself: rearranging a bar means touching the bar, so
 * this screen points at it rather than duplicating it.
 *
 * ## The writing options are [EditorPrefs], the same value the editor saves
 *
 * One source of truth. A change here goes through `EditorPreferences.changed`, and an editor
 * opened afterwards seeds from `EditorPreferences.current()` — and one already open re-adopts on
 * `ON_START` (`InkUiState.adopt`), so nothing here can be written back over by a stale editor.
 *
 * ⚠️ [SettingsUiState.prefs] is null until `EditorPreferences.warm` has read the store, and the
 * writing section waits for it rather than showing stock values that a press would then save over
 * the user's real ones — the same launch-snapshot trap `EditorPreferences` documents.
 */
@Composable
fun SettingsScreen(state: SettingsUiState, actions: SettingsActions, modifier: Modifier = Modifier) {
    var confirming by remember { mutableStateOf<PendingReset?>(null) }
    // The system Back gesture means the same as `‹ BACK`, as it does on the trash screen — without
    // this a swipe from the edge leaves the app from a screen the user thinks of as one level deep.
    BackHandler(onBack = actions.onBack)

    Box(modifier = modifier.fillMaxSize().background(Ink.Canvas)) {
        Box(modifier = Modifier.fillMaxSize().background(rememberDotGridBrush()))

        Column(
            modifier = Modifier.fillMaxSize().padding(InkDims.EdgePadding),
            verticalArrangement = Arrangement.spacedBy(InkDims.GroupGap),
        ) {
            GlassBar(modifier = Modifier.fillMaxWidth()) {
                TextChip(label = "‹ Back", onClick = actions.onBack)
                ChipDivider()
                MicroLabel("Settings", color = Ink.TextStrong)
            }

            // Centred and capped: a settings row stretched across a 1,280 dp landscape tablet puts its
            // switch a hand's width from the words it belongs to.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Column(
                    modifier = Modifier.widthIn(max = ContentMaxWidth).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(InkDims.GroupGap),
                ) {
                    NextcloudSection(state, actions)
                    WritingSection(state.prefs, actions.onPrefsChange)
                    ToolbarSection(enabled = state.prefs != null, onReset = { confirming = it })
                    state.model?.let { HandwritingSection(it, actions.onDownloadModel) }
                    actions.onOpenHandwriting?.let { MyHandwritingSection(it) }
                    LibrarySection(actions)
                    DeveloperSection(actions)
                    AboutSection(state.versionName)
                }
            }
        }
    }

    confirming?.let { reset ->
        val prefs = state.prefs
        AlertDialog(
            onDismissRequest = { confirming = null },
            title = { Text(reset.title) },
            text = { Text(reset.body) },
            confirmButton = {
                TextChip(
                    label = "Reset",
                    danger = true,
                    onClick = {
                        if (prefs != null) actions.onPrefsChange(reset.apply(prefs))
                        confirming = null
                    },
                )
            },
            dismissButton = { TextChip(label = "Cancel", onClick = { confirming = null }) },
        )
    }
}

/** What the screen draws. Assembled by `MainActivity` from the view models it already holds. */
data class SettingsUiState(
    val accounts: List<SyncAccount> = emptyList(),
    val outboxCount: Int = 0,
    val pendingRecordings: Int = 0,
    val attentionCount: Long = 0L,
    val syncMessage: String? = null,
    val syncBusy: Boolean = false,
    val prefs: EditorPrefs? = null,
    /** Null hides the handwriting-search section; see [ModelStatus]. */
    val model: ModelStatus? = null,
    val versionName: String = "",
)

/**
 * The handwriting-search model, as this screen shows it.
 *
 * Its own type rather than the library's, so the screen does not depend on how the library models a
 * download. A build whose *My handwriting* screen already follows the download from press to finish
 * passes **null** and gets no section here: two places to start one 20 MB download, only one of which
 * shows it progressing, is the kind of duplicate this screen exists to remove.
 */
data class ModelStatus(val downloaded: Boolean, val downloading: Boolean = false, val error: String? = null)

/** Everything the screen can do. A parameter object, the same shape as `LibraryActions`. */
data class SettingsActions(
    val onBack: () -> Unit,
    val onOpenNextcloud: () -> Unit,
    val onSyncNow: () -> Unit,
    val onOpenAttention: () -> Unit,
    val onPrefsChange: (EditorPrefs) -> Unit,
    val onDownloadModel: () -> Unit,
    val onOpenTrash: () -> Unit,
    val onFillForMeasurement: () -> Unit,
    /**
     * *My handwriting* — what has been learned from the user's writing, every label correctable and
     * everything deletable. Null in a build without handwriting synthesis, and then its section is
     * absent rather than a button that does nothing.
     */
    val onOpenHandwriting: (() -> Unit)? = null,
    /** The handwriting model's speed spike, written to logcat. Null where there is no model to time. */
    val onMeasureHandwritingModel: (() -> Unit)? = null,
)

private enum class PendingReset(val title: String, val body: String, val apply: (EditorPrefs) -> EditorPrefs) {
    LAYOUT(
        "Reset toolbar layout?",
        "Every bar goes back to where it started, at its original size, with every chip showing. " +
            "Your colours and pen sizes are kept.",
        SettingsModel::resetToolbarLayout,
    ),
    COLOURS(
        "Reset colours and sizes?",
        "The twelve saved colours, the pen sizes and each pen's colour go back to the defaults. " +
            "Where your toolbars are is kept. Nothing you have written changes.",
        SettingsModel::resetColoursAndSizes,
    ),
}

@Composable
private fun NextcloudSection(state: SettingsUiState, actions: SettingsActions) {
    Section("Nextcloud") {
        if (state.accounts.isEmpty()) {
            Body(
                "Not connected. Your notes are kept on this tablet; connect a Nextcloud to back them " +
                    "up and use them on other devices.",
            )
            Row(horizontalArrangement = Arrangement.spacedBy(InkDims.ChipGap)) {
                TextChip(label = "Connect to Nextcloud", selected = true, onClick = actions.onOpenNextcloud)
            }
            return@Section
        }

        for (account in state.accounts) {
            AccountLine(account)
        }
        if (state.outboxCount > 0 || state.pendingRecordings > 0) {
            Readout(
                buildList {
                    if (state.outboxCount > 0) add("${state.outboxCount} waiting to upload")
                    if (state.pendingRecordings > 0) add("${state.pendingRecordings} recording(s) waiting")
                }.joinToString(" · "),
                color = Ink.Focus,
            )
        }
        state.syncMessage?.let {
            Hint(it, color = if (state.syncBusy) Ink.Focus else Ink.TextNormal)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(InkDims.ChipGap)) {
            TextChip(label = "Accounts and folders", onClick = actions.onOpenNextcloud)
            TextChip(label = "Sync now", enabled = !state.syncBusy, onClick = actions.onSyncNow)
            // SPEC §7.7 — only when there is something in it, the same rule the library toolbar keeps.
            if (state.attentionCount > 0) {
                TextChip(label = "⚑ ${state.attentionCount} need attention", onClick = actions.onOpenAttention)
            }
        }
    }
}

/** Name, server, and one short state phrase. The full sentences are on the Nextcloud screen. */
@Composable
private fun AccountLine(account: SyncAccount) {
    Column {
        Text(
            account.displayName ?: account.loginName,
            style = MaterialTheme.typography.titleSmall,
            color = Ink.TextStrong,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Readout(account.serverUrl, color = Ink.TextMuted)
        Hint(
            SettingsModel.stateLabel(account),
            color = if (SettingsModel.needsAction(account)) Ink.Danger else Ink.TextNormal,
        )
    }
}

@Composable
private fun WritingSection(prefs: EditorPrefs?, onChange: (EditorPrefs) -> Unit) {
    Section("Writing") {
        if (prefs == null) {
            Hint("Loading…")
            return@Section
        }
        SettingRow(
            title = "Draw with your finger",
            hint = "Off: only the pen draws, and a finger scrolls and zooms. On: a finger draws too.",
        ) {
            OnOff(prefs.fingerDrawingEnabled) { onChange(prefs.copy(fingerDrawingEnabled = it)) }
        }
        SettingRow(
            title = "Stroke smoothing",
            hint = "Smooth evens out a shaky line as you write. Direct follows the pen exactly.",
        ) {
            Segmented {
                SegmentChip(
                    label = "Smooth",
                    selected = prefs.inputModel == InkInputModel.SPRING,
                    onClick = { onChange(prefs.copy(inputModel = InkInputModel.SPRING)) },
                )
                SegmentChip(
                    label = "Direct",
                    selected = prefs.inputModel == InkInputModel.DIRECT,
                    onClick = { onChange(prefs.copy(inputModel = InkInputModel.DIRECT)) },
                )
            }
        }
        SettingRow(
            title = "Eraser",
            hint = "Partial removes only what the eraser passes over. Whole stroke removes any line it touches.",
        ) {
            Segmented {
                SegmentChip(
                    label = "Partial",
                    selected = prefs.eraseMode == EraseMode.PARTIAL,
                    onClick = { onChange(prefs.copy(eraseMode = EraseMode.PARTIAL)) },
                )
                SegmentChip(
                    label = "Whole stroke",
                    selected = prefs.eraseMode == EraseMode.WHOLE_STROKE,
                    onClick = { onChange(prefs.copy(eraseMode = EraseMode.WHOLE_STROKE)) },
                )
            }
        }
        SettingRow(
            title = "Shape snapping",
            hint = "Draw a line, box or circle and hold the pen still to turn it into a clean shape.",
        ) {
            OnOff(prefs.shapeSnapEnabled) { onChange(prefs.copy(shapeSnapEnabled = it)) }
        }
        // ⚠️ Grid snap and arrowheads are deliberately not here. They are modes a person flips
        // mid-diagram from the editor's own chips, and a second switch for them on a screen nobody
        // is looking at while drawing would only be a second place to wonder why a line snapped.
    }
}

@Composable
private fun ToolbarSection(enabled: Boolean, onReset: (PendingReset) -> Unit) {
    Section("Toolbars") {
        Body(
            "To move, resize or hide anything on the note toolbars, open a note and long-press the " +
                "background of any bar — or press ⋯ and choose Customise layout.",
        )
        Row(horizontalArrangement = Arrangement.spacedBy(InkDims.ChipGap)) {
            TextChip(label = "Reset toolbar layout", enabled = enabled, onClick = { onReset(PendingReset.LAYOUT) })
            TextChip(label = "Reset colours and sizes", enabled = enabled, onClick = { onReset(PendingReset.COLOURS) })
        }
    }
}

@Composable
private fun HandwritingSection(model: ModelStatus, onDownload: () -> Unit) {
    Section("Handwriting search") {
        when {
            model.downloaded -> {
                Readout("English model installed ✓", color = Ink.TextNormal)
                Hint("Search finds words in your handwriting as well as in titles.")
            }

            model.downloading -> Readout("Downloading…", color = Ink.Focus)

            else -> {
                Body("Search covers titles and folders only until the handwriting model is downloaded.")
                Hint("English (~20 MB) · ML Kit sends anonymous usage metrics to Google")
                model.error?.let { Hint("Download failed: $it", color = Ink.Danger) }
                Row {
                    TextChip(label = "Download", selected = true, onClick = onDownload)
                }
            }
        }
    }
}

/** Moved here from the library toolbar's `HANDWRITING` chip, which sat beside *New folder*. */
@Composable
private fun MyHandwritingSection(onOpen: () -> Unit) {
    Section("My handwriting") {
        Body(
            "What Inkstone has learned from your writing, and the model handwriting search uses. " +
                "Check what it recognised, correct any label, or delete it.",
        )
        Row {
            TextChip(label = "Open my handwriting", onClick = onOpen)
        }
    }
}

@Composable
private fun LibrarySection(actions: SettingsActions) {
    Section("Trash") {
        Body("Deleted notes and folders are kept for 30 days before they are removed.")
        Row {
            TextChip(label = "Open trash", onClick = actions.onOpenTrash)
        }
    }
}

/**
 * ROADMAP M3's frame-rate gate needs 500 notes across 50 nested folders. Moved here from the
 * library toolbar's `MEASURE` chip, where it sat beside *New folder* and read as a feature.
 *
 * There is deliberately still no "remove the fixture": that would be a hard delete with a wildcard's
 * blast radius. The generated notes are ordinary notes — select them and move them to the trash.
 */
@Composable
private fun DeveloperSection(actions: SettingsActions) {
    Section("For development") {
        Hint(
            "Creates 500 test notes in 50 folders to measure how the library performs. " +
                "They are ordinary notes: select them and move them to the trash to remove them.",
        )
        Row(horizontalArrangement = Arrangement.spacedBy(InkDims.ChipGap)) {
            TextChip(label = "Fill library", onClick = actions.onFillForMeasurement)
            actions.onMeasureHandwritingModel?.let {
                TextChip(label = "Handwriting model speed (logcat)", onClick = it)
            }
        }
    }
}

@Composable
private fun AboutSection(versionName: String) {
    Section("About") {
        Readout("Inkstone $versionName", color = Ink.TextNormal)
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    GlassPanel(modifier = Modifier.fillMaxWidth()) {
        MicroLabel(title, color = Ink.TextStrong)
        content()
    }
}

/**
 * A setting: its name and one sentence on the left, its control on the right.
 *
 * ⚠️ The words get the weight and the control does not — a control squeezed by a long hint would
 * wrap its segments, and a hint squeezed by a control is merely a line longer.
 */
@Composable
private fun SettingRow(title: String, hint: String, control: @Composable () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(InkDims.GroupGap * 2),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = Ink.TextStrong)
            Hint(hint)
        }
        control()
    }
}

/**
 * Two segments rather than a Material `Switch`: the design system has no switch, and `Segmented`
 * is its answer to *"a choice between two things"* — the same reason the library's List/Grid uses it.
 */
@Composable
private fun OnOff(value: Boolean, onChange: (Boolean) -> Unit) {
    Segmented {
        SegmentChip(label = "Off", selected = !value, onClick = { onChange(false) })
        SegmentChip(label = "On", selected = value, onClick = { onChange(true) })
    }
}

@Composable
private fun Body(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = Ink.TextNormal)
}

@Composable
private fun Hint(text: String, color: Color = Ink.TextMuted) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = color)
}

private val ContentMaxWidth = 760.dp
