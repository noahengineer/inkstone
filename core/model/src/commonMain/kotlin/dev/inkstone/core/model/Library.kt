package dev.inkstone.core.model

/**
 * SPEC §9 — the library: folders, favourites, sort, trash.
 *
 * ## Why this is a model type and not just a database row
 *
 * SPEC §9: *"folders mirror Nextcloud both directions. An app folder maps to a remote
 * folder; a move in either place is reflected in the other."* That makes a folder a
 * **synced entity**, not a UI convenience — it has an identity a peer has to agree with,
 * a name that has to be legal on a WebDAV server, and a parent that a remote `MOVE` can
 * change under it. So the type lives here, with [FolderTree] and [RemoteName] beside it,
 * and `:core:database` persists it rather than defining it.
 *
 * @property parentId null for a folder at the library root.
 * @property sortKey a [FractionalKey], for the same reason page order uses one (SPEC
 *   §4.3): two devices that each create a folder offline must both keep theirs. An
 *   integer position would let the later write erase the earlier one.
 * @property colorArgb null means "no colour" and the UI picks a neutral. Not a default
 *   colour constant, because a folder the user has deliberately left plain and one they
 *   coloured grey are different states and only one of them should follow a theme change.
 * @property deletedEpochMillis non-null means trashed. **A tombstone, not a removal**
 *   (CLAUDE.md rule 1) — the row stays, and [dev.inkstone.core.model.FolderIcon] and
 *   name stay with it, so restore is a field write.
 * @property deletedWithFolderId set on every descendant when an ancestor folder is
 *   trashed. It is what makes "restore that folder" put its whole subtree back with one
 *   action, and what keeps the trash list showing *one* entry for a deleted folder of
 *   fifty notes rather than fifty-one entries.
 */
public data class Folder(
    val id: FolderId,
    val parentId: FolderId?,
    val name: String,
    val sortKey: FractionalKey = FractionalKey.Initial,
    val colorArgb: Int? = null,
    val icon: FolderIcon = FolderIcon.FOLDER,
    val cover: FolderCover = FolderCover.Icon,
    val createdEpochMillis: Long = 0L,
    val modifiedEpochMillis: Long = 0L,
    val deletedEpochMillis: Long? = null,
    val deletedWithFolderId: FolderId? = null,
) {
    public val isTrashed: Boolean get() = deletedEpochMillis != null
}

/**
 * What fills a folder's tile in the library.
 *
 * [icon] and [colorArgb] are always set and are what a folder falls back to, so removing a
 * picture never leaves a blank tile. This says what is drawn *instead of* the icon, when
 * anything is.
 *
 * The two interesting variants both reuse machinery that already exists rather than adding
 * a second way to store a picture:
 *
 * - [LatestNote] draws the first page of the most recently edited note inside the folder.
 *   That is a thumbnail the library has already rendered (SPEC §5.7), so it costs nothing
 *   and it stays current on its own.
 * - [Drawn] points at an ordinary single-page note that the user draws in the ordinary
 *   editor. Its cover thumbnail is regenerated on materialize like any other note's, so a
 *   redrawn folder cover updates with no extra code at all. The note is marked
 *   `isCover` so it does not also appear in the library as a note.
 */
public sealed interface FolderCover {

    /** The default: [Folder.icon] on [Folder.colorArgb]. */
    public data object Icon : FolderCover

    /** The first page of the most recently edited note in this folder or below it. */
    public data object LatestNote : FolderCover

    /** A note the user drew for the purpose. See the interface KDoc. */
    public data class Drawn(val noteId: DocumentId) : FolderCover

    /** An imported image, stored under `folders/<folderId>/`. */
    public data class Image(val asset: String) : FolderCover
}

/**
 * A closed set of folder icons.
 *
 * An enum and not an icon name, for the reason [PageTemplate] gives: a string reference
 * implies a registry of user-supplied assets, which would have to sync, and a folder
 * whose icon file is on the other tablet is a blank square with no explanation.
 */
public enum class FolderIcon {
    FOLDER,
    STAR,
    BOOK,
    NOTE,
    FLASK,
    CALCULATOR,
    GLOBE,
    HEART,
    BRIEFCASE,
    TAG,
}

