package dev.inkstone.core.sync

/**
 * SPEC §7.4 rule 1 and §7.6 step 2 — the prune-by-directory-ETag walk, as a pure function.
 *
 * ## Why this is pure
 *
 * The same reason [OutboxCoalescing] is. The walk's cleverness is entirely in *what it decides
 * not to request*, and the only way to be sure that is right is to be able to state it as a
 * table and test the table — with no server, no fixture and no clock. Everything here takes one
 * `Depth:1` result plus the cache and returns a decision; [Reconciler] does the I/O.
 *
 * ## The three rules
 *
 * 1. **A change anywhere under a folder changes that folder's ETag, up to the root.** So a
 *    child collection whose ETag matches the cache cannot contain anything new, and the whole
 *    subtree is skipped. That is what turns a poll of a 500-note library into one request.
 * 2. **A weak ETag never prunes.** A false "unchanged" loses a pull silently and the note simply
 *    stops syncing; a false "changed" costs one request. Take the expensive direction — the same
 *    asymmetry [ETag.asIfMatch] encodes for the upload side.
 * 3. **Never walk from the DAV root with descent enabled.** The user's Nextcloud contains their
 *    photo library, and a `Depth:1`-per-changed-child walk from `/` is a crawl of it. Roots are
 *    the directories notes are actually bound into ([rootsOf]); the root itself is visited for
 *    its *files* only, because a note can legitimately be bound at the top level.
 */
public object RemoteWalk {

    /**
     * Whether a cached ETag proves nothing has changed.
     *
     * Both must be present, equal, **and strong**. Rule 2 lives here, in one function, used by
     * both the child-collection decision and [Reconciler]'s root short-circuit — because a weak
     * root ETag ending the pass before it starts is the same bug one level up, and it would be
     * spelled twice if this were inlined.
     */
    public fun unchanged(cached: ETag?, seen: ETag?): Boolean =
        cached != null && seen != null && !cached.weak && !seen.weak && cached.value == seen.value

    /**
     * Where a walk starts: the directories this account's notes actually live in.
     *
     * **Not the DAV root** (rule 3), and not the whole tree. Three sources, because each answers
     * a case the others miss:
     *
     * - the **parent of every bound note**, which is the only thing that can see a note the user
     *   bound into a folder nobody pinned;
     * - the **pinned folders**, which is the only thing that can see a note a *peer* created in a
     *   folder this device has no note in yet;
     * - the **account's default folder**, so a fresh install with nothing bound still looks
     *   somewhere.
     *
     * Descendants of another root are dropped: walking `Notes` and `Notes/Physics` separately
     * would list `Notes/Physics` twice and, worse, would let the second walk stamp it as seen
     * after the first had pruned it.
     *
     * ⚠️ **Nothing here can see a note moved *out* of the set**, and that is not a gap this function
     * can close — widening the roots is rule 3's crawl of the user's photo library. It means SPEC
     * §7.4 rule 2's move detection, which needs the new href in hand, cannot fire for such a move;
     * `Reconciler.resolveAbsent` asks the server for the file's current path by fileid instead,
     * before it is allowed to conclude a deletion. A note whose binding is repathed that way adds
     * its new parent here on the very next pass, which is what closes the loop.
     *
     * @return roots deepest-last, with `""` — the DAV root — present only if something is
     *   genuinely bound at the top level. [plan] must be called with `allowDescend = false` for
     *   that one.
     */
    public fun rootsOf(
        defaultFolder: String,
        bindingPaths: Collection<String>,
        pinnedPaths: Collection<String>,
    ): List<String> {
        val candidates = LinkedHashSet<String>()
        candidates += defaultFolder.trim('/')
        for (path in bindingPaths) candidates += path.trim('/').substringBeforeLast('/', "")
        for (path in pinnedPaths) candidates += path.trim('/')

        // `""` is the DAV root, and it is a root only when a note is genuinely bound directly in
        // it — otherwise the empty parent of nothing would put the user's whole drive on the list.
        val boundAtTopLevel = bindingPaths.any { !it.trim('/').contains('/') }
        val roots = candidates.filter { it.isNotEmpty() || boundAtTopLevel }

        // Deduplicate by containment, but ⚠️ never *by* `""`. It is an ancestor of everything, so
        // letting it absorb the others would leave one root that may not be descended into
        // (rule 3) and no root that may — which is a walk that reads nothing and reports success.
        return roots.filterNot { candidate ->
            roots.any { other -> other.isNotEmpty() && other != candidate && isUnder(candidate, other) }
        }.sorted()
    }

