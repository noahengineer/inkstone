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
    val createdEpochMillis: Long = 0L,
    val modifiedEpochMillis: Long = 0L,
    val deletedEpochMillis: Long? = null,
    val deletedWithFolderId: FolderId? = null,
) {
    public val isTrashed: Boolean get() = deletedEpochMillis != null
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
        return if (segments.isEmpty()) base else base + "/" + segments.joinToString("/")
    }
}

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
}
