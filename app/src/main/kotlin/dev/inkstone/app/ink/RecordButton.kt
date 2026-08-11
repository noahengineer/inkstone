package dev.inkstone.app.ink

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import dev.inkstone.app.R
import dev.inkstone.app.audio.RecordingUiState
import kotlinx.coroutines.delay

/**
 * ⭐ M12 §10 — the record button, and while it runs, **a visible elapsed timer**.
 *
 * > *"Unmistakable while running — a visible elapsed timer, not a tinted icon. The failure that
 * > hurts is recording for fifty minutes and finding out you were not; its mirror, believing you
 * > stopped when you had not, is the privacy version of the same bug."*
 *
 * So the running state is not a colour change: it is a red pill with a counting clock in it, and
 * it says `Stop recording` when you look at what pressing it does.
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
     * to. `POST_NOTIFICATIONS` rides with it because denying that hides the recording
     * notification while the recording continues, which is §12's privacy concern exactly — so both
     * are requested together and the recording starts either way, since Android will not let the
     * capture happen silently in the first place.
     */
    val permissions =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
            if (granted[Manifest.permission.RECORD_AUDIO] == true) onToggle()
        }

    if (state.isRecording) {
        RunningPill(state = state, onStop = onToggle, modifier = modifier)
    } else {
        FilledTonalButton(
            onClick = {
                if (hasMicrophone(context)) onToggle() else permissions.launch(required())
            },
            modifier = modifier.semantics { contentDescription = START_TAG },
        ) {
            Text(stringResource(R.string.recording_start))
        }
    }
}

@Composable
private fun RunningPill(state: RecordingUiState, onStop: () -> Unit, modifier: Modifier) {
    var elapsed by remember(state.recordingId) { mutableLongStateOf(0L) }
    LaunchedEffect(state.anchorElapsedRealtime) {
        while (true) {
            elapsed = SystemClock.elapsedRealtime() - state.anchorElapsedRealtime
            delay(TICK_MILLIS)
        }
    }

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(PILL_CORNER_PERCENT),
        color = MaterialTheme.colorScheme.errorContainer,
    ) {
        Row(modifier = Modifier.padding(horizontal = 4.dp)) {
            Button(
                onClick = onStop,
                colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
                modifier = Modifier.semantics { contentDescription = STOP_TAG },
            ) {
                Text(
                    text = formatElapsed(elapsed),
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
            if (state.silenced) {
                Spacer(Modifier.width(4.dp))
                // §9 — the microphone was taken by something else. Said out loud, because the
                // alternative is an hour of silence discovered afterwards.
                Text(
                    text = stringResource(R.string.recording_silenced),
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(top = 14.dp, end = 8.dp),
                )
            }
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
 * Content descriptions, and they are load-bearing rather than decoration: `uiautomator dump`
 * reads them, and driving the app over `adb` is CLAUDE.md's definition-of-done #6 check that
 * catches an inert feature when a green suite does not.
 */
internal const val START_TAG: String = "Start recording"
internal const val STOP_TAG: String = "Stop recording"

/** A full pill: 50% of the shorter side, which is what makes it read as a status and not a button. */
private const val PILL_CORNER_PERCENT = 50

private const val TICK_MILLIS = 500L
private const val MILLIS_PER_SECOND = 1_000L
private const val SECONDS_PER_MINUTE = 60L
private const val MINUTES_PER_HOUR = 60L
private const val SECONDS_PER_HOUR = 3_600L
