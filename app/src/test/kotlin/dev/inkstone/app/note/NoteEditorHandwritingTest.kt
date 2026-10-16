package dev.inkstone.app.note

import dev.inkstone.app.handwriting.phraseOf
import dev.inkstone.app.handwriting.stampHandwriting
import dev.inkstone.core.handwriting.HarvestPolicy
import dev.inkstone.core.handwriting.Harvester
import dev.inkstone.core.handwriting.SkipReason
import dev.inkstone.core.model.Clock
import dev.inkstone.core.model.GeneratedInk
import dev.inkstone.core.model.IdFactory
import dev.inkstone.core.model.ObjectFormat
import dev.inkstone.core.model.ObjectStyle
import dev.inkstone.core.model.PageRect
import dev.inkstone.core.model.PhrasePlacement
import dev.inkstone.core.model.PhraseRecord
import dev.inkstone.core.model.StrokeGeometry
import dev.inkstone.core.model.StrokeObject
import dev.inkstone.core.model.TextBoxObject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * ⭐ docs/HANDWRITING-SYNTHESIS.md §4.3 and §6.3 — a written phrase is **one** undo entry, carries its
 * `hw` tag and its words, and replacing a text box with handwriting is undone by one press too.
 *
 * Driven through the real `NoteEditor` and a real `NoteSession`, so the undo is the production undo
 * stack. The stroke geometry is opaque bytes here — `:android:ink`'s encoder needs native code — so
 * this proves the *edit*, not the ink: that is the emulator's (docs §12, H3).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NoteEditorHandwritingTest {

    private fun TestScope.editor() = NoteEditor(
        fs = InMemoryFileSystem(),
        clock = Clock { NOW },
        scope = this,
        drainDispatcher = UnconfinedTestDispatcher(testScheduler),
        autosaveIntervalMs = Long.MAX_VALUE,
    )

    private val tag = GeneratedInk("01a2b3c4-0000-7000-8000-00000000abcd", GeneratedInk.ASSEMBLY)
    private val record = PhraseRecord("milk eggs", PhrasePlacement(50f, 100f, 5f, 0.1f, 0f, 300f, 16f, 0.5f))
    private val strokes = (1..3).map { StrokeGeometry("stroke-$it".encodeToByteArray()) }
    private val ids = IdFactory(Clock { NOW })

    @Test
    fun `a phrase is three strokes, each tagged, with its words on the first, and one undo removes it`() = runTest {
        val editor = editor()
        editor.onSurfaceAttached(FakeSurface())
        runCurrent()

        val placed = async { editor.stampHandwriting(strokes, List(strokes.size) { tag }, record, ids) }
        runCurrent()
        val written = placed.await()
        val page = assertNotNull(async { editor.currentFold() }.also { runCurrent() }.await())

        assertEquals(3, written.size)
        assertEquals(written, page.objects.filterIsInstance<StrokeObject>().sortedBy { it.z }.map { it.id })
        assertTrue(written.all { GeneratedInk.ofRecord(page.formatOf(it)) == tag })
        assertEquals(record, PhraseRecord.decode(page.texts[written.first()]))
        // ⭐ Never learned from (D8): the harvester's own rule refuses every one of them.
        val policy = HarvestPolicy(learnFromOlderNotes = true)
        assertTrue(written.all { Harvester.ineligibility(page, it, emptyMap(), policy) == SkipReason.GENERATED })

        editor.undo()
        runCurrent()
        val undone = assertNotNull(async { editor.currentFold() }.also { runCurrent() }.await())
        assertTrue(undone.objects.isEmpty(), "one undo should take the whole phrase away")
        editor.close()
    }

    @Test
    fun `text box to handwriting replaces the box, and one undo brings the box back`() = runTest {
        val editor = editor()
        editor.onSurfaceAttached(FakeSurface())
        runCurrent()
        val box = async {
            val style = ObjectStyle(-16777216, 1f)
            editor.insertTextBox(PageRect(40f, 40f, 240f, 80f), "milk eggs", style, ObjectFormat.Default)
        }.also { runCurrent() }.await()
        val boxId = assertNotNull(box)

        async { editor.stampHandwriting(strokes, List(strokes.size) { tag }, record, ids, replacing = listOf(boxId)) }
            .also { runCurrent() }.await()
        val written = assertNotNull(async { editor.currentFold() }.also { runCurrent() }.await())
        assertTrue(written.objects.none { it is TextBoxObject })
        assertEquals(3, written.objects.size)

        editor.undo()
        runCurrent()
        val undone = assertNotNull(async { editor.currentFold() }.also { runCurrent() }.await())
        assertEquals(listOf(boxId), undone.objects.map { it.id })
        editor.close()
    }

    @Test
    fun `write again finds the phrase by any of its strokes and replaces all of them in one entry`() = runTest {
        val editor = editor()
        editor.onSurfaceAttached(FakeSurface())
        runCurrent()
        val tags = List(strokes.size) { tag }
        val first = async { editor.stampHandwriting(strokes, tags, record, ids) }.also { runCurrent() }.await()

        val found = assertNotNull(async { editor.phraseOf(listOf(first[2])) }.also { runCurrent() }.await())
        assertEquals(first, found.strokes)
        assertEquals("milk eggs", found.record.text)
        assertEquals(50f, assertNotNull(found.placement).originX, 1e-3f)

        val again = GeneratedInk("01a2b3c4-0000-7000-8000-00000000beef", GeneratedInk.ASSEMBLY)
        val second = async {
            editor.stampHandwriting(strokes.take(2), List(2) { again }, record, ids, replacing = found.strokes)
        }
            .also { runCurrent() }.await()
        val page = assertNotNull(async { editor.currentFold() }.also { runCurrent() }.await())
        assertEquals(second.toSet(), page.objects.map { it.id }.toSet())

        editor.undo()
        runCurrent()
        val undone = assertNotNull(async { editor.currentFold() }.also { runCurrent() }.await())
        assertEquals(first.toSet(), undone.objects.map { it.id }.toSet(), "one undo restores the phrase as it was")
        editor.close()
    }

    private companion object {
        const val NOW = 1_800_000_000_000
    }
}
