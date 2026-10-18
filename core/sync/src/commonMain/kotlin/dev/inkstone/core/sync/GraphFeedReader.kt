package dev.inkstone.core.sync

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/**
 * The reading half of OneDrive's `delta` (docs/CLOUD-PROVIDERS.md §4): pages in, one path-resolved
 * [ChangeBatch] out. Its own class because [OneDriveStore] is already the longest thing in this
 * package and this half has nothing to do with writing.
 *
 * - **Tracks by id.** Graph says a delta item's `parentReference.path` is empty and *"you should always
 *   track items by id"*, so a path is rebuilt from the folders in the same reading, and a folder that is
 *   not in it (a parent the feed did not repeat) is asked for once.
 * - **The last occurrence wins** — Graph may list one item more than once.
 * - **A `410 Gone` starts over** from the drive root and the batch says so ([DeltaKind.RESYNC]), because
 *   after one the *absence* of a file proves nothing (see [DeltaKind]).
 * - **A cursor or `nextLink` that is not on this account's own API host is refused.** A response must not
 *   be able to steer the bearer token that [get] attaches to another server.
 *
 * ⚠️ Everything it assumes about Graph's feed — that parents repeat, that items carry no path, what a
 * `410` is — is read from Microsoft's documentation and **never observed** (spike M-M7).
 *
 * @param get an authenticated GET of an absolute URL: the parsed body, or why there is none.
 */
