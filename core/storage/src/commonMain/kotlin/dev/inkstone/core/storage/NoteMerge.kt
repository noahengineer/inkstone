package dev.inkstone.core.storage

import dev.inkstone.core.model.Document
import dev.inkstone.core.model.DocumentId
import dev.inkstone.core.model.FoldOutcome
import dev.inkstone.core.model.PageId
import kotlinx.serialization.json.JsonElement

/**
 * ⭐ **SPEC §7.7 layers 1 and 2 — merging a peer's copy of a note into this one.**
 *
 * ## What this is, and what it deliberately is not
 *
 * It is *"concatenate the two op logs and fold"*, and that is the whole of it. SPEC §4.3
 * calls the convergence mechanism **total-order state-machine replication** rather than a
 * CRDT for exactly this reason: sort the union of ops by Lamport clock, fold from empty, and
 * every device reaches the same state. So there is no merge *algorithm* here to get wrong —
 * the algorithm is [dev.inkstone.core.model.foldPage], written in M2 and unit-tested since.
 *
 * What is here is the **order of operations around** that fold, and every step of it is
 * load-bearing in a way that fails silently if it is wrong. That is why this is one function
 * with a long comment rather than four short ones: the sequence *is* the design.
 *
 * ## Three rules with no exceptions (ROADMAP M6)
 *
 * 1. **Never resolve a conflict by discarding bytes.** Nothing here deletes, truncates or
 *    replaces. A page only the peer has is added; a page only we have is kept; an op either
 *    side has is folded. The one thing that can happen to local data is that an LWW register
 *    loses — and losing a register is not losing bytes, because the losing op stays in the log
 *    forever and remains visible to a re-fold.
 * 2. **Never show a modal dialog.** This function returns a [MergeReport]. It has no idea
 *    what a user is.
 * 3. **Never block sync on user input.** It never waits for anything.
 *
 * ## Why the caller supplies the session
 *
 * Because the note may be **open in the editor** while this runs (SPEC §13.2 row 10), and two
 * `NoteSession`s over one note would each hold their own `pending` list and their own folded
 * pages — so whichever materialized second would write a bundle missing the other's work.
 * There must be exactly one session per note, and the caller is what knows whether the editor
 * already has it.
 */
public object NoteMerge {

    /**
     * ⭐ **Fold a peer's bundle *bytes* into [session] — the door every production merge uses.**
     *
     * [merge] takes an already-open [BundleReader], and a reader can never be a schema from the
     * future: `BundleReader.open` refuses one by throwing (SPEC §5.8) and `BundleReader`'s
     * constructor is private, so no reader that [merge]'s own schema check could see has ever
     * existed. Both `:app` callers therefore wrapped the open in
     * `runCatching { … }.getOrNull()` and collapsed *every* refusal into "this file is not this
     * note" — which left [MergeReport.TooNew] unreachable and reported a peer running a newer
     * build as an unmergeable conflict rather than as *"update Inkstone and this note opens"*.
     *
     * The decision belongs here because this is the only place holding the bytes. The two are
     * genuinely different files: the extractor's `TOO_NEW` verdict comes off the **XMP marker**
     * and never gets as far as writing a bundle, so the case that reaches here is a file whose
     * marker and whose manifest disagree — a peer's build that bumped one and not the other, or a
     * third party's edit. Either way layer 3 keeps both copies; what changes is what the user is
     * told.
     *
     * @return never throws, for [merge]'s reason.
     */
    public suspend fun mergeBundle(session: NoteSession, source: ByteSource): MergeReport {
        val remote = runCatching { BundleReader.open(source) }.getOrNull() ?: return refusal(session, source)
        return merge(session, remote)
    }

    /**
     * Why a bundle would not open — asked only after one already has refused.
     *
     * [BundleReader.schemaVersionOf] re-reads the central directory and the manifest, which is why
     * this is behind the failure rather than in front of the open: paying it on every merge would
     * double a read on the path that works, and this path has by definition already failed.
     */
    private suspend fun refusal(session: NoteSession, source: ByteSource): MergeReport {
        val schema = BundleReader.schemaVersionOf(source)
        return if (schema != null && schema > Document.SCHEMA_VERSION) {
            MergeReport.TooNew(schema, Document.SCHEMA_VERSION)
        } else {
            MergeReport.DifferentDocument(session.document.id)
        }
    }

