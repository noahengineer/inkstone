package dev.inkstone.core.sync

import dev.inkstone.core.model.DocumentId

/**
 * M12 — where a note's recordings live on the server (docs/M12-AUDIO.md §6).
 *
 * ```
 * Maths/Straight lines.pdf                                   ← unchanged; still an ordinary PDF
 * Maths/.inkstone-media/<documentId>/<recordingId>.opus
 * Maths/.inkstone-media/<documentId>/<recordingId>.timeline
 * Maths/.inkstone-media/<documentId>/<recordingId>.md        ← written by the server half
 * ```
 *
 * ## ⚠️ Keyed by `documentId`, never by filename
 *
 * The directory beside the note is a *convenience* — it keeps a lecture near the notebook it
 * belongs to when a human browses Nextcloud — and it is deliberately **not** the identity. A
 * transcript found by *sitting next to* `Straight lines.pdf` is orphaned the moment somebody
 * renames that file in the web UI, which is a thing people do; M6 spent a milestone learning
 * that path-based identity does not survive contact with a browser. So the `documentId`
 * segment is what says which note this is, and the note's own `remoteBinding` is what moves
 * the directory if the note moves.
 *
 * ## Why the file name is the recording id and not a timestamp
 *
 * Doc §6 sketches `2026-08-11T09-05.opus`, which reads better in a file listing. It is also
 * exactly the wrong key for this feature's headline case: **two recordings in one note**, which
 * at minute resolution collide, and at second resolution still collide for two devices — while
 * the whole design is that a recording is one device's, once. A UUIDv7 sorts by time anyway, so
 * the listing stays chronological, and the date a human wants is in the transcript's front
 * matter where §7 already puts it. Doc §6 has been corrected.
 */
public object RemoteMedia {

    /**
     * The per-note media directory, beside the note's own file.
     *
     * Dot-prefixed so a file manager keeps it out of the way, and because it is machinery
     * rather than something the user filed there. ⚠️ It is **not** hidden from the reconciler
     * by the dot — nothing in `:core:sync` has ever had a hidden-file rule — which is why
     * `RemoteWalk` skips it by name instead.
     */
    public const val MEDIA_DIR: String = ".inkstone-media"

    public const val AUDIO_EXTENSION: String = ".opus"

    public const val TIMELINE_EXTENSION: String = ".timeline"

    /**
     * @param noteRemotePath the note's own `remoteBinding.remotePath`, e.g. `Maths/Lines.pdf`.
     *   The media directory is its **sibling**, so a note in the account's root gets
     *   `.inkstone-media/<documentId>/` at the root too.
     */
    public fun directory(noteRemotePath: String, noteId: DocumentId): String {
        val parent = noteRemotePath.substringBeforeLast('/', "")
        val prefix = if (parent.isEmpty()) "" else "$parent/"
        return "$prefix$MEDIA_DIR/${noteId.value}"
    }

    public fun audio(noteRemotePath: String, noteId: DocumentId, recordingId: String): String =
        "${directory(noteRemotePath, noteId)}/$recordingId$AUDIO_EXTENSION"

    public fun timeline(noteRemotePath: String, noteId: DocumentId, recordingId: String): String =
        "${directory(noteRemotePath, noteId)}/$recordingId$TIMELINE_EXTENSION"
}