internal class GraphFeedReader(
    private val base: String,
    private val drive: String,
    private val get: suspend (url: String, describe: String) -> DavOutcome<JsonElement>,
    private val listChildren: suspend (path: String) -> DavOutcome<List<DavResource>>,
    private val folderOf: suspend (path: String) -> DavOutcome<DavResource>,
) {

    /**
     * @param firstPassScope the folders a first enumeration or a resync may list — see
     *   [CloudStore.changes]. Ignored when [since] is a live cursor, which is already only what changed.
     */
    suspend fun read(since: String?, firstPassScope: List<String>): DavOutcome<ChangeBatch> {
        if (since != null && !since.startsWith(base)) {
            return DavOutcome.Failed(DavFailure.Malformed("a feed cursor that is not this account's"))
        }
        if (since == null) return startOver(DeltaKind.FIRST, firstPassScope)
        return when (val feed = readPages(since)) {
            is DavOutcome.Ok ->
                DavOutcome.Ok(ChangeBatch(resolve(feed.value), feed.value.deltaLink, DeltaKind.INCREMENTAL))

            is DavOutcome.Failed -> {
                val failure = feed.error
                if (failure is DavFailure.Server && failure.status == HTTP_GONE) {
                    startOver(DeltaKind.RESYNC, firstPassScope)
                } else {
                    feed
                }
            }
        }
    }

    /**
     * No usable cursor: take the feed's *latest* one, then list only [scope].
     *
     * ⭐ **The marker first, the walk second.** Anything that changes while the walk runs is then in the
     * next incremental batch rather than lost between the two; and a change that is seen twice is
     * harmless, because every per-file decision downstream is idempotent (an unchanged version tag is a
     * no-op). The other order loses changes silently.
     *
     * `?token=latest` is documented to answer an empty page and a `deltaLink` — "no items, just where
     * the feed is now" — and **that has not been observed against Graph by anyone here** except through
     * the first real sign-in, which is what showed that the alternative (list everything) never ends.
     */
    private suspend fun startOver(kind: DeltaKind, scope: List<String>): DavOutcome<ChangeBatch> {
        val marker = when (val latest = readPages("$drive/root/delta?token=latest")) {
            is DavOutcome.Ok -> latest.value.deltaLink
            is DavOutcome.Failed -> return latest
        }
        val found = ArrayList<RemoteChange>()
        return when (val walked = walk(scope, found)) {
            is DavOutcome.Ok -> DavOutcome.Ok(ChangeBatch(found, marker, kind))
            is DavOutcome.Failed -> walked
        }
    }

    /**
     * Breadth-first over [scope], collecting PDFs. A folder that does not exist is skipped (the default
     * folder usually does not, until the first upload); any other failure fails the walk, because a
     * listing that stopped half-way must not be mistaken for a complete one.
     *
     * A blank root is the drive's top level, listed **without descending** — a binding at the top of the
     * drive must not turn into a walk of everything. Folders are capped at [MAX_FOLDERS]; what lies past
     * the cap is simply not adopted now (never *trashed*: a first enumeration's silence proves nothing,
     * [DeltaKind]) and is seen if it changes.
     */
    private suspend fun walk(scope: List<String>, out: MutableList<RemoteChange>): DavOutcome<Unit> {
        val queue = ArrayDeque<Folder>()
        for (root in scope.map { it.trim('/') }.distinct()) queue.addLast(Folder(root, null, 0))
        val seen = HashSet<String>()
        while (queue.isNotEmpty() && seen.size < MAX_FOLDERS) {
            val queued = queue.removeFirst()
            if (!seen.add(queued.path)) continue
            when (val step = visit(queued, queue, out)) {
                is DavOutcome.Failed -> return step
                is DavOutcome.Ok -> Unit
            }
        }
        return DavOutcome.Ok(Unit)
    }

    /**
     * One folder: learn its id (a scope root arrives with a path only; the incremental feed gives every
     * file its parent's id, so a first pass does too), list it, record its PDFs and queue its folders.
     * A folder that does not exist is *not* a failure: the default folder usually does not, until the
     * first upload.
     */
    private suspend fun visit(
        queued: Folder,
        queue: ArrayDeque<Folder>,
        out: MutableList<RemoteChange>,
    ): DavOutcome<Unit> {
        val folder = when (val resolved = withId(queued)) {
            is DavOutcome.Ok -> resolved.value
            is DavOutcome.Failed -> return missingIsEmpty(resolved)
        }
        val children = when (val reply = listChildren(folder.path)) {
            is DavOutcome.Ok -> reply.value
            is DavOutcome.Failed -> return missingIsEmpty(reply)
        }
        for (child in children) {
            // A blank root is the drive's top level and is never descended into.
            if (child.isCollection && folder.path.isNotEmpty() && folder.depth < MAX_DEPTH) {
                queue.addLast(Folder(child.path.trim('/'), child.ocId, folder.depth + 1))
            } else if (!child.isCollection && child.name.endsWith(PDF_SUFFIX, ignoreCase = true)) {
                out += RemoteChange.File(child, folder.id)
            }
        }
        return DavOutcome.Ok(Unit)
    }

    private suspend fun withId(folder: Folder): DavOutcome<Folder> {
        if (folder.id != null || folder.path.isEmpty()) return DavOutcome.Ok(folder)
        return when (val self = folderOf(folder.path)) {
            is DavOutcome.Ok -> DavOutcome.Ok(Folder(folder.path, self.value.ocId, folder.depth))
            is DavOutcome.Failed -> self
        }
    }

    private fun missingIsEmpty(failed: DavOutcome.Failed): DavOutcome<Unit> =
        if (failed.error is DavFailure.NotFound) DavOutcome.Ok(Unit) else failed

    private class Folder(val path: String, val id: String?, val depth: Int)

    private class FeedItem(
        val id: String,
        val name: String,
        val parentId: String?,
        val isFolder: Boolean,
        val isRoot: Boolean,
        val deleted: Boolean,
        val etag: ETag?,
        val size: Long?,
        val modified: String?,
    )

    private class Feed(val items: Map<String, FeedItem>, val deltaLink: String)

    /** Where a page says to go next — or that the feed is over, or that it makes no sense. */
    private sealed interface Step {
        data class Next(val url: String) : Step

        data class Done(val deltaLink: String) : Step

        data class Broken(val reason: String) : Step
    }

    private suspend fun readPages(first: String): DavOutcome<Feed> {
        val items = LinkedHashMap<String, FeedItem>()
        var url: String? = first
        var pages = 0
        while (url != null && pages++ < MAX_FEED_PAGES) {
            val json = when (val page = get(url, "delta")) {
                is DavOutcome.Ok -> page.value
                is DavOutcome.Failed -> return page
            }
            for (element in (json.field("value") as? JsonArray).orEmpty()) collect(element, items)
            when (val step = stepAfter(json)) {
                is Step.Next -> url = step.url
                is Step.Done -> return DavOutcome.Ok(Feed(items, step.deltaLink))
                is Step.Broken -> return DavOutcome.Failed(DavFailure.Malformed(step.reason))
            }
        }
        return DavOutcome.Failed(DavFailure.Malformed("the feed did not end within $MAX_FEED_PAGES pages"))
    }

    private fun stepAfter(page: JsonElement): Step {
        val next = page.text("@odata.nextLink")
        val done = page.text("@odata.deltaLink")
        return when {
            next != null && !next.startsWith(base) -> Step.Broken("the feed pointed at another host")
            next != null -> Step.Next(next)
            done != null && !done.startsWith(base) -> Step.Broken("the feed's cursor is on another host")
            done != null -> Step.Done(done)
            else -> Step.Broken("the feed ended with no cursor")
        }
    }

    private fun collect(element: JsonElement, items: MutableMap<String, FeedItem>) {
        val id = element.text("id") ?: return
        val parent = element.field("parentReference")
        items.remove(id) // so a later occurrence is also the *last* in order
        items[id] = FeedItem(
            id = id,
            name = element.text("name").orEmpty(),
            parentId = parent?.text("id"),
            isFolder = element.field("folder") is JsonObject,
            isRoot = element.field("root") is JsonObject,
            deleted = element.field("deleted") is JsonObject,
            etag = ETag.parse(element.text("eTag")),
            size = (element.field("size") as? JsonPrimitive)?.longOrNull,
            modified = element.text("lastModifiedDateTime"),
        )
    }

    private suspend fun resolve(feed: Feed): List<RemoteChange> {
        val folders = feed.items.values.filter { it.isFolder && !it.deleted }.associateBy { it.id }
        val resolved = HashMap<String, String?>()
        val out = ArrayList<RemoteChange>()
        for (item in feed.items.values) {
            when {
                item.deleted -> out += RemoteChange.Removed(item.id)
                item.isFolder || !item.name.endsWith(PDF_SUFFIX, ignoreCase = true) -> Unit
                else -> pdfChange(item, folders, resolved)?.let { out += it }
            }
        }
        return out
    }

    private suspend fun pdfChange(
        item: FeedItem,
        folders: Map<String, FeedItem>,
        resolved: MutableMap<String, String?>,
    ): RemoteChange.File? {
        val parent = item.parentId?.let { folderPath(it, folders, resolved, 0) } ?: return null
        val path = if (parent.isEmpty()) item.name else "$parent/${item.name}"
        return RemoteChange.File(resourceOf(item, path), item.parentId)
    }

    private fun resourceOf(item: FeedItem, path: String) = DavResource(
        path = path,
        href = item.id,
        isCollection = false,
        etag = item.etag,
        lastModified = item.modified,
        contentLength = item.size,
        ocId = item.id,
        fileId = item.id,
        permissions = null,
        recursiveSize = null,
        checksums = emptyList(),
        dataFingerprint = null,
        lockOwner = null,
        locked = false,
        quotaAvailableBytes = null,
    )

    /**
     * A folder's path from the feed's own folders; one request for a folder the feed did not carry.
     * Memoised for every ancestor on the way, so a deep tree costs one walk, not one per file.
     */
    private suspend fun folderPath(
        id: String,
        folders: Map<String, FeedItem>,
        resolved: MutableMap<String, String?>,
        depth: Int,
    ): String? {
        if (resolved.containsKey(id)) return resolved[id]
        val folder = folders[id]
        val path = when {
            depth > MAX_DEPTH -> null

            folder == null -> askForFolderPath(id)

            folder.isRoot -> ""

            else -> folder.parentId?.let { folderPath(it, folders, resolved, depth + 1) }
                ?.let { above -> if (above.isEmpty()) folder.name else "$above/${folder.name}" }
        }
        resolved[id] = path
        return path
    }

    /** `GET items/{id}` → `parentReference.path` + name; null if it cannot be established. */
    private suspend fun askForFolderPath(id: String): String? {
        val item = (get("$drive/items/$id", id) as? DavOutcome.Ok)?.value ?: return null
        if (item.field("root") is JsonObject) return ""
        val name = item.text("name")
        val parentPath = item.field("parentReference")?.text("path")
        // "/drive/root:/A/B" or "/drives/{id}/root:/A/B" — what follows `root:` is the parent's path.
        if (name == null || parentPath == null || !parentPath.contains(ROOT_MARKER)) return null
        val above = parentPath.substringAfter(ROOT_MARKER).trim('/')
        return if (above.isEmpty()) name else "$above/$name"
    }

    private fun JsonElement.field(key: String): JsonElement? = (this as? JsonObject)?.get(key)

    private fun JsonElement.text(key: String): String? = (field(key) as? JsonPrimitive)?.contentOrNull

    private companion object {
        const val HTTP_GONE = 410
        const val MAX_FEED_PAGES = 5_000
        const val MAX_FOLDERS = 2_000
        const val MAX_DEPTH = 64
        const val PDF_SUFFIX = ".pdf"
        const val ROOT_MARKER = "root:"
    }
}