    /**
     * Fold [remote] into [session].
     *
     * @param session the **one** live session for this note. Must not be shared with another
     *   merge running concurrently — see the object KDoc.
     * @param remote a reader over the peer's `note.inkstone`, already extracted from their
     *   published PDF.
     * @return what happened, for the reconciler to act on. Never throws for a merge that
     *   cannot proceed; that is a [MergeReport] variant, because a background worker turning
     *   an unreadable peer file into an exception is how a conflict becomes a crash report.
     */
    public suspend fun merge(session: NoteSession, remote: BundleReader): MergeReport {
        // ⚠️ There is deliberately **no** schema check here. A newer bundle schema is layer 3's
        // (SPEC §5.8 forbids re-saving a file from the future; SPEC §5.6 adds the reason —
        // recovering it would rewrite a newer format's document in this build's vocabulary, under
        // the original documentId, and it would then win the next sync and take the newer data
        // with it). But `BundleReader.open` has already refused such a file by throwing, and its
        // constructor is private, so a check here is a branch no input can reach. It lived here
        // through all of M6 looking like the guard. [mergeBundle] is where it actually fires.
        if (remote.document.id != session.document.id) {
            // Two different notes. The reconciler matches on oc:id and the marker's
            // documentId, so reaching here means one of those disagreed with the bundle
            // inside the file — which is a conflicted copy, never a merge.
            return MergeReport.DifferentDocument(remote.document.id)
        }

        // ⚠️ STEP 1, AND IT MUST BE FIRST. `NoteSession.merge` will happily accept ops for a
        // page the document has never heard of, and journal them. `BundleWriter` then iterates
        // `document.pages` and skips any page not in it, and `materialize` resets the journal
        // immediately afterwards — so a remote page's ops journaled without its `PageRef`
        // present are **destroyed, permanently, with no error**. The output is a valid PDF
        // with the right page count and a page missing.
        //
        // Merging the document first means every page the peer has is in `document.pages`
        // before a single op is applied to it (SPEC §4.3 — "any page not present in a merged
        // order is appended at the end, never dropped").
        // Read *before* the merge. SPEC §7.7 layer 2 requires the override be "recorded in
        // history", and the string worth recording is the one the user typed and lost — which
        // `mergeDocument` has already replaced by the time it returns.
        val localTitleBefore = session.document.title
        val titleOverridden = session.mergeDocument(remote.document)

        var outcome = FoldOutcome.APPENDED
        var remoteNovel = 0
        val pagesTouched = LinkedHashSet<PageId>()

        // Tombstones included. A page the peer deleted still has ops we may not have, and a
        // page we deleted may be one the peer is still writing to — deletion is a flag, and
        // the log underneath it stays whole (CLAUDE.md rule 1).
        for (ref in session.document.pages) {
            // `session.merge` does the opId subtraction itself, so a pull that re-delivers this
            // device's own ops — which is most of any pull — costs nothing. It is repeated here
            // only so that a page with nothing novel does not reach `flush` at all.
            val novel = remote.page(ref.id)?.ops.orEmpty().let { ops ->
                if (ops.isEmpty()) return@let emptyList()
                val known = session.opIdsOn(ref.id)
                ops.filterNot { it.opId in known }
            }
            if (novel.isEmpty()) continue

            remoteNovel += novel.size
            val pageOutcome = session.merge(ref.id, novel)
            if (pageOutcome.ordinal > outcome.ordinal) outcome = pageOutcome
            pagesTouched += ref.id

            // ⚠️ Flushed per page, not once at the end. `NoteSession.pending` is an unbounded
            // list holding every applied op *including its geometry*, and `Journal.record`
            // builds one buffer containing the whole flush's JSON plus its whole geometry blob
            // before it appends a single byte. A first pull of a 200-page notebook is the
            // ordinary case, not the pathological one, and this runs inside a WorkManager job
            // on a device SPEC §10 sizes at 4 GB.
            session.flush()
        }

        return MergeReport.Merged(
            remoteNovelOps = remoteNovel,
            pagesTouched = pagesTouched.toList(),
            outcome = outcome,
            titleOverridden = titleOverridden,
            overriddenTitle = localTitleBefore.takeIf { titleOverridden },
            // Everything in the peer's bundle this build does not produce — recognised text
            // (M7), assets, anything a newer version added. Handed back rather than written
            // here because it is `materialize`'s input, and materializing is the caller's
            // decision: the reconciler does it once, after the merge, so one bundle rewrite
            // covers every page (SPEC §5.1 — never on the ink path).
            //
            // ⚠️ `NoteStore.materialize` unions these with the local reader's **local-wins**, which
            // is symmetric — so an entry name *both* devices produce keeps a different value on each
            // and never converges. Latent today (nothing writes a deterministic name yet) and the
            // normal case the moment M7 writes SPEC §5.5's `text/<pageId>.json`. See that method.
            carried = remote.carried(),
            carriedManifest = remote.carriedManifest,
            peerDeviceId = remote.document.deviceId.takeIf { it.isNotEmpty() },
            peerHighestCounter = remote.highestClockCounter,
        )
    }
}

