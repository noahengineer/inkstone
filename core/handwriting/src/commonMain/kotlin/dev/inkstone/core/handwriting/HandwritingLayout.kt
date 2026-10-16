package dev.inkstone.core.handwriting

import dev.inkstone.core.model.DocumentId
import kotlin.jvm.JvmInline

/**
 * Whose handwriting a profile is — docs/HANDWRITING-SYNTHESIS.md D10.
 *
 * One per Nextcloud account, because the request ties a style profile to *"the owning user's
 * account"*; [LOCAL] before any account has signed in. A value class over the directory name so a
 * path can never be built from an account id that was not first made one of these.
 */
@JvmInline
public value class ProfileKey(public val value: String) {
    public companion object {
        /** The profile of a device nobody has signed in on yet. */
        public val LOCAL: ProfileKey = ProfileKey("local")

        /** An account id is a UUIDv7 (`IdFactory.newDeviceId`), so it is already a safe file name. */
        public fun forAccount(accountId: String): ProfileKey = ProfileKey("account-$accountId")
    }
}

/**
 * Where handwriting synthesis keeps its files — docs/HANDWRITING-SYNTHESIS.md §4.2.
 *
 * ```
 * handwriting/
 * ├── ledger/<documentId>.ledger    per INSTALL — which strokes this device's pen drew (PenLedger)
 * └── <profile>/                    per ACCOUNT (ProfileKey)
 *     ├── profile.json              settings and harvest progress (ProfileState)
 *     ├── labels.json               the user's corrections (LabelBook) — the one file not re-derivable
 *     ├── words/<n>.hws             harvested word samples (WordSampleCodec)
 *     └── trash/                    anything displaced or deleted, purged after 30 days
 * ```
 *
 * ⚠️ **Beside `notes/`, never inside the top-level `trash/`.** `NoteTrash` reads every name in that
 * directory as a `DocumentId`, so a file of ours there would be shown — and purged — as a note.
 */
public object HandwritingLayout {
    public const val ROOT: String = "handwriting"
    public const val LEDGER_DIR: String = "$ROOT/ledger"

    public fun ledger(noteId: DocumentId): String = "$LEDGER_DIR/${noteId.value}.ledger"

    public fun profileDir(profile: ProfileKey): String = "$ROOT/${profile.value}"

    public fun state(profile: ProfileKey): String = "${profileDir(profile)}/profile.json"

    public fun labels(profile: ProfileKey): String = "${profileDir(profile)}/labels.json"

    public fun wordsDir(profile: ProfileKey): String = "${profileDir(profile)}/words"

    /**
     * §8.1's artefacts, each naming the base model it was fitted to: `style.bin` (P2), `adapters.bin`
     * (P3), and `checkpoint.bin` while a fit is under way. Derived and re-fittable, but a displaced one
     * still goes to the trash first, like everything else in a profile.
     */
    public fun styleDir(profile: ProfileKey): String = "${profileDir(profile)}/style"

    public fun styleVector(profile: ProfileKey): String = "${styleDir(profile)}/style.bin"

    public fun adapters(profile: ProfileKey): String = "${styleDir(profile)}/adapters.bin"

    public fun checkpoint(profile: ProfileKey): String = "${styleDir(profile)}/checkpoint.bin"

    /** The name of a profile's trash directory, inside the profile. */
    public const val TRASH_DIR_NAME: String = "trash"

    public fun trashDir(profile: ProfileKey): String = "${profileDir(profile)}/$TRASH_DIR_NAME"

    /** Thirty days, as for notes (SPEC §9) — anything displaced is recoverable for as long as a note is. */
    public const val TRASH_RETENTION_MILLIS: Long = 30L * 24 * 60 * 60 * 1000
}
