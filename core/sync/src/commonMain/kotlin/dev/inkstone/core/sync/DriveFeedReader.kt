package dev.inkstone.core.sync

import io.ktor.http.encodeURLParameter
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/**
 * The reading half of Google Drive's `changes` feed (docs/CLOUD-PROVIDERS.md §4): pages in, one
 * path-resolved [ChangeBatch] out. Its own class because [DriveStore] is about requests and this half
 * has nothing to do with writing.
 *
 * - **Tracks by file id.** A change carries `parents` (ids), never a path, so a path is rebuilt by asking
 *   for each unknown parent once (`files.get`, memoised for the pass).
 * - **No scoping is needed**, unlike OneDrive: under the `drive.file` scope Drive only shows files this
 *   app created, so "everything" is the app's own notes and not somebody's whole drive.
 * - **A deletion is named, never inferred**: a change with `removed`, or whose file is `trashed`, is a
 *   [RemoteChange.Removed]. After a first enumeration or a resync a missing file proves nothing
 *   ([DeltaKind]).
 * - **A page token Google no longer honours starts over** as [DeltaKind.RESYNC].
 *
 * ⚠️ Everything here is read from documentation and **never observed against Google**: that a token the
 * service has expired answers 400/404/410 (the docs pages read did not say which — all three are treated
 * as "start over"), that `trashed` arrives on the file of a change, and what a folder rename reports.
 *
 * @param get an authenticated GET of an absolute URL: the parsed body, or why there is none.
 */
