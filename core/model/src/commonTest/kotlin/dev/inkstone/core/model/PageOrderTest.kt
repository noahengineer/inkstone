package dev.inkstone.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** SPEC §4.3 — "any page not present in a merged order is appended at the end, never dropped." */
class PageOrderTest {

    private fun ref(id: String, key: String, deleted: Boolean = false) =
        PageRef(PageId(id), FractionalKey(key), deleted, if (deleted) 1L else null)

    @Test
    fun `two devices adding a page offline both keep their page`() {
        // The failure a plain LWW list produces: the later write wins, one device's page
        // disappears from the order, and its data sits in the bundle orphaned.
        val shared = listOf(ref("p1", "1"), ref("p2", "2"))
        val local = shared + ref("mine", "3")
        val remote = shared + ref("theirs", "4")

        val merged = PageOrder.mergePageOrder(local, remote)

        assertEquals(listOf("p1", "p2", "mine", "theirs"), merged.map { it.id.value })
    }

    @Test
    fun `two devices inserting at the same position both keep their page`() {
        val shared = listOf(ref("p1", "1"), ref("p2", "3"))
        val mine = FractionalKey.between(FractionalKey("1"), FractionalKey("3"))
        val theirs = FractionalKey.between(FractionalKey("1"), FractionalKey("3"))

        val merged = PageOrder.mergePageOrder(
            shared + PageRef(PageId("mine"), mine),
            shared + PageRef(PageId("theirs"), theirs),
        )

        assertEquals(4, merged.size)
        assertEquals("p1", merged.first().id.value)
        assertEquals("p2", merged.last().id.value)
    }

    @Test
    fun `a disagreement about a page's key is resolved the same way on both devices`() {
        val local = listOf(ref("p1", "1"), ref("p2", "5"))
        val remote = listOf(ref("p1", "1"), ref("p2", "3"))

        val fromLocal = PageOrder.mergePageOrder(local, remote)
        val fromRemote = PageOrder.mergePageOrder(remote, local)

        assertEquals(fromLocal, fromRemote, "the merge is not symmetric, so devices will diverge")
        assertEquals(FractionalKey("3"), fromLocal.first { it.id.value == "p2" }.sortKey)
    }

    @Test
    fun `a deleted page stays deleted rather than being resurrected by a peer`() {
        val local = listOf(ref("p1", "1"), ref("p2", "2", deleted = true))
        val remote = listOf(ref("p1", "1"), ref("p2", "2"))

        val merged = PageOrder.mergePageOrder(local, remote)

        val page = merged.first { it.id.value == "p2" }
        assertTrue(page.deleted, "a page the user deleted came back on sync")
        // The entry survives as a tombstone, so its data is still there to restore.
        assertEquals(2, merged.size)
    }

    @Test
    fun `insert positions are computed from live pages only`() {
        val pages = listOf(ref("p1", "1"), ref("gone", "2", deleted = true), ref("p2", "3"))

        val key = PageOrder.keyForInsert(pages, index = 1)

        assertTrue(FractionalKey("1") < key && key < FractionalKey("3"), "got $key")
    }

    @Test
    fun `inserting at the ends works`() {
        val pages = listOf(ref("p1", "5"), ref("p2", "7"))

        assertTrue(PageOrder.keyForInsert(pages, 0) < FractionalKey("5"))
        assertTrue(PageOrder.keyForInsert(pages, 2) > FractionalKey("7"))
        assertTrue(PageOrder.keyForInsert(emptyList(), 0).isWellFormed)
    }

    @Test
    fun `an order with unusable keys is repaired without losing or reordering a page`() {
        // Keys can arrive malformed from a file another tool touched. A sort key is
        // derived data, so rewriting one loses nothing — but dropping a page would.
        val pages = listOf(ref("p1", "A0"), ref("p2", "B"), ref("p3", ""))

        val repaired = PageOrder.repair(pages)

        assertEquals(3, repaired.size)
        assertEquals(listOf("p3", "p1", "p2"), repaired.map { it.id.value })
        assertTrue(repaired.all { it.sortKey.isWellFormed })
        assertEquals(repaired.sortedBy { it.sortKey.value }, repaired)
        // And a healthy order is left alone.
        val healthy = listOf(ref("p1", "1"), ref("p2", "2"))
        assertEquals(healthy, PageOrder.repair(healthy))
    }
}