/**
 * A note as the library shows it — everything the grid needs **without opening the note**.
 *
 * That is the point of the whole index. SPEC §5.7: rendering a 500-note library must not
 * mean opening 500 PDFs, and the same argument applies to titles and page counts. If the
 * grid had to read a bundle per row to know what to draw, the 60 fps target would be gone
 * before thumbnails were even considered.
 *
 * @property thumbnailRev the [localRev] the cached thumbnail was rendered from, or null
 *   when there is none. Equality with [localRev] is the whole freshness test — see
 *   `ThumbnailCache`. A timestamp would be a guess; a revision is an answer.
 */
public data class NoteSummary(
    val id: DocumentId,
    val title: String,
    val folderId: FolderId? = null,
    val favourite: Boolean = false,
    /**
     * True for a note that exists only to be a folder's [FolderCover.Drawn] picture.
     *
     * It is an ordinary note in every other way — same editor, same journal, same thumbnail
     * pipeline — and this flag keeps it out of the library's own lists so a folder cover
     * does not also show up as a note the user has to explain to themselves.
     */
    val isCover: Boolean = false,
    val pageCount: Int = 1,
    val syncState: NoteSyncState = NoteSyncState.LOCAL_ONLY,
    val localRev: Long = 0L,
    val thumbnailRev: Long? = null,
    val createdEpochMillis: Long = 0L,
    val modifiedEpochMillis: Long = 0L,
    val deletedEpochMillis: Long? = null,
    val deletedWithFolderId: FolderId? = null,
) {
    public val isTrashed: Boolean get() = deletedEpochMillis != null

    /** True when the cached thumbnail is for this exact revision (SPEC §5.7). */
    public val hasFreshThumbnail: Boolean get() = thumbnailRev != null && thumbnailRev == localRev
}

/** ROADMAP M3 — *"sort by name/modified/created"*. */
public enum class LibrarySort {
    NAME,
    MODIFIED,
    CREATED,
}

/** ROADMAP M3 — *"library: grid/list"*. */
public enum class LibraryLayout {
    GRID,
    LIST,
}

/**
 * SPEC §9's folder tree, as pure functions over a flat list.
 *
 * Flat and not nested: the database holds `(id, parentId)` rows, a remote PROPFIND walk
 * (SPEC §7.4) yields paths one level at a time, and a nested type would have to be rebuilt
 * from scratch on every change. The tree is derived where it is needed and never stored.
 *
 * Every function here tolerates a **broken parent link** — a folder whose parent is
 * missing, trashed, or (through a bad remote move) an ancestor of itself. That is not
 * defensive programming for its own sake: SPEC §9 has folders arriving from a server this
 * app does not control, where all three are reachable states. A cycle that hangs the
 * library is a worse outcome than a folder that shows up at the root.
 */
public object FolderTree {

    /**
     * The chain from the root down to [folderId], inclusive. Empty for the library root.
     *
     * Stops at a missing parent and at a cycle, so the result is always finite and the
     * folder itself is always last when it exists.
     */
    public fun pathOf(folderId: FolderId?, folders: Map<FolderId, Folder>): List<Folder> {
        val chain = ArrayList<Folder>()
        val seen = HashSet<FolderId>()
        var current = folderId
        while (current != null && seen.add(current)) {
            val folder = folders[current] ?: break
            chain += folder
            current = folder.parentId
        }
        chain.reverse()
        return chain
    }

    /** Direct children of [parentId], in `(sortKey, name, id)` order. Trashed folders excluded. */
    public fun childrenOf(parentId: FolderId?, folders: Collection<Folder>): List<Folder> = folders
        .filter { !it.isTrashed && it.parentId == parentId }
        .sortedWith(compareBy({ it.sortKey.value }, { it.name.lowercase() }, { it.id.value }))

