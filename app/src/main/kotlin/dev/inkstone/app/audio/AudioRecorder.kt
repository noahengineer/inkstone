package dev.inkstone.app.audio

/**
 * M12 §9 — the microphone, as the little of it `RecordingController` needs.
 *
 * An interface so the controller's *policy* — the thirty-second floor, the timeline buffer, when
 * a recording starts and stops, what a lost microphone does — is testable on the JVM. That
 * matters more here than usual: none of those decisions can be checked on a device without
 * actually giving a lecture, and CLAUDE.md's standing lesson is that a feature can be correct in
 * every test and inert in the app. Hoisting the decision into something a test can drive is the
 * countermeasure the same document recommends.
 *
 * Everything platform-shaped stays behind it: `MediaRecorder`, `AudioManager`, the file, and the
 * `audio/ogg` container.
 */
interface AudioRecorder {

    /**
     * Open the microphone and begin writing Ogg/Opus to [path].
     *
     * @param path an absolute filesystem path. A real one, not a [dev.inkstone.core.model.FileSystem]
     *   path: `MediaRecorder` writes through its own file descriptor and cannot be given an
     *   abstraction. The controller resolves it.
     * @return null on success, or why it failed — a message fit to show a human. Never throws:
     *   "the microphone is busy" is an ordinary outcome of a phone ringing, and this codebase
     *   uses result types across boundaries and exceptions only for programming errors.
     */
    suspend fun start(path: String, listener: Listener): String?

    /**
     * Stop and finalise. Safe to call when not recording.
     *
     * @return how many bytes were written, so the controller can apply §10's floor and record the
     *   size without a second stat call.
     */
    suspend fun stop(): Long

    /** What the microphone did without being asked. */
    interface Listener {

        /**
         * ⚠️ §9: *"Losing the microphone is not the same as stopping."*
         *
         * A phone call takes the microphone and the recorder happily keeps writing **silence** —
         * worse than stopping, because it is discovered an hour later, and worse again because
         * every stroke after it is then aligned to audio that is offset by the length of the call.
         */
        fun onSilenced()

        /** The microphone came back. The controller writes the gap between the two. */
        fun onResumed()

        /** The recorder died — the media server was restarted, or the file could not be written. */
        fun onFailed(reason: String)
    }
}
