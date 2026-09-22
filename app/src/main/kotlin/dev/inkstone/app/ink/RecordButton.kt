package dev.inkstone.app.ink

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import dev.inkstone.app.R
import dev.inkstone.app.audio.RecordingUiState
import dev.inkstone.app.ui.GlassBar
import dev.inkstone.app.ui.Ink
import dev.inkstone.app.ui.InkDims
import dev.inkstone.app.ui.MicroLabel
import dev.inkstone.app.ui.Readout
import dev.inkstone.app.ui.TextChip
import kotlinx.coroutines.delay

/**
 * ⭐ M12 §10 / ROADMAP M10 — the design's *"Global Utilities (Top Right)"* zone.
 *
 * > *"A `RECORD` chip that transitions into a red pulse counter tracking elapsed time."*
 *
 * Which is exactly what M12 already required, in the redesign's clothes:
 *
 * > *"Unmistakable while running — a visible elapsed timer, not a tinted icon. The failure that
 * > hurts is recording for fifty minutes and finding out you were not; its mirror, believing you
 * > stopped when you had not, is the privacy version of the same bug."*
 *
 * ## The timer costs no state writes
 *
 * `RecordingUiState` carries the **anchor** — when the audio started, on the monotonic clock — and
 * this recomposes once a second from it. A controller that pushed a new state object every second
 * would wake the whole note screen 3,000 times in a lecture for a number that only this composable
 * draws.
 */
@Composable
fun RecordButton(state: RecordingUiState, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current

    /**
     * ⚠️ **The app's first runtime permission**, and it is asked for at the press rather than at
     * launch: an app that asks for a microphone before you have done anything is one people say no
     * to. `POST_NOTIFICATIONS` rides with it because denying that hides the recording notification
     * while the recording continues, which is §12's privacy concern exactly.
     *
     * ⚠️ **A refused microphone must say so.** This callback used to be `if (granted) onToggle()`
     * and nothing else, so a denial — or Android's "don't ask again", which returns denied instantly
     * without showing a dialog at all — left the button doing absolutely nothing, forever, in
     * silence. Once permanently denied no in-app request can re-prompt; only Settings can, so the
     * message says that rather than inviting another press.
     */
    val permissions =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
            if (granted[Manifest.permission.RECORD_AUDIO] == true) {
                onToggle()
            } else {
                Toast.makeText(context, R.string.recording_needs_permission, Toast.LENGTH_LONG).show()
            }
        }

    if (state.isRecording) {
        RunningPill(state = state, onStop = onToggle, modifier = modifier)
    } else {
        GlassBar(modifier = modifier, scrollable = false) {
            TextChip(
                label = stringResource(R.string.recording_start),
                onClick = { if (hasMicrophone(context)) onToggle() else permissions.launch(required()) },
                modifier = Modifier.semantics { contentDescription = START_TAG },
            )
        }
    }
}

/**
 * The running state: a pulsing dot, a counting clock, and a red edge.
 *
 * The pill is the design's `border border-red-500/30` over the same glass as every other widget —
 * the recording state is a *state*, not an error, so it borrows the chrome rather than replacing it.
 */
@Composable
private fun RunningPill(state: RecordingUiState, onStop: () -> Unit, modifier: Modifier) {
    var elapsed by remember(state.recordingId) { mutableLongStateOf(0L) }
    LaunchedEffect(state.anchorElapsedRealtime) {
        while (true) {
            elapsed = SystemClock.elapsedRealtime() - state.anchorElapsedRealtime
            delay(TICK_MILLIS)
        }
    }

    val pulse = rememberInfiniteTransition(label = "record")
    val dotAlpha by pulse.animateFloat(
        initialValue = 1f,
        targetValue = DIM_ALPHA,
        animationSpec = infiniteRepeatable(tween(PULSE_MILLIS), RepeatMode.Reverse),
        label = "dot",
    )

    Row(
        modifier = modifier
            .clip(InkDims.Pill)
            .background(Ink.Panel, InkDims.Pill)
            .border(1.dp, Ink.RecordingWash, InkDims.Pill)
            .clickable(onClick = onStop)
            .semantics { contentDescription = STOP_TAG }
            .padding(horizontal = PillPaddingH, vertical = PillPaddingV),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(InkDims.GroupGap),
    ) {
        Box(
            modifier = Modifier
                .size(DotSize)
                .alpha(dotAlpha)
                .background(Ink.Recording, CircleShape),
        )
        Readout(formatElapsed(elapsed), color = Ink.Recording)
        if (state.silenced) {
            // §9 — the microphone was taken by something else. Said out loud, because the
            // alternative is an hour of silence discovered afterwards.
            MicroLabel(stringResource(R.string.recording_silenced), color = Ink.Recording)
        }
    }
}

/** `h:mm:ss` past an hour, `mm:ss` below it — a lecture is usually the second. */
internal fun formatElapsed(millis: Long): String {
    val totalSeconds = (millis / MILLIS_PER_SECOND).coerceAtLeast(0)
    val seconds = totalSeconds % SECONDS_PER_MINUTE
    val minutes = (totalSeconds / SECONDS_PER_MINUTE) % MINUTES_PER_HOUR
    val hours = totalSeconds / SECONDS_PER_HOUR
    val mm = minutes.toString().padStart(2, '0')
    val ss = seconds.toString().padStart(2, '0')
    return if (hours > 0) "$hours:$mm:$ss" else "$mm:$ss"
}

private fun hasMicrophone(context: android.content.Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

private fun required(): Array<String> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
    arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
} else {
    arrayOf(Manifest.permission.RECORD_AUDIO)
}

/**
 * Content descriptions, and they are load-bearing rather than decoration: `uiautomator dump` reads
 * them, and driving the app over `adb` is CLAUDE.md's definition-of-done #6 check that catches an
 * inert feature when a green suite does not.
 */
internal const val START_TAG: String = "Start recording"
internal const val STOP_TAG: String = "Stop recording"

private val DotSize = 9.dp
private val PillPaddingH = 16.dp
private val PillPaddingV = 12.dp

private const val DIM_ALPHA = 0.25f
private const val PULSE_MILLIS = 900
private const val TICK_MILLIS = 500L
private const val MILLIS_PER_SECOND = 1_000L
private const val SECONDS_PER_MINUTE = 60L
private const val MINUTES_PER_HOUR = 60L
private const val SECONDS_PER_HOUR = 3_600L