    /**
     * [folderId] and everything beneath it, breadth-first.
     *
     * Used by trash (one action has to reach the whole subtree) and by move (a folder may
     * not be moved into its own descendant, which is the check [isDescendantOf] makes).
     * Includes trashed folders, because restoring a subtree has to find them.
     */
    public fun subtreeOf(folderId: FolderId, folders: Collection<Folder>): List<Folder> {
        val byParent = folders.groupBy { it.parentId }
        val start = folders.firstOrNull { it.id == folderId } ?: return emptyList()
        val out = ArrayList<Folder>()
        val seen = HashSet<FolderId>()
        val queue = ArrayDeque<Folder>()
        queue += start
        while (queue.isNotEmpty()) {
            val folder = queue.removeFirst()
            if (!seen.add(folder.id)) continue
            out += folder
            byParent[folder.id]?.let { queue += it }
        }
        return out
    }

    /**
     * True when [candidate] is [ancestor] or sits beneath it.
     *
     * The guard on moving a folder. Dropping a folder into its own child would detach the
     * whole subtree from the root — the rows would still be there, and nothing in the UI
     * could ever reach them again.
     */
    public fun isDescendantOf(candidate: FolderId, ancestor: FolderId, folders: Map<FolderId, Folder>): Boolean {
        if (candidate == ancestor) return true
        val seen = HashSet<FolderId>()
        var current = folders[candidate]?.parentId
        while (current != null && seen.add(current)) {
            if (current == ancestor) return true
            current = folders[current]?.parentId
        }
        return false
    }

    /**
     * The remote path a folder maps to, under [root] (SPEC §9, M5's half of the mirror).
     *
     * Built from [RemoteName.sanitise]d segments, so a folder the user named
     * `Physics / Term 2` becomes one legal remote segment rather than two accidental
     * levels of hierarchy. Returns [root] itself for the library root.
     *
     * This is here in M3 rather than M5 because the *names* are chosen now: a folder
     * created today has to be nameable on a server the app has not been introduced to
     * yet, and discovering otherwise after a hundred folders exist would mean renaming
     * the user's library for them.
     */
    public fun remotePathOf(folderId: FolderId?, folders: Map<FolderId, Folder>, root: String): String {
        val segments = pathOf(folderId, folders).map { RemoteName.sanitise(it.name) }
        val base = root.trimEnd('/')
        return if (segments.isEmpty()) base else join(base, segments)
    }

    /**
     * ⭐ **Where a note in [folderId] belongs on the server, honouring pinned folders (M6).**
     *
     * SPEC §9 asks for folders that *"mirror Nextcloud both directions"*, and ROADMAP M6 for
     * folder mirroring. [remotePathOf] does the easy half — every folder under one root, one
     * sanitised segment per level. This does the half users actually ask for: **pin one
     * library folder to any path on the server, and everything beneath it follows.**
     *
     * Pin `School` to `Documents/Uni`, and a note in `School / Physics / Term 1` lands at
     * `Documents/Uni/Physics/Term 1/<note>.pdf`. Nothing has to be pinned twice, and adding a
     * subfolder needs no configuration at all.
     *
     * ## The rules, and why each is the way it is
     *
     * 1. **The deepest pinned ancestor wins.** Pin `School` → `Documents/Uni` *and*
     *    `School/Physics` → `Archive/Physics`, and a note in `School/Physics/Term 1` goes to
     *    `Archive/Physics/Term 1`. Most-specific-wins is the only rule that makes pinning a
     *    subfolder mean anything; "shallowest wins" would make the second pin silently dead.
     * 2. **Only the folders *below* the pin contribute segments.** The pinned folder's own
     *    name does not appear — the user chose a path for it, and appending its library name
     *    on top would give `Documents/Uni/School`, which is not what they asked for and is
     *    impossible to correct from the UI.
     * 3. **Nothing pinned anywhere → [accountRoot]**, i.e. exactly [remotePathOf]'s behaviour.
     *    So this is a generalisation, not a replacement, and an unpinned library keeps
     *    syncing precisely where it did before.
     * 4. **Every derived segment is sanitised, the pinned path is not.** A folder named
     *    `Physics / Term 2` must become one legal segment rather than two accidental levels
     *    (SPEC §7.8) — whereas the pinned path is a *path*, chosen against a real folder
     *    listing, and its slashes are meant.
     * 5. **A broken parent chain degrades to [accountRoot].** [pathOf] stops at a missing
     *    parent or a cycle, so a folder whose ancestry does not reach the root cannot see a
     *    pin that sits above the break. Falling back puts the note somewhere real and
     *    reachable rather than at a path assembled from half an ancestry.
     *
     * @param boundFolders library folder → the remote path it is pinned to. An empty string
     *   is legitimate and means the account's DAV root.
     * @param accountRoot where notes go when no ancestor is pinned — the account's default
     *   folder.
     */
    public fun remoteFolderFor(
        folderId: FolderId?,
        folders: Map<FolderId, Folder>,
        boundFolders: Map<FolderId, String>,
        accountRoot: String,
    ): String {
        val chain = pathOf(folderId, folders)
        // Rule 1 — searched from the deepest end, so the first hit is the most specific pin.
        val pinnedAt = chain.indexOfLast { boundFolders.containsKey(it.id) }
        if (pinnedAt < 0) return remotePathOf(folderId, folders, accountRoot)

        // Rule 2 — the pinned folder contributes its *path*, not its name, so segments start
        // after it.
        val base = boundFolders.getValue(chain[pinnedAt].id).trim('/')
        val below = chain.drop(pinnedAt + 1).map { RemoteName.sanitise(it.name) }
        return if (below.isEmpty()) base else join(base, below)
    }

