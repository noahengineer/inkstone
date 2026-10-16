package dev.inkstone.core.handwriting

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * docs/HANDWRITING-SYNTHESIS.md §4.2 — rule 1 and rule 10 for a profile: nothing user-authored is
 * overwritten without its previous version in the trash, nothing is deleted that was not in the
 * trash for thirty days, and a file this build cannot read is never rewritten.
 */
class ProfileStoreTest {

    private val key = ProfileKey.forAccount("0190-account")

    @Test
    fun `a new profile has the defaults and keeps what it is given`() = runTest {
        val store = ProfileStore(FakeFileSystem(), key)
        assertEquals(ProfileState(), store.state())

        val state = ProfileState(learning = false, harvestedPages = mapOf("n/p" to "digest"))
        store.saveState(state)

        assertEquals(state, store.state())
    }

    @Test
    fun `keys a later build wrote survive this build rewriting the file`() = runTest {
        val fs = FakeFileSystem()
        fs.put(HandwritingLayout.state(key), """{"v":1,"learning":true,"fromTheFuture":{"x":1}}""".encodeToByteArray())
        val store = ProfileStore(fs, key)

        store.saveState(store.state().copy(learnFromOlderNotes = false))

        val text = fs.readBytes(HandwritingLayout.state(key)).decodeToString()
        assertTrue("fromTheFuture" in text, text)
        assertFalse(store.state().learnFromOlderNotes)
    }

    @Test
    fun `replacing the labels moves the previous ones to the trash first`() = runTest {
        val fs = FakeFileSystem()
        val store = ProfileStore(fs, key)
        store.saveLabels(LabelBook(corrected = mapOf("k1" to "first")), nowEpochMillis = 1_000L)
        store.saveLabels(LabelBook(corrected = mapOf("k1" to "second")), nowEpochMillis = 2_000L)

        assertEquals("second", store.labels().corrected["k1"])
        val trashed = fs.paths.single { it.startsWith(HandwritingLayout.trashDir(key)) }
        assertTrue(trashed.endsWith("2000-labels.json"), trashed)
        assertTrue("first" in fs.readBytes(trashed).decodeToString())
    }

    @Test
    fun `samples fill a shard and spill into the next`() = runTest {
        val fs = FakeFileSystem()
        val store = ProfileStore(fs, key)
        store.add((0 until 200).map { Fixtures.sample("a$it") })
        store.add((0 until 100).map { Fixtures.sample("b$it") })

        val set = store.samples()
        assertEquals(300, set.samples.size)
        assertEquals(0, set.unreadableShards)
        assertEquals(2, fs.paths.count { it.startsWith(HandwritingLayout.wordsDir(key)) })
    }

    @Test
    fun `withdrawn samples go to the trash before the shard is rewritten`() = runTest {
        val fs = FakeFileSystem()
        val store = ProfileStore(fs, key)
        store.add(listOf(Fixtures.sample("keep"), Fixtures.sample("drop")))

        assertEquals(1, store.withdraw(setOf("drop"), nowEpochMillis = 5_000L))

        assertEquals(listOf("keep"), store.samples().samples.map { it.id })
        val trashed = fs.paths.single { it.startsWith(HandwritingLayout.trashDir(key)) }
        assertEquals(listOf("drop"), WordSampleCodec.decode(fs.readBytes(trashed))?.map { it.id })
    }

    @Test
    fun `a shard this build cannot read is never written to`() = runTest {
        // SPEC §5.8's rule, at profile scale: a later build's shard is left exactly as it is, and new
        // samples go to a shard of their own.
        val fs = FakeFileSystem()
        val future = WordSampleCodec.encode(listOf(Fixtures.sample("future"))).also { it[4] = 9 }
        val futurePath = "${HandwritingLayout.wordsDir(key)}/000000.hws"
        fs.put(futurePath, future)
        val store = ProfileStore(fs, key)

        store.add(listOf(Fixtures.sample("now")))
        store.withdraw(setOf("future"), nowEpochMillis = 1L)

        assertTrue(fs.readBytes(futurePath).contentEquals(future), "the unreadable shard was rewritten")
        assertEquals(listOf("now"), store.samples().samples.map { it.id })
        assertEquals(1, store.samples().unreadableShards)
    }

    @Test
    fun `wiping a profile is recoverable for thirty days and then purged`() = runTest {
        val fs = FakeFileSystem()
        val store = ProfileStore(fs, key)
        store.add(listOf(Fixtures.sample("one")))
        store.saveLabels(LabelBook(rejected = setOf("x")), nowEpochMillis = 1L)

        store.trashEverything(nowEpochMillis = DAY)

        assertTrue(store.samples().samples.isEmpty())
        assertEquals(LabelBook(), store.labels())
        assertTrue(fs.paths.any { it.startsWith("${HandwritingLayout.trashDir(key)}/$DAY-profile/") })

        assertEquals(0, store.purgeTrash(nowEpochMillis = DAY * 30))
        assertEquals(1, store.purgeTrash(nowEpochMillis = DAY * 32))
        assertTrue(fs.paths.none { it.startsWith(HandwritingLayout.trashDir(key)) })
    }

    @Test
    fun `wiping twice keeps each wipe on its own thirty day clock`() = runTest {
        // Found on the emulator: the second wipe carried the first one inside it, so purging, which
        // reads only the top of the trash, would have kept the first for thirty days from the second.
        val fs = FakeFileSystem()
        val store = ProfileStore(fs, key)
        val trash = HandwritingLayout.trashDir(key)
        store.add(listOf(Fixtures.sample("one")))
        store.trashEverything(nowEpochMillis = DAY)
        store.add(listOf(Fixtures.sample("two")))
        store.trashEverything(nowEpochMillis = DAY * 20)

        assertTrue(fs.paths.any { it.startsWith("$trash/$DAY-profile/") })
        assertTrue(fs.paths.any { it.startsWith("$trash/${DAY * 20}-profile/") })
        assertTrue(fs.paths.none { it.startsWith("$trash/${DAY * 20}-profile/${HandwritingLayout.TRASH_DIR_NAME}/") })

        assertEquals(1, store.purgeTrash(nowEpochMillis = DAY * 32))
        assertTrue(fs.paths.none { it.startsWith("$trash/$DAY-profile/") }, "the first wipe outlived its thirty days")
        assertTrue(fs.paths.any { it.startsWith("$trash/${DAY * 20}-profile/") }, "the second wipe went early")
    }

    @Test
    fun `labels keyed by the strokes apply to the sample`() {
        val sample = Fixtures.sample("one", sourceKey = "strokes")
        val book = LabelBook(corrected = mapOf("strokes" to "hit"), extras = mapOf("z" to JsonPrimitive(1)))

        assertEquals("hit", book.labelOf(sample))
        assertEquals(LabelSource.CORRECTED, book.sourceOf(sample))
        assertEquals(LabelSource.AGREED, LabelBook().sourceOf(sample))
    }

    private companion object {
        const val DAY = 24L * 60 * 60 * 1000
    }
}
