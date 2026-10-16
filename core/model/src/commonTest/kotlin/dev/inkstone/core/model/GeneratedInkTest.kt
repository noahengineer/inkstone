package dev.inkstone.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** SPEC §4.10 — the `hw` format key and the phrase record on a phrase's first stroke. */
class GeneratedInkTest {

    private val group = "01a2b3c4-0000-7000-8000-000000000001"

    @Test
    fun `the tag round trips through a format record and older builds keep it`() {
        val tag = GeneratedInk(group, GeneratedInk.ASSEMBLY)
        val record = tag.applyTo(ObjectFormat(knockout = true)).encode()

        assertEquals("if1;ko=1;hw=$group:a:-", record)
        assertEquals(tag, GeneratedInk.ofRecord(record))
        // An unknown key is kept verbatim by ObjectFormat, which is how a build without `hw` keeps it.
        assertEquals(record, ObjectFormat.decode(record).encode())
    }

    @Test
    fun `a value this build did not write is not a tag`() {
        for (value in listOf("", "g:a", "g:x:-", "g:a:ABC", "g:a:-:extra", ":a:-", "g;x:a:-")) {
            assertNull(GeneratedInk.decode(value), value)
        }
        assertNotNull(GeneratedInk.decode("g:n:0123abcd"))
    }

    @Test
    fun `a phrase moved is still one phrase and a duplicate is a second`() {
        val tag = GeneratedInk(group, GeneratedInk.ASSEMBLY)
        val moved = Matrix3.translate(10f, 20f)

        assertEquals(tag.copyKey(moved), tag.copyKey(Matrix3.translate(10.001f, 20f)))
        assertNotEquals(tag.copyKey(Matrix3.Identity), tag.copyKey(moved))
    }

    @Test
    fun `the phrase record keeps the words exactly and the placement to three places`() {
        val placement = PhrasePlacement(120.5f, 304f, 5.2f, 0.21f, -0.01f, 320f, 17.5f, 0.5f)
        val text = "Remember the milk;\nand = eggs"
        val record = PhraseRecord(text, placement)

        val decoded = assertNotNull(PhraseRecord.decode(record.encode()))
        assertEquals(text, decoded.text)
        assertEquals(placement, decoded.placement)
    }

    @Test
    fun `unknown header keys survive and a broken placement is dropped whole`() {
        val decoded = assertNotNull(PhraseRecord.decode("hw1;x=1;y=2;xh=0;sl=0;an=0;w=9;p=9;n=0.5;zz=7\nhello"))

        assertEquals("hello", decoded.text)
        assertNull(decoded.placement, "an x-height of zero is not a placement")
        assertEquals(mapOf("zz" to "7"), decoded.extras)
        assertEquals("hw1;zz=7\nhello", decoded.encode())
    }

    @Test
    fun `anything else in the content register is not a phrase`() {
        assertNull(PhraseRecord.decode("is1;k=rect"))
        assertNull(PhraseRecord.decode("just some text"))
        assertNull(PhraseRecord.decode(null))
    }

    @Test
    fun `the placement follows the phrase when it is moved or scaled`() {
        val record = PhraseRecord("hi", PhrasePlacement(100f, 200f, 5f, 0.1f, 0f, 300f, 16f, 0.5f))
        val placement = assertNotNull(record.placementOn(Matrix3.translate(10f, -5f) * Matrix3.scale(2f)))

        assertEquals(210f, placement.originX, 1e-3f)
        assertEquals(395f, placement.originY, 1e-3f)
        assertEquals(10f, placement.xHeightPt, 1e-3f)
        assertEquals(600f, placement.wrapWidthPt, 1e-3f)
    }
}