    /**
     * `base/a/b`, with an empty base yielding `a/b` rather than `/a/b`.
     *
     * A leading slash would make the path absolute against the DAV endpoint instead of
     * relative to the account's files root, which resolves to a different place and 404s in a
     * way that reads as "the folder is missing".
     */
    private fun join(base: String, segments: List<String>): String {
        val tail = segments.joinToString("/")
        return if (base.isEmpty()) tail else "$base/$tail"
    }

    /**
     * ⭐ **What has to happen for *"always sync this folder"* to be true.**
     *
     * ## Why this is a function and not four lines inside the repository
     *
     * Because it was four lines inside the repository, and they were **missing**. Pinning a
     * folder recorded the mapping and did nothing else — so the user pinned a folder holding a
     * note, watched their Nextcloud, and saw no folder and no upload. A pin is only *consulted*
     * when a note is bound, and nothing bound anything.
     *
     * Every unit test passed, because the resolver was right; the feature was inert, because
     * nothing called it. `LibraryRepository` cannot be reached from a JVM test (its `PdfGateway`
     * needs `androidx.ink`'s native code), so an omission there is invisible until someone looks
     * at a real server. Hoisting the *decision* out makes it a pure function over plain data —
     * which means "a note in a pinned folder produces a step" is now something a test can say.
     *
     * The lesson worth keeping: *"always sync this folder"* is a statement about the **notes**,
     * not about a mapping. Recording the mapping is the easy half and accomplishes nothing.
     *
     * @param root the folder being pinned. Its whole subtree is covered.
     * @param boundNotes which notes already have a remote binding — they need a **move**, not a
     *   bind, and conflating the two either re-creates a file or leaves it at the old path.
     * @param pins every pin on the account, [root]'s included, so a subfolder with its own pin
     *   still wins (see [remoteFolderFor]).
     * @return one step per note in the subtree, in index order. Empty when the folder is empty,
     *   which is a legitimate answer and not a failure — new notes are covered as they arrive.
     */
    public fun planFolderSync(
        root: FolderId,
        folders: Collection<Folder>,
        notes: List<NoteSummary>,
        boundNotes: Set<DocumentId>,
        pins: Map<FolderId, String>,
        accountRoot: String,
    ): List<FolderSyncStep> {
        val byId = folders.associateBy { it.id }
        // The whole subtree, not just direct children: pinning "School" has to cover
        // "School/Physics/Term 1" or the nesting the user was promised does not happen.
        val covered = subtreeOf(root, folders).mapTo(HashSet()) { it.id }
        return notes
            .filter { !it.isTrashed && it.folderId != null && it.folderId in covered }
            .map { note ->
                FolderSyncStep(
                    noteId = note.id,
                    title = note.title,
                    remoteFolder = remoteFolderFor(note.folderId, byId, pins, accountRoot),
                    alreadyBound = note.id in boundNotes,
                )
            }
    }
}