    /**
     * The one directory whose `Depth:0` ETag covers every root (SPEC §7.6 step 1).
     *
     * This is what makes the common case *one tiny request*: rule 1 propagates a change up to the
     * root, so if this ETag has not moved, nothing under any root has. `""` is a legitimate
     * answer and costs nothing — `Depth:0` on a collection returns that collection and no
     * children, so asking about the drive root does not read the drive.
     */
    public fun commonAncestor(roots: Collection<String>): String {
        val cleaned = roots.map { it.trim('/') }
        // ⚠️ An empty root **is** the DAV root, and the only directory containing it is itself.
        // Filtering it out and answering with the deepest sibling — which is what dropping empties
        // before this check did — probes a folder whose ETag says nothing about a note bound at the
        // top level, so that note would never be pulled again and nothing would report it.
        if (cleaned.any { it.isEmpty() }) return ""
        if (cleaned.isEmpty()) return ""
        var prefix = cleaned.first().split('/')
        for (root in cleaned.drop(1)) {
            val segments = root.split('/')
            val shared = prefix.zip(segments).takeWhile { (a, b) -> a == b }.map { it.first }
            prefix = shared
            if (prefix.isEmpty()) break
        }
        return prefix.joinToString("/")
    }

    /**
     * One directory listed, decided.
     *
     * @param path the directory that was listed. Compared against [DavResource.path], which
     *   `WebDavXml` has already stripped of its trailing slash and percent-decoded.
     * @param cached the account's whole directory cache, keyed by path.
     * @param allowDescend false for the synthetic DAV-root walk (rule 3). Files are still
     *   visited; child collections are neither descended nor pruned, because "not descended
     *   because we are not allowed to" must not be recorded as "nothing changed in there".
     * @param force true after `nc:data-fingerprint` moved — an admin restored a backup, so every
     *   cached ETag now describes a file that went *backwards* and none of them may prune.
     */
    public fun plan(
        accountId: String,
        path: String,
        listing: List<DavResource>,
        cached: Map<String, RemoteDirectory>,
        nowEpochMillis: Long,
        allowDescend: Boolean = true,
        force: Boolean = false,
    ): WalkStep {
        val here = path.trim('/')
        val self = listing.firstOrNull { it.path == here }
        val descend = ArrayList<String>()
        val pruned = ArrayList<String>()
        val files = ArrayList<DavResource>()

        for (child in listing) {
            if (child.path == here) continue
            when {
                !child.isCollection -> if (isNote(child)) files += child

                // ⭐ M12 — the note's own media directory, skipped by **name** (see [isMediaDir]).
                isMediaDir(child) -> Unit

                !allowDescend -> Unit

                !force && unchanged(cached[child.path]?.etag, child.etag) -> pruned += child.path

                else -> descend += child.path
            }
        }

        return WalkStep(
            // Stamped with `now` because this directory *was* walked — the listing is in hand.
            // A pruned child gets no row at all, which is what keeps `seenAt` meaning "walked"
            // and keeps a pruned subtree from looking like a mass deletion.
            self = RemoteDirectory(
                accountId = accountId,
                path = here,
                etag = self?.etag,
                ocId = self?.ocId,
                seenAtEpochMillis = nowEpochMillis,
            ),
            descend = descend,
            pruned = pruned,
            files = files,
        )
    }