internal class DriveFeedReader(
    private val base: String,
    private val get: suspend (url: String, describe: String) -> DavOutcome<JsonElement>,
) {

    suspend fun read(since: String?): DavOutcome<ChangeBatch> {
        if (since == null) return startOver(DeltaKind.FIRST)
        return when (val pages = readChanges(since)) {
            is DavOutcome.Ok -> DavOutcome.Ok(
                ChangeBatch(resolve(pages.value.changes), pages.value.cursor, DeltaKind.INCREMENTAL),
            )

            is DavOutcome.Failed ->
                if (pages.error.meansTokenIsStale()) startOver(DeltaKind.RESYNC) else pages
        }
    }

    private fun DavFailure.meansTokenIsStale(): Boolean =
        this is DavFailure.NotFound || (this is DavFailure.Server && status in STALE_STATUSES)

    private class Seen(val id: String, val name: String, val parent: String?, val removed: Boolean, val entry: Entry?)

    private class Pages(val changes: List<Seen>, val cursor: String)

    private class Entry(val id: String, val name: String, val head: String?, val size: Long?, val modified: String?)

    /** No usable cursor: take the feed's *current* one, then list every PDF the app can see. */
    @Suppress("LoopWithTooManyJumpStatements", "ReturnCount")
    private suspend fun startOver(kind: DeltaKind): DavOutcome<ChangeBatch> {
        val marker = when (val token = get("$base/drive/v3/changes/startPageToken", "changes/startPageToken")) {
            is DavOutcome.Ok -> token.value.text("startPageToken")
                ?: return DavOutcome.Failed(DavFailure.Malformed("Drive's start page token was missing"))

            is DavOutcome.Failed -> return token
        }
        val listed = ArrayList<Seen>()
        var url: String? = firstListUrl()
        var guard = 0
        while (url != null && guard++ < MAX_PAGES) {
            val page = when (val reply = get(url, "files")) {
                is DavOutcome.Ok -> reply.value
                is DavOutcome.Failed -> return reply
            }
            for (element in (page.field("files") as? JsonArray).orEmpty()) seenOf(element, null)?.let(listed::add)
            url = page.text("nextPageToken")?.let { firstListUrl(it) }
        }
        return DavOutcome.Ok(ChangeBatch(resolve(listed), marker, kind))
    }

    private fun firstListUrl(pageToken: String? = null): String {
        val q = "mimeType='$PDF' and trashed=false".encodeURLParameter()
        val fields = "nextPageToken,files($FILE_FIELDS)".encodeURLParameter()
        val token = pageToken?.let { "&pageToken=${it.encodeURLParameter()}" }.orEmpty()
        return "$base/drive/v3/files?q=$q&fields=$fields&pageSize=$PAGE_SIZE$token"
    }

    @Suppress("CyclomaticComplexMethod", "LoopWithTooManyJumpStatements")
    // One loop with the feed's whole protocol in it — page, fold, advance the token, stop on the new start
    // token — and splitting it would scatter the one invariant (a cursor only at the very end) across helpers.
    private suspend fun readChanges(since: String): DavOutcome<Pages> {
        val seen = LinkedHashMap<String, Seen>()
        var token: String = since
        var cursor: String? = null
        var guard = 0
        while (cursor == null && guard++ < MAX_PAGES) {
            val page = when (val reply = get(changesUrl(token), "changes")) {
                is DavOutcome.Ok -> reply.value
                is DavOutcome.Failed -> return reply
            }
            for (element in (page.field("changes") as? JsonArray).orEmpty()) {
                val change = element as? JsonObject ?: continue
                val id = change.text("fileId") ?: continue
                val removed = change.bool("removed") == true
                val item = seenOf(change["file"], removed) ?: if (removed) Seen(id, "", null, true, null) else null
                seen.remove(id) // the last occurrence wins, and keeps its place in order
                if (item != null) seen[id] = item
            }
            val next = page.text("nextPageToken")
            if (next != null) {
                token = next
            } else {
                cursor = page.text("newStartPageToken")
                    ?: return DavOutcome.Failed(DavFailure.Malformed("the changes feed ended with no new start token"))
            }
        }
        return cursor?.let { DavOutcome.Ok(Pages(seen.values.toList(), it)) }
            ?: DavOutcome.Failed(DavFailure.Malformed("the changes feed did not end within $MAX_PAGES pages"))
    }

    private fun changesUrl(token: String): String {
        val fields = "nextPageToken,newStartPageToken,changes(fileId,removed,file($FILE_FIELDS))".encodeURLParameter()
        return "$base/drive/v3/changes?pageToken=${token.encodeURLParameter()}&includeRemoved=true" +
            "&pageSize=$CHANGE_PAGE_SIZE&fields=$fields"
    }

    /** A file as the feed shows it; null for what the sync engine never surfaces (a folder, a non-PDF). */
    private fun seenOf(element: JsonElement?, removed: Boolean?): Seen? {
        val file = element as? JsonObject ?: return null
        val id = file.text("id") ?: return null
        val trashed = removed == true || file.bool("trashed") == true
        if (trashed) return Seen(id, file.text("name").orEmpty(), null, removed = true, entry = null)
        if (file.text("mimeType") != PDF) return null
        val parent = (file.field("parents") as? JsonArray)?.firstOrNull()?.let { (it as? JsonPrimitive)?.contentOrNull }
        return Seen(
            id = id,
            name = file.text("name").orEmpty(),
            parent = parent,
            removed = false,
            entry = Entry(
                id,
                file.text("name").orEmpty(),
                file.text("headRevisionId"),
                file.long("size"),
                file.text("modifiedTime"),
            ),
        )
    }

    private suspend fun resolve(seen: List<Seen>): List<RemoteChange> {
        val paths = HashMap<String, String?>()
        val out = ArrayList<RemoteChange>()
        val rootId = (get("$base/drive/v3/files/root?fields=id", "root") as? DavOutcome.Ok)?.value?.text("id")
        for (item in seen) {
            val change = if (item.removed) RemoteChange.Removed(item.id) else fileChange(item, rootId, paths)
            if (change != null) out += change
        }
        return out
    }

    /** A live PDF with its path, or null when its folder's path cannot be established. */
    private suspend fun fileChange(
        item: Seen,
        rootId: String?,
        paths: MutableMap<String, String?>,
    ): RemoteChange.File? {
        val entry = item.entry ?: return null
        val folder = item.parent?.let { folderPath(it, rootId, paths, 0) } ?: return null
        val path = if (folder.isEmpty()) entry.name else "$folder/${entry.name}"
        return RemoteChange.File(resourceOf(entry, path), item.parent)
    }

    private fun resourceOf(entry: Entry, path: String) = DavResource(
        path = path,
        href = entry.id,
        isCollection = false,
        etag = ETag.parse(entry.head),
        lastModified = entry.modified,
        contentLength = entry.size,
        ocId = entry.id,
        fileId = entry.id,
        permissions = null,
        recursiveSize = null,
        checksums = emptyList(),
        dataFingerprint = null,
        lockOwner = null,
        locked = false,
        quotaAvailableBytes = null,
    )

    /** A folder id's path: "" at the top of My Drive, null when it cannot be established. Memoised. */
    private suspend fun folderPath(
        id: String,
        rootId: String?,
        memo: MutableMap<String, String?>,
        depth: Int,
    ): String? {
        if (id == rootId || id == "root") return ""
        if (memo.containsKey(id)) return memo[id]
        val resolved = if (depth > MAX_DEPTH) null else lookUp(id, rootId, memo, depth)
        memo[id] = resolved
        return resolved
    }

    @Suppress("ReturnCount") // Each return is a different way a parent's path cannot be established.
    private suspend fun lookUp(id: String, rootId: String?, memo: MutableMap<String, String?>, depth: Int): String? {
        val folder =
            (
                get(
                    "$base/drive/v3/files/$id?fields=${"id,name,parents,trashed".encodeURLParameter()}",
                    id,
                ) as? DavOutcome.Ok
                )
                ?.value ?: return null
        if (folder.bool("trashed") == true) return null
        val name = folder.text("name") ?: return null
        val parent =
            (folder.field("parents") as? JsonArray)?.firstOrNull()?.let { (it as? JsonPrimitive)?.contentOrNull }
                ?: return name // no parent: the top of what this app can see
        val above = folderPath(parent, rootId, memo, depth + 1) ?: return null
        return if (above.isEmpty()) name else "$above/$name"
    }

    private fun JsonElement.field(key: String): JsonElement? = (this as? JsonObject)?.get(key)

    private fun JsonElement.text(key: String): String? = (field(key) as? JsonPrimitive)?.contentOrNull

    private fun JsonElement.bool(key: String): Boolean? = (field(key) as? JsonPrimitive)?.booleanOrNull

    private fun JsonElement.long(key: String): Long? = (field(key) as? JsonPrimitive)?.longOrNull

    internal companion object {
        const val PDF = "application/pdf"
        const val FILE_FIELDS = "id,name,mimeType,parents,headRevisionId,md5Checksum,size,modifiedTime,trashed"
        const val PAGE_SIZE = 200
        const val CHANGE_PAGE_SIZE = 1000
        const val MAX_PAGES = 5_000
        const val MAX_DEPTH = 64
        val STALE_STATUSES = setOf(400, 410)
    }
}