/**
 * One note that a folder pin has to act on.
 *
 * @property alreadyBound true when the note already has a binding, so this is a **move** to the
 *   new path; false when it has never been uploaded, so it is a bind — which is what enqueues
 *   the `MKCOL` that creates the remote folder in the first place.
 */
public data class FolderSyncStep(
    val noteId: DocumentId,
    val title: String,
    val remoteFolder: String,
    val alreadyBound: Boolean,
)

/**
 * SPEC §7.8 — filename sanitisation, the static half.
 *
 * > Nextcloud forbids only `\` and `/` by default — *not* the Windows character set. […]
 * > **Then be stricter anyway** — assume Windows rules so notes sync cleanly to the
 * > user's desktop.
 *
 * That is what this does, and it is deliberately the *strict* set from the start. M5 adds
 * the runtime read of `capabilities.files.forbidden_*`, which can only ever forbid more —
 * so a name legal here is legal on a default install, and M5's job is to catch the
 * instance that has been configured to be fussier, not to relax anything.
 *
 * The order is SPEC §7.8's, and each step exists because the previous one can create the
 * problem the next one solves: replacing a forbidden character can leave a trailing dot,
 * truncating can leave a trailing space, and truncating to bytes can split a UTF-8
 * sequence.
 */
public object RemoteName {

    /** SPEC §7.8 — bytes, not characters. */
    public const val MAX_BYTES: Int = 250

    /** What a name becomes when nothing legal is left of it. */
    public const val FALLBACK: String = "Untitled"

    private const val REPLACEMENT = '_'

    /**
     * Nextcloud's own two, plus the Windows set SPEC §7.8 says to assume anyway.
     *
     * `:` is on this list and it is the one that catches people out — it is legal on a
     * default Nextcloud and illegal on the Windows desktop the same folder syncs to.
     */
    private const val FORBIDDEN = "\\/<>:\"|?*"

    /** SPEC §7.8 — "reserved basenames `CON`/`AUX`/`NUL`". The full DOS set. */
    private val RESERVED_BASENAMES = setOf(
        "con", "prn", "aux", "nul",
        "com1", "com2", "com3", "com4", "com5", "com6", "com7", "com8", "com9",
        "lpt1", "lpt2", "lpt3", "lpt4", "lpt5", "lpt6", "lpt7", "lpt8", "lpt9",
    )

    /** SPEC §7.8 — Nextcloud rejects these outright. */
    private val FORBIDDEN_NAMES = setOf(".htaccess")

    /** SPEC §7.8 — a name ending in one of these is refused as a partial upload. */
    private val FORBIDDEN_EXTENSIONS = listOf(".part", ".filepart")

    /**
     * A legal remote path segment for [name].
     *
     * Never empty, never longer than [MAX_BYTES] bytes, and stable — sanitising an
     * already-sanitised name returns it unchanged, which matters because a remote path is
     * recomputed on every sync and a sanitiser that drifted would rename the folder each
     * time.
     */
    public fun sanitise(name: String): String {
        val cleaned = buildString(name.length) {
            for (character in name) {
                when {
                    // SPEC §7.8 — "control chars [0-31]". Stripped, not replaced: a name
                    // pasted with a stray newline should not gain an underscore for it.
                    character.code < FIRST_PRINTABLE || character.code == DELETE -> Unit

                    character in FORBIDDEN -> append(REPLACEMENT)

                    else -> append(character)
                }
            }
        }

        var result = cleaned.trim()
        result = stripForbiddenExtensions(result)
        result = suffixIfReserved(result)
        result = truncateToBytes(result, MAX_BYTES)
        // Truncation and stripping can both leave a trailing dot or space, which Windows
        // silently drops from a filename — so two folders would collide on the desktop
        // while looking distinct here.
        result = result.trimEnd(' ', '.')
        return result.ifEmpty { FALLBACK }
    }

