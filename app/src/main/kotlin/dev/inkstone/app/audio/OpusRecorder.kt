package dev.inkstone.app.audio

import android.content.Context
import android.media.AudioManager
import android.media.AudioRecordingConfiguration
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ⭐ M12 §9 — `MediaRecorder` writing **Ogg/Opus**, and the reasons for each setting.
 *
 * ## ⚠️ Ogg, never MP4 — this is the whole container decision
 *
 * `MediaRecorder` writing MPEG-4 finalises its index (`moov`) **on stop**. Kill the process at
 * minute 50 and the file is large, complete-looking, and unplayable by anything: the samples are
 * all there and nothing knows where they are. Ogg is a stream of self-describing pages, so a
 * truncated file is simply a shorter recording.
 *
 * This is the same failure class as the torn ZIP that CLAUDE.md rule 10 exists to prevent, and
 * audio is the most irreplaceable data this app will ever hold — **you cannot re-record a
 * lecture**. It is also what makes §9's orphan recovery possible at all: a row still marked
 * CAPTURING at the next launch points at a file that is already playable.
 *
 * ## The encoder settings
 *
 * - **48 kHz**, because that is Opus's native rate; anything else is a resample for nothing.
 * - **Mono.** A tablet on a desk in a lecture theatre has no stereo image worth twice the bytes.
 * - **24 kbit/s**, which is ~10.8 MB an hour — the figure doc §4 budgets against, and the reason
 *   audio is a sidecar rather than a bundle entry.
 * - **`VOICE_RECOGNITION`**, not `MIC`. `MIC` applies the device's own processing, tuned for a
 *   phone held at your face: automatic gain control pumps badly on a voice five metres away, and
 *   what pumping does to a transcript is exactly what this feature is judged on.
 *   `VOICE_RECOGNITION` is the source Android documents as tuned for speech recognition, which is
 *   literally the consumer here. ⚠️ Like §7's alignment window, it is a **tuning decision made
 *   without a real recording to check it against** — the first thing to measure once there is one.
 *
 * ## Losing the microphone
 *
 * `MediaRecorder` does not report being pre-empted; it keeps writing silence, which is the
 * failure §9 calls out as worse than stopping. `AudioManager.AudioRecordingCallback` does report
 * it — [AudioRecordingConfiguration.isClientSilenced] is precisely *"something else has priority
 * and your capture is being muted"* — so the gap comes from there rather than from the recorder.
 */
@Singleton
class OpusRecorder @Inject constructor(
    @ApplicationContext private val context: Context,
    private val io: CoroutineDispatcher,
) : AudioRecorder {

    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null
    private var silenced = false
    private var callback: AudioManager.AudioRecordingCallback? = null

    private val audioManager: AudioManager
        get() = context.getSystemService(AudioManager::class.java)

    @Suppress("TooGenericExceptionCaught")
    // `MediaRecorder` throws IllegalStateException, IOException and RuntimeException for the same
    // three real causes — no permission, no microphone, no disk — and which one arrives is
    // device-specific. Every one of them means "this lecture is not being recorded", and the user
    // has to be told that now rather than at minute fifty.
    override suspend fun start(path: String, listener: AudioRecorder.Listener): String? = withContext(io) {
        val file = File(path)
        file.parentFile?.mkdirs()
        try {
            val created = newRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                setOutputFormat(MediaRecorder.OutputFormat.OGG)
                setAudioEncoder(MediaRecorder.AudioEncoder.OPUS)
                setAudioSamplingRate(SAMPLE_RATE_HZ)
                setAudioChannels(1)
                setAudioEncodingBitRate(BITRATE_BPS)
                setOutputFile(file.absolutePath)
                setOnErrorListener { _, what, extra -> listener.onFailed("recorder error $what/$extra") }
                prepare()
                start()
            }
            recorder = created
            outputFile = file
            silenced = false
            watchForSilence(listener)
            null
        } catch (failure: Exception) {
            releaseQuietly()
            failure.message ?: "The microphone could not be opened."
        }
    }

    @Suppress("TooGenericExceptionCaught")
    // `stop()` throws if the recorder never captured a frame — a start-then-immediately-stop, or a
    // microphone that was refused. The Ogg on disk is still whatever was written, which is what the
    // caller wants to know about, so the size is read either way.
    override suspend fun stop(): Long = withContext(io) {
        callback?.let { audioManager.unregisterAudioRecordingCallback(it) }
        callback = null
        try {
            recorder?.stop()
        } catch (ignored: Exception) {
            // Nothing was captured. The file may still hold Ogg pages; `length()` below is the truth.
        }
        releaseQuietly()
        val size = outputFile?.length() ?: 0L
        outputFile = null
        size
    }

    /**
     * ⚠️ `MediaRecorder(Context)` is **API 31** and `minSdk` is 30.
     *
     * The context-taking constructor exists so the platform can attribute the capture to the app
     * for the microphone privacy indicator; on 30 the no-argument one is the only one there is, and
     * it is deprecated *from* 31 rather than broken on 30. Lint catches this and it is right to —
     * without the check the app would crash on the oldest device it claims to support, on the one
     * code path that only runs when somebody presses record.
     */
    @Suppress("DEPRECATION")
    private fun newRecorder(): MediaRecorder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context) else MediaRecorder()

    private fun releaseQuietly() {
        recorder?.release()
        recorder = null
    }

    /**
     * ⚠️ **The only reliable way to learn the microphone was taken.**
     *
     * The callback fires with *every* active recording configuration in the app, so the
     * interesting question is whether **ours** is among them and silenced. A configuration list
     * that no longer contains us at all is also a loss, and is what a full pre-emption looks like
     * on some devices — so absence counts as silence rather than as "nothing to report".
     */
    private fun watchForSilence(listener: AudioRecorder.Listener) {
        // ⚠️ `AudioManager` takes a `Handler`, not an `Executor` — so the callback lands on the
        // main looper. That is fine and deliberate: it does one comparison and hands off into the
        // controller's own scope, and the main looper is the one thread guaranteed to exist for
        // as long as the process does.
        val handler = Handler(Looper.getMainLooper())
        val watcher = object : AudioManager.AudioRecordingCallback() {
            override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>?) {
                val ours = configs.orEmpty().filter {
                    it.clientAudioSource ==
                        MediaRecorder.AudioSource.VOICE_RECOGNITION
                }
                val nowSilenced = ours.isEmpty() || ours.any { it.isClientSilenced }
                if (nowSilenced == silenced) return
                silenced = nowSilenced
                if (nowSilenced) listener.onSilenced() else listener.onResumed()
            }
        }
        callback = watcher
        audioManager.registerAudioRecordingCallback(watcher, handler)
    }

    private companion object {
        /** Opus's native rate. Anything else is a resample for no benefit. */
        const val SAMPLE_RATE_HZ = 48_000

        /** Voice-grade: ~10.8 MB an hour, which is the figure doc §4's whole argument rests on. */
        const val BITRATE_BPS = 24_000
    }
}
