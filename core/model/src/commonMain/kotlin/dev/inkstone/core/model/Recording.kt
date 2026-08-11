package dev.inkstone.core.model

/**
 * M12 — one lecture recording (docs/M12-AUDIO.md).
 *
 * The row that indexes the two sidecar files on disk. **Not** the audio, and not part of the
 * note: audio never enters the bundle (§4) and the timeline is not an op and not a bundle entry
 * (§3). A recording is written by one device in one sitting, never merged and never edited,
 * which is why none of this needs a Lamport clock or a merge rule.
 *
 * @property startedAtEpochMillis wall clock, **for display only** — *"your lecture from Tuesday
 *   morning"*. Ordering between devices is Lamport (CLAUDE.md) and no two devices' recordings
 *   are ever compared at all.
 * @property durationMillis elapsed-realtime, so a clock correction mid-lecture cannot change how
 *   long the lecture was. §10's thirty-second floor is applied to this.
 * @property uploadedAtEpochMillis 0 until the server has both files. Separate from [state]
 *   because *"the capture finished"* and *"the server has it"* are different facts, and a
 *   recording stays [RecordingState.READY] after it has been uploaded — the local audio is still
 *   on disk.
 */
public data class Recording(
    val id: String,
    val noteId: DocumentId,
    val state: RecordingState,
    val startedAtEpochMillis: Long,
    val durationMillis: Long = 0L,
    val audioBytes: Long = 0L,
    val uploadedAtEpochMillis: Long = 0L,
    val discardReason: String? = null,
) {
    /** What §11's pending list counts: finished, kept, and the server does not have it. */
    public val awaitingUpload: Boolean
        get() = state == RecordingState.READY && uploadedAtEpochMillis == 0L
}

public enum class RecordingState {

    /**
     * The microphone is open — **or the process died while it was**.
     *
     * ⚠️ The row is written in this state *before* the first byte of audio, which is what makes
     * §9's orphan recovery possible: a recording interrupted by process death is a row that still
     * says CAPTURING, and the Ogg stream on disk is playable up to its last complete page. That is
     * the second reason the container is Ogg and not MP4 — an MP4 finalises its index on stop, so
     * a kill at minute 50 leaves a large file nothing will play.
     */
    CAPTURING,

    /** Finished, over the thirty-second floor, and kept. The only state that uploads. */
    READY,

    /**
     * Under §10's floor, or the recorder produced nothing.
     *
     * The row survives — nothing in this table is ever deleted (CLAUDE.md rule 1 has no exception
     * for metadata) — and carries [Recording.discardReason] so a human can be told which of the
     * two it was.
     */
    DISCARDED,

    ;

    public companion object {
        /**
         * A state written by a newer build reads as [CAPTURING], not [READY].
         *
         * The conservative direction on purpose: CAPTURING is *"do not upload this, and offer it
         * for recovery"*, which for an unknown row is exactly right. Reading it as READY would
         * upload a file a newer build may still be writing to.
         */
        public fun parse(name: String): RecordingState = entries.firstOrNull { it.name == name } ?: CAPTURING
    }
}