    /** True when [name] survives [sanitise] unchanged. */
    public fun isLegal(name: String): Boolean = name == sanitise(name)

    /**
     * ⭐ SPEC §7.7 layer 3 — **the name of a conflicted copy.** Added in M6.
     *
     * `Lecture Notes` at 2026-08-05 14:30:12 UTC becomes
     * `Lecture Notes (conflicted copy 2026-08-05 143012)`. The caller appends `.pdf`.
     *
     * This exists because layer 3 keeps *both* versions (CLAUDE.md rule 1 — never resolve a
     * conflict by discarding bytes), and two versions need two names. Four properties make it
     * a correct name rather than merely a plausible one, and three of them are bugs if missed.
     *
     * 1. **No `:` anywhere in the time.** [sanitise] rewrites a colon to `_`, so `14:30:12`
     *    would reach the server as `14_30_12` — legal, but the string in the path is then no
     *    longer the string the formatter produced, and any device that spelled the time even
     *    slightly differently lands on a *different* path for the same instant. Two devices
     *    that disagree on the path each create their own copy, and neither is obviously the
     *    other's twin. `HHmmss` has no separator to mangle.
     * 2. **UTC, never local time.** Two tablets in different timezones must agree on the name
     *    for the same conflict, and this is structurally true rather than merely intended:
     *    there is no timezone API in common Kotlin to get wrong.
     * 3. **Hand-rolled from a `Long`.** `:core:model` has zero dependencies by design — there
     *    is no `kotlinx-datetime` in the version catalogue and `java.time` is JVM-only, so
     *    this is the same constraint that produced the hand-written SHA-256 and MD5. A wall
     *    clock is legitimate here precisely because the result is a display string in a
     *    filename and never an ordering; ordering is Lamport (CLAUDE.md).
     * 4. **The title gives way to [MAX_BYTES], never the stamp.** Sanitising the whole
     *    concatenation would truncate the suffix off a 250-byte title — and a copy whose name
     *    equals the original's name is not a copy, it is the original, overwritten.
     *
     * ⚠️ **Called exactly once per conflict, from `Reconciler.layerThree`, with that pass's own
     * `passStartedAt`.** The returned string is *both* the local note's title and the remote path
     * the verbatim bytes are PUT to, so a second call for the same conflict is a second reading of
     * the clock and two names for one thing. `NoteStore.conflictedCopyOf` used to make that second
     * call itself; it now takes the finished name. Property 1's failure and this one are the same
     * failure arrived at from opposite ends.
     */
    public fun conflictedCopy(title: String, epochMillisUtc: Long): String {
        val suffix = " (conflicted copy ${utcStamp(epochMillisUtc)})"
        // Property 4. `suffix` is pure ASCII, so its byte length is its length.
        val room = maxOf(1, MAX_BYTES - suffix.length)
        val stem = truncateToBytes(sanitise(title), room).trimEnd(' ', '.').ifEmpty { FALLBACK }
        // Sanitised once more so that `isLegal(conflictedCopy(…))` holds by construction
        // rather than by inspection of the parts. [sanitise] is idempotent, so this cannot
        // change a stem that was already legal — it only closes the door on the suffix ever
        // gaining a forbidden character.
        return sanitise(stem + suffix)
    }

    /**
     * `yyyy-MM-dd HHmmss`, UTC, from epoch milliseconds, with no library and no allocation
     * beyond the string.
     *
     * See [conflictedCopy] property 3 for why this is hand-written at all.
     */
    private fun utcStamp(epochMillis: Long): String {
        // Floor division, not Kotlin's truncating division. `-1 / 86_400_000` is 0, which
        // would put 1969-12-31T23:59:59.999Z on 1970-01-01 and give it a *negative*
        // millisecond-of-day. Every pre-1970 instant is wrong without these three lines.
        var days = epochMillis / MILLIS_PER_DAY
        var millisOfDay = epochMillis - days * MILLIS_PER_DAY
        if (millisOfDay < 0) {
            days -= 1
            millisOfDay += MILLIS_PER_DAY
        }
        return "${formatCivilDate(days)} ${formatTimeOfDay(millisOfDay)}"
    }