    /**
     * Whether a remote file is a candidate for being one of our notes.
     *
     * Extension only, and on purpose: SPEC §5.2 publishes notes as ordinary PDFs, so the only
     * cheap filter available from a PROPFIND is the name. Whether it is *ours* is decided by the
     * XMP marker inside the file (SPEC §5.6) and cannot be known without downloading it — which
     * is why "unknown `oc:id`, name ends `.pdf`" is a *probe*, not an adoption.
     */
    public fun isNote(resource: DavResource): Boolean =
        !resource.isCollection && resource.name.endsWith(PDF_EXTENSION, ignoreCase = true)

    /**
     * ⭐ M12 — `.inkstone-media/`, which the walk skips entirely.
     *
     * ## The files inside were already harmless. The **directory** was not.
     *
     * [isNote] filters on `.pdf`, so a `.opus`, a `.timeline` or the server's `.md` transcript is
     * invisible to the classifier that writes `foreignRemote` rows: they cannot be adopted as
     * notes and cannot be read as deletions. Doc §6 expected them to land in `foreignRemote` and
     * they never reach it — which is a *better* answer, and the doc has been corrected.
     *
     * The collection is a different matter, and nothing in `:core:sync` has ever had a
     * hidden-file rule — the leading dot buys nothing here. Left alone, the walk descends into
     * `.inkstone-media/` and then into a `<documentId>/` under it, which costs:
     *
     * - **two extra `PROPFIND Depth:1` per note that has recordings, on every unpruned walk.**
     *   Directory listings do not consume the pass's budget (`Reconciler.DEFAULT_LIMIT` counts
     *   files acted on), so nothing bounds them.
     * ⚠️ This KDoc used to claim the skip also protects `probeRoot`'s one-request poll. **It does
     * not.** ETag propagation to the account root is the *server's* doing and happens when the file
     * is written, whether or not this client ever descends — so a recording upload changes the root
     * ETag and forces a walk regardless. What the skip saves is the extra requests *inside* a walk
     * that was going to happen anyway, which is worth having and is not the same claim. Corrected
     * rather than deleted, because a comment that overstates a mechanism is how the next person
     * builds on something that is not there.
     *
     * Skipping it also makes *"not mistaken for a note"* a **guarantee** rather than a
     * consequence of the extension filter, which is what ROADMAP M12 asks for.
     *
     * ⚠️ Skipped, and therefore deliberately **not** pruned either: a pruned child is recorded as
     * *"nothing changed in there"*, and this one is never looked at at all. The distinction is the
     * same one [plan]'s `allowDescend` documents, and getting it wrong writes a cache entry that
     * would later make a subtree invisible.
     */
    public fun isMediaDir(resource: DavResource): Boolean =
        resource.isCollection && resource.name.equals(RemoteMedia.MEDIA_DIR, ignoreCase = true)

    /** True when [candidate] is strictly inside [ancestor]. Callers exclude an empty ancestor. */
    private fun isUnder(candidate: String, ancestor: String): Boolean = candidate.startsWith("$ancestor/")

    private const val PDF_EXTENSION = ".pdf"
}

/**
 * What to do with one listed directory.
 *
 * @property self the cache row for the directory that was just listed. ⚠️ **Written only at the
 *   end of a pass, and only when nothing under it was left unfinished** — see
 *   [DirectoryCache.upsertAll]. A row claiming an ETag whose contents were not fully reconciled
 *   prunes the subtree on every later pass, silently, until something else in it changes.
 * @property descend child collections whose ETag moved, or that have never been seen.
 * @property pruned child collections proven unchanged by rule 1. Counted, and otherwise ignored:
 *   they get no cache write and no `seenAt` stamp.
 * @property files the `.pdf` children, in listing order. Non-PDFs are dropped without comment —
 *   the user's own files living beside their notes are not the reconciler's business.
 */
public data class WalkStep(
    val self: RemoteDirectory,
    val descend: List<String>,
    val pruned: List<String>,
    val files: List<DavResource>,
)
