package dev.inkstone.app.elements

import dev.inkstone.core.model.Clock
import dev.inkstone.core.model.DocumentId
import dev.inkstone.core.model.ElementId
import dev.inkstone.core.model.FileSystem
import dev.inkstone.core.model.FoldedPage
import dev.inkstone.core.model.FractionalKey
import dev.inkstone.core.model.IdFactory
import dev.inkstone.core.model.LamportClock
import dev.inkstone.core.model.Matrix3
import dev.inkstone.core.model.ObjectId
import dev.inkstone.core.model.PagePoint
import dev.inkstone.core.model.PageRect
import dev.inkstone.core.storage.Element
import dev.inkstone.core.storage.ElementLibrary
import dev.inkstone.core.storage.ElementStamp
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ⭐ The Element library as the editor sees it — `:core:storage`'s [ElementLibrary], plus a one-time
 * migration of what the M8c stand-in saved.
 *
 * ## Why the switch
 *
 * The stand-in ([LegacyElementLibrary]) could not hold handwriting, and handwriting is what a person
 * draws an icon with. `:core:storage`'s library copies a stroke's geometry blob verbatim — it never
 * needed to understand it, only to carry it — and the one thing it could not do, measure the ink to
 * find the Element's origin, is now the caller's job: `save` takes the box the surface measured. So
 * nothing here knows what kind of object anything is.
 *
 * ## The migration
 *
 * On the first [list], every row of the legacy `elements/index.json` is rebuilt as a page of real
 * objects ([LegacyElementLibrary.asPage]), saved through the core library, and only **then** removed
 * from the index. Its picture files stay where they were, so a migration that went wrong has the
 * originals to go back to (CLAUDE.md rule 1). A crash between the save and the removal leaves the
 * Element twice in the panel rather than nowhere, which is the direction a crash should fail in.
 *
 * ⚠️ A row that cannot be rebuilt — nothing in it this build can make — stays in the index rather
 * than being dropped. It is invisible in the panel either way, and leaving it costs nothing.
 */
@Singleton
class ElementShelf @Inject constructor(private val fs: FileSystem, clock: Clock) {

    private val library = ElementLibrary(fs, IdFactory(clock))
    private val legacy = LegacyElementLibrary(fs, clock)

    /** One migration at a time: the panel and a save can both ask for [list] within a frame. */
    private val migration = Mutex()
    private var migrated = false

    /** Every saved Element, ordered by collection, then name. */
    suspend fun list(): List<Element> {
        migrateLegacy()
        return library.list()
    }

    /**
     * Save [ids] from [page] as a named Element.
     *
     * @param measured the selection's box as the surface drew it — strokes included, which is what
     *   makes an Element of handwriting land where it is dropped (`ElementLibrary.save`'s KDoc).
     * @return null when none of [ids] is on [page] — an Element of nothing would sit in the panel
     *   stamping nothing.
     */
    suspend fun save(
        name: String,
        collection: String,
        page: FoldedPage,
        ids: Collection<ObjectId>,
        assets: Map<String, ByteArray>,
        measured: PageRect?,
    ): Element? {
        val present = ids.filterTo(LinkedHashSet()) { id -> page.objects.any { it.id == id } }
        if (present.isEmpty()) return null
        return library.save(
            name = name.ifBlank { DEFAULT_NAME },
            collection = collection.ifBlank { DEFAULT_COLLECTION },
            page = page,
            selection = present,
            assets = assets,
            measured = measured,
        )
    }

    /** Moves the Element to `elements/trash/` — CLAUDE.md rule 1 has no exception for small things. */
    suspend fun delete(id: ElementId) = library.delete(id)

    /**
     * One insertion with [element]'s top-left at [topLeft] — fresh ids and fresh asset names, every
     * time (SPEC §4.9.4). See `ElementLibrary.stamp`.
     */
    @Suppress("LongParameterList") // Where, which key, the three generators, and which copy.
    fun stamp(
        element: Element,
        topLeft: PagePoint,
        topZ: FractionalKey?,
        newObjectId: () -> ObjectId,
        nextOpId: () -> String,
        nextClock: () -> LamportClock,
        instanceId: String? = null,
    ): ElementStamp = library.stamp(
        element = element,
        at = Matrix3.translate(topLeft.x, topLeft.y),
        z = topZ,
        newObjectId = newObjectId,
        nextOpId = nextOpId,
        nextClock = nextClock,
        instanceId = instanceId,
    )

