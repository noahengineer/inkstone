package dev.inkstone.app.calculator

import dev.inkstone.core.model.PagePoint
import dev.inkstone.core.model.PageRect
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * [AnswerLanding] — the user's *"Handwriting, else text"* (docs/CALCULATOR.md §6, A1), on plain
 * functions.
 *
 * ⚠️ What this cannot say: that the real synthesizer writes an answer, that its read-back gate refuses
 * the ones it should, or that either write reaches the journal. Those are `PhraseWriter`'s and the
 * editor's, and the emulator walk (§6.4) is what drives them with a real profile.
 */
class AnswerLandingTest {
    private val box = PageRect(100f, 200f, 194f, 224f)

    private class Recorder {
        val written = mutableListOf<Pair<String, PagePoint>>()
        val typeset = mutableListOf<Pair<PageRect, String>>()
        val reports = mutableListOf<String>()
    }

    private fun landing(recorder: Recorder, typesets: Boolean = true, hand: suspend (String, PagePoint) -> String?) =
        AnswerLanding(
            handwrite = { words, start ->
                recorder.written += words to start
                hand(words, start)
            },
            typeset = { box, words ->
                recorder.typeset += box to words
                typesets
            },
            report = { recorder.reports += it },
        )

    @Test
    fun `written by hand is the whole of it`() = runTest {
        val r = Recorder()
        landing(r) { _, _ -> null }.land("55,55555556", box, 13f)
        assertEquals(listOf("55.55555556" to PagePoint(100f, 213f)), r.written)
        assertEquals(emptyList(), r.typeset, "no text box when the hand wrote it")
        assertEquals(emptyList(), r.reports)
    }

    @Test
    fun `a refusal becomes a text box with the same words and says why`() = runTest {
        val r = Recorder()
        landing(r) { _, _ -> "Not written: '3.14' was read back as 3.19" }.land("3,14", box, 13f)
        assertEquals(listOf(box to "3.14"), r.typeset)
        assertEquals(1, r.reports.size)
        assertTrue(r.reports.single().startsWith(AnswerLanding.AS_TEXT), r.reports.single())
        assertTrue(r.reports.single().endsWith("read back as 3.19"), r.reports.single())
    }

    @Test
    fun `no hand to write in becomes a text box`() = runTest {
        val r = Recorder()
        landing(r) { _, _ -> "Nothing learned yet — see My handwriting." }.land("1/200", box, 13f)
        assertEquals(listOf(box to "1/200"), r.typeset)
    }

    @Test
    fun `a handwriting failure that throws becomes a text box and not a crash`() = runTest {
        val r = Recorder()
        landing(r) { _, _ -> error("model file torn") }.land("12", box, 13f)
        assertEquals(listOf(box to "12"), r.typeset)
        assertTrue("model file torn" in r.reports.single(), r.reports.single())
    }

    @Test
    fun `cancellation is not a failure`() = runTest {
        val r = Recorder()
        assertFailsWith<CancellationException> {
            landing(r) { _, _ -> throw CancellationException("left the note") }.land("12", box, 13f)
        }
        assertEquals(emptyList(), r.typeset, "a cancelled write must not fall back to a text box")
    }

    @Test
    fun `a page that takes neither says so`() = runTest {
        val r = Recorder()
        landing(r, typesets = false) { _, _ -> "refused" }.land("12", box, 13f)
        assertEquals(listOf(AnswerLanding.NOT_PLACED), r.reports)
    }
}