    /**
     * Days since 1970-01-01 → `yyyy-MM-dd`, proleptic Gregorian.
     *
     * Howard Hinnant's `civil_from_days`: shift the calendar so a year starts on **1 March**,
     * and the leap day becomes the *last* day of the year instead of an interruption in the
     * middle of it. That buys two things at once — month lengths collapse to one linear
     * formula (153 days per five months, exactly, forever) so there is no lookup table and no
     * special case for February, and the 400-year era arithmetic needs no separate handling
     * for the century rule. January and February are then months 11 and 12 of the *previous*
     * shifted year, which is the single `+1` at the end.
     *
     * Verified in `ConflictedCopyTest` against dates chosen to hit each of the ways this can
     * go wrong: a leap day, a century leap year (2000), a century *non*-leap year (2100), a
     * year boundary from both sides, and an instant before the epoch.
     */
    private fun formatCivilDate(daysSinceEpoch: Long): String {
        val shifted = daysSinceEpoch + DAYS_MARCH_0000_TO_EPOCH
        // Floor division again, for the same reason as in [utcStamp]: an era is 400 years and
        // a wildly wrong device clock can land before 0000-03-01.
        val era = (if (shifted >= 0) shifted else shifted - LAST_DAY_OF_ERA) / DAYS_PER_ERA
        val dayOfEra = shifted - era * DAYS_PER_ERA
        val yearOfEra = (
            dayOfEra - dayOfEra / DAYS_PER_LEAP_CYCLE +
                dayOfEra / DAYS_PER_CENTURY -
                dayOfEra / LAST_DAY_OF_ERA
            ) / DAYS_PER_COMMON_YEAR
        val dayOfYear = dayOfEra -
            (DAYS_PER_COMMON_YEAR * yearOfEra + yearOfEra / YEARS_PER_LEAP_CYCLE - yearOfEra / YEARS_PER_CENTURY)
        val monthIndex = (MONTHS_PER_153_DAYS * dayOfYear + 2) / DAYS_PER_FIVE_MONTHS
        val day = dayOfYear - (DAYS_PER_FIVE_MONTHS * monthIndex + 2) / MONTHS_PER_153_DAYS + 1
        val month = if (monthIndex < MONTHS_MARCH_TO_DECEMBER) {
            monthIndex + MARCH_IS_MONTH_3
        } else {
            monthIndex - JANUARY_WRAPS_BY
        }
        // The shifted year began in March, so January and February belong to the next one.
        val year = yearOfEra + era * YEARS_PER_ERA + if (month <= 2) 1 else 0
        return "${pad(year, YEAR_DIGITS)}-${pad(month, TWO_DIGITS)}-${pad(day, TWO_DIGITS)}"
    }

    /**
     * Milliseconds since midnight UTC → `HHmmss`.
     *
     * No separator, deliberately — see [conflictedCopy] property 1. A `:` here is the bug
     * that puts two devices on two different remote paths for one conflict.
     */
    private fun formatTimeOfDay(millisOfDay: Long): String {
        val hours = millisOfDay / MILLIS_PER_HOUR
        val minutes = millisOfDay % MILLIS_PER_HOUR / MILLIS_PER_MINUTE
        val seconds = millisOfDay % MILLIS_PER_MINUTE / MILLIS_PER_SECOND
        return "${pad(hours, TWO_DIGITS)}${pad(minutes, TWO_DIGITS)}${pad(seconds, TWO_DIGITS)}"
    }

    /**
     * Zero-padded to [digits].
     *
     * A device whose wall clock is set before the year 1000 produces a short or
     * minus-sign-bearing year field. That is still a legal filename and still the *same*
     * filename on both devices, which is the whole of what this has to guarantee.
     */
    private fun pad(value: Long, digits: Int): String = value.toString().padStart(digits, '0')

    private fun stripForbiddenExtensions(name: String): String {
        var result = name
        var changed = true
        while (changed) {
            changed = false
            for (extension in FORBIDDEN_EXTENSIONS) {
                if (result.length > extension.length && result.endsWith(extension, ignoreCase = true)) {
                    result = result.dropLast(extension.length)
                    changed = true
                }
            }
        }
        return result
    }