/**
 * What a merge did — the reconciler's whole basis for deciding what happens next.
 *
 * Sealed rather than a nullable-field data class because the four outcomes lead to genuinely
 * different places: one continues to a materialize and possibly an upload, two go to SPEC
 * §7.7 layer 3 (a conflicted copy), and one is a bug in the caller.
 */
public sealed interface MergeReport {

    /**
     * The union was taken.
     *
     * @property remoteNovelOps how many of the peer's ops this device had not already seen.
     *   **Zero is the common case and is not a failure** — it is what a poll finds when the
     *   remote ETag moved because *this* device uploaded. The reconciler uses it to decide
     *   whether an upload is owed, which is what stops two devices uploading each other's
     *   merges forever.
     * @property outcome [FoldOutcome.REFOLDED] means at least one page had to be re-derived
     *   because a peer's op sorted before state already applied (CLAUDE.md rule 9);
     *   [FoldOutcome.SNAPSHOT_STALE] means a compaction snapshot was not a valid base and has
     *   been discarded (SPEC §4.7). Both are normal, and both are worth logging — a note that
     *   re-folds on every poll is a note whose peer's clock is behind.
     * @property overriddenTitle the title this device had, when the peer's rename won. SPEC
     *   §7.7 layer 2 requires the override be *"recorded in history"* — this is the string to
     *   record, and dropping it is the one place layer 2 could quietly discard something a
     *   user typed.
     * @property carried bundle entries from the peer that this build cannot interpret. **Must
     *   be passed to `materialize`**, or the first rewrite after the merge deletes a peer's
     *   recognised text and anything a newer build added — invisibly, because by definition
     *   they are the entries this build cannot render.
     * @property peerHighestCounter the highest Lamport counter the peer's bundle records. With
     *   [peerDeviceId] this is what SPEC §4.7's causal-stability horizon has been missing:
     *   `oldestAckedCounter` is a `min` over peers, and until M6 nothing knew what a peer had
     *   seen so it was always null.
     */
    public data class Merged(
        val remoteNovelOps: Int,
        val pagesTouched: List<PageId>,
        val outcome: FoldOutcome,
        val titleOverridden: Boolean,
        val overriddenTitle: String?,
        val carried: Map<String, ByteArray>,
        val carriedManifest: Map<String, JsonElement>,
        val peerDeviceId: String?,
        val peerHighestCounter: Long,
    ) : MergeReport {

        /** True when the peer had something this device did not. Drives the thumbnail refresh. */
        public val changedAnything: Boolean get() = remoteNovelOps > 0 || titleOverridden
    }

    /**
     * The peer's bundle is a schema this build does not understand. SPEC §5.6 / §5.8.
     *
     * Not an error and not a merge — layer 3. The local note is left **exactly** as it was,
     * and the remote bytes are kept as a conflicted copy so that upgrading the app later
     * recovers them.
     *
     * Reached from [NoteMerge.mergeBundle] and nowhere else. The ordinary route to a `TOO_NEW`
     * verdict is the **XMP marker**, read by `PulledPdfExtractor` before the bundle is even
     * extracted; this is the narrower case where the marker said one version and the manifest
     * inside says another. Only the message to the user differs — both go to layer 3, and neither
     * advances the binding's ETag.
     */
    public data class TooNew(val remoteVersion: Int, val understoodVersion: Int) : MergeReport

    /** The bundle inside the file is a different note. A conflicted copy, never a merge. */
    public data class DifferentDocument(val remoteId: DocumentId) : MergeReport
}