    /** See `ElementLibrary.replaceCopies` — every copy of [element] on [page], replaced in place. */
    @Suppress("LongParameterList")
    fun replaceCopies(
        page: FoldedPage,
        element: Element,
        newObjectId: () -> ObjectId,
        nextOpId: () -> String,
        nextClock: () -> LamportClock,
        originMoved: PagePoint = PagePoint(0f, 0f),
    ): ElementStamp? = library.replaceCopies(page, element, newObjectId, nextOpId, nextClock, originMoved)

    /** One Element, freshly read — what the editor opens and what an update replaces copies with. */
    suspend fun find(id: ElementId): Element? = list().firstOrNull { it.id == id }

    /**
     * ⭐ Save an edited Element **under its own id**, the previous version moved to trash first
     * (CLAUDE.md rule 1, `ElementLibrary.save`'s `replacing`). Name and collection are kept.
     *
     * @param pivot in page space on the editor's canvas; stored relative to the Element's origin.
     */
    @Suppress("LongParameterList")
    suspend fun revise(
        original: Element,
        page: FoldedPage,
        ids: Collection<ObjectId>,
        assets: Map<String, ByteArray>,
        measured: PageRect?,
        pivot: PagePoint?,
    ): Element? {
        val present = ids.filterTo(LinkedHashSet()) { id -> page.objects.any { it.id == id } }
        if (present.isEmpty()) return null
        return library.save(
            name = original.name,
            collection = original.collection,
            page = page,
            selection = present,
            assets = assets,
            measured = measured,
            pivot = pivot,
            replacing = original.id,
        )
    }

    /**
     * ⭐ Remember that [noteId] holds copies of [elementId], so an edit can find them (SPEC §4.9.7).
     *
     * A small file beside the Element rather than a scan of every note: a library of five hundred
     * notes would have to be opened and folded to answer *"are there copies?"* on every Done. The
     * list may name a note that no longer holds a copy — that note answers zero when asked — but it
     * must never miss one this device placed.
     *
     * ⚠️ **Copies this device did not place are not in it**: one stamped on another tablet and
     * synced here, or carried into a duplicated note. Those are updated only from the device that
     * placed them. `docs/M8-ELEMENT-EDITOR.md` §5.
     */
    suspend fun recordUse(elementId: ElementId, noteId: DocumentId) = registry.withLock {
        val known = usesUnlocked(elementId)
        if (noteId.value in known) return@withLock
        fs.createDirectories(USES_DIR)
        fs.writeBytesAtomically(usesPath(elementId), (known + noteId.value).joinToString(LINE).encodeToByteArray())
    }

    /** The notes [elementId] has been placed in from this device. See [recordUse]. */
    suspend fun uses(elementId: ElementId): List<DocumentId> =
        registry.withLock { usesUnlocked(elementId).map { DocumentId(it) } }

    private suspend fun usesUnlocked(elementId: ElementId): List<String> {
        val path = usesPath(elementId)
        if (!fs.exists(path)) return emptyList()
        return fs.readBytes(path).decodeToString().lines().filter { it.isNotBlank() }
    }

    private fun usesPath(elementId: ElementId) = "$USES_DIR/${elementId.value}.uses"

    private val registry = Mutex()

    private suspend fun migrateLegacy() = migration.withLock {
        if (migrated) return@withLock
        for (old in legacy.list()) {
            val rebuilt = legacy.asPage(old) ?: continue
            library.save(
                name = old.name,
                collection = old.collection,
                page = rebuilt.page,
                selection = rebuilt.ids,
                assets = rebuilt.assets,
                measured = rebuilt.bounds,
            )
            legacy.delete(old.id)
        }
        migrated = true
    }

    private companion object {
        const val DEFAULT_NAME = "Element"
        const val DEFAULT_COLLECTION = "My elements"

        /** Beside the Element files, not among them: `ElementLibrary.list` reads every `.element`. */
        const val USES_DIR = "elements/uses"
        const val LINE = "\n"
    }
}