    private fun suffixIfReserved(name: String): String {
        val stem = name.substringBefore('.').lowercase()
        val isReserved = name.lowercase() in FORBIDDEN_NAMES ||
            stem in RESERVED_BASENAMES ||
            name == "." ||
            name == ".."
        return if (isReserved) name + REPLACEMENT else name
    }

    /**
     * Truncate to [limit] UTF-8 bytes without splitting a character.
     *
     * Encoding once and cutting the array would be shorter and would produce a name with a
     * replacement character in it on a device whose locale made the last character
     * multi-byte — which is most of them, for most non-English names.
     *
     * The loop steps by **code point**, not by `Char`. Stepping by `Char` and charging each
     * surrogate two bytes gives the right total for a pair and the wrong answer at the
     * boundary: with two bytes of room left, the high surrogate fits and the low one does
     * not, and the result ends in half an emoji — a string that is not valid UTF-16, let
     * alone a legal filename. Caught by a test, which is the only way this gets caught.
     */
    private fun truncateToBytes(name: String, limit: Int): String {
        var bytes = 0
        var index = 0
        while (index < name.length) {
            val character = name[index]
            val isPair = character.isHighSurrogate() &&
                index + 1 < name.length &&
                name[index + 1].isLowSurrogate()
            val width = if (isPair) SURROGATE_PAIR_BYTES else utf8Width(character)
            if (bytes + width > limit) return name.take(index)
            bytes += width
            index += if (isPair) 2 else 1
        }
        return name
    }

    private fun utf8Width(character: Char): Int = when {
        character.code < ONE_BYTE_MAX -> ONE_BYTE

        character.code < TWO_BYTE_MAX -> TWO_BYTES

        // A *lone* surrogate — pairs are handled above. It is not encodable, so what it
        // costs depends on the platform's replacement. Three is the widest that can be, and
        // over-counting only truncates a character early.
        else -> THREE_BYTES
    }

    private const val FIRST_PRINTABLE = 32
    private const val DELETE = 127
    private const val ONE_BYTE_MAX = 0x80
    private const val TWO_BYTE_MAX = 0x800
    private const val ONE_BYTE = 1
    private const val TWO_BYTES = 2
    private const val THREE_BYTES = 3
    private const val SURROGATE_PAIR_BYTES = 4

    private const val MILLIS_PER_DAY = 86_400_000L
    private const val MILLIS_PER_HOUR = 3_600_000L
    private const val MILLIS_PER_MINUTE = 60_000L
    private const val MILLIS_PER_SECOND = 1_000L
    private const val TWO_DIGITS = 2
    private const val YEAR_DIGITS = 4

    /*
     * [formatCivilDate]'s constants. They are the published algorithm's, not choices, so they
     * are named for what they measure rather than for a role in this file.
     */

    /** 1970-01-01 counted from 0000-03-01, the shifted calendar's origin. */
    private const val DAYS_MARCH_0000_TO_EPOCH = 719_468L

    /** 400 Gregorian years, which is a whole number of days exactly once at this scale. */
    private const val DAYS_PER_ERA = 146_097L

    /** `DAYS_PER_ERA - 1`. Both the last index in an era and the floor-division bias. */
    private const val LAST_DAY_OF_ERA = 146_096L

    private const val YEARS_PER_ERA = 400L
    private const val DAYS_PER_LEAP_CYCLE = 1_460L
    private const val DAYS_PER_CENTURY = 36_524L
    private const val DAYS_PER_COMMON_YEAR = 365L
    private const val YEARS_PER_LEAP_CYCLE = 4L
    private const val YEARS_PER_CENTURY = 100L

    /** March to July inclusive, and every five-month run after it. The reason for the shift. */
    private const val DAYS_PER_FIVE_MONTHS = 153L

    private const val MONTHS_PER_153_DAYS = 5L
    private const val MONTHS_MARCH_TO_DECEMBER = 10L
    private const val MARCH_IS_MONTH_3 = 3L
    private const val JANUARY_WRAPS_BY = 9L
}
