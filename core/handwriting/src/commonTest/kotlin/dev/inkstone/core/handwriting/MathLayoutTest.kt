package dev.inkstone.core.handwriting

import dev.inkstone.core.model.GeneratedInk
import dev.inkstone.core.model.PenTrajectory
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * docs/HANDWRITING-MATHS.md §3 — the box layout, on pieces whose extents are known exactly: a piece is
 * a box-shaped "letter" [width] wide from [bottom] to [top], in two strokes, so every claim below is a
 * claim about where the layout put ink, not about what any recogniser made of it.
 */
class MathLayoutTest {

    private val hand = MathHand(letterGap = 0.2f, wordGap = 0.7f, penUpMs = 120)

    private fun piece(
        width: Float,
        top: Float = 1f,
        bottom: Float = 0f,
        backend: Char = GeneratedInk.ASSEMBLY,
        pressure: Boolean = true,
    ): MathPiece {
        val builder = PenTrajectory.Builder(8, hasPressure = pressure, hasTilt = false)
        builder.add(0f, bottom, 0, 0.5f)
        builder.add(width, top, 40, 0.6f)
        builder.endStroke()
        builder.add(0f, top, 100, 0.5f)
        builder.add(width, bottom, 140, 0.6f)
        val model = if (backend == GeneratedInk.NEURAL) "a6a191cb" else GeneratedInk.NO_MODEL
        return MathPiece(assertNotNull(builder.build()), backend, model)
    }

    private fun layout(glyphs: (Char) -> PenTrajectory? = { null }) = MathLayout(hand, glyphs, Random(1))

    private fun compose(text: String, vararg pieces: MathPiece, glyphs: (Char) -> PenTrajectory? = { null }): MathInk {
        val tree = assertNotNull(MathNotation.parse(text), "$text is maths")
        return assertNotNull(layout(glyphs).compose(tree, pieces.toList()), "$text composed")
    }

    private class Ext(val minX: Float, val maxX: Float, val minY: Float, val maxY: Float)

    private fun ext(ink: PenTrajectory, strokes: IntRange): Ext {
        var minX = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        for (k in strokes) {
            for (i in ink.strokeStart(k) until ink.strokeEnd(k)) {
                minX = minOf(minX, ink.x[i])
                maxX = maxOf(maxX, ink.x[i])
                minY = minOf(minY, ink.y[i])
                maxY = maxOf(maxY, ink.y[i])
            }
        }
        return Ext(minX, maxX, minY, maxY)
    }

    @Test
    fun `a fraction puts its numerator over a bar on the axis over its denominator`() {
        val maths = compose("a/b", piece(0.6f), piece(1.2f, bottom = -0.4f))
        // Two strokes of numerator, the bar, two of denominator.
        assertEquals(5, maths.ink.strokeCount)
        val numerator = ext(maths.ink, 0..1)
        val bar = ext(maths.ink, 2..2)
        val denominator = ext(maths.ink, 3..4)
        val axis = MathLayout.AXIS
        assertTrue(abs((bar.minY + bar.maxY) / 2 - axis) < 0.06f, "the bar is on the axis: ${bar.minY}..${bar.maxY}")
        assertTrue(numerator.minY > bar.maxY, "the numerator is above the bar")
        assertTrue(denominator.maxY < bar.minY, "the denominator is below it, descender and all")
        assertTrue(bar.minX <= denominator.minX && bar.maxX >= denominator.maxX, "the bar spans the wider part")
        val centre = (bar.minX + bar.maxX) / 2
        assertTrue(abs((numerator.minX + numerator.maxX) / 2 - centre) < 0.01f, "the numerator is centred on it")
        assertEquals(
            GeneratedInk.ASSEMBLY to GeneratedInk.NO_MODEL,
            maths.strokeTags[2],
            "the bar is drawn, and marked",
        )
    }

    @Test
    fun `a root carries its bar over the radicand in the same stroke`() {
        val maths = compose("√x", piece(0.8f))
        assertEquals(3, maths.ink.strokeCount, "the radical is one stroke")
        val sign = ext(maths.ink, 0..0)
        val radicand = ext(maths.ink, 1..2)
        assertTrue(sign.maxY >= radicand.maxY + MathLayout.ROOT_CLEAR * 0.9f, "its bar clears the radicand")
        assertTrue(sign.maxX > radicand.maxX, "and runs past its end")
        assertTrue(sign.minY <= radicand.minY, "its vee reaches the baseline")
    }

    @Test
    fun `a cube root puts a small 3 over the tick and left of the radicand`() {
        // Pieces of different widths, so that which one landed where is visible: the sheet once put the
        // radicand in the notch and the 3 under the bar, and pieces alike could not tell.
        val maths = compose("∛x", piece(0.3f), piece(1.5f))
        val index = ext(maths.ink, 0..1)
        val radicand = ext(maths.ink, 3..4)
        assertTrue(abs((index.maxX - index.minX) - 0.3f * MathLayout.INDEX_SCALE) < 0.01f, "the 3 is in the notch")
        assertTrue(abs((radicand.maxX - radicand.minX) - 1.5f) < 0.01f, "and the radicand under the bar")
        assertTrue(abs((index.maxY - index.minY) - MathLayout.INDEX_SCALE) < 0.01f, "the index is written small")
        assertTrue(index.maxX < radicand.minX, "left of the radicand")
        assertTrue(index.minY > 0.3f, "and raised over the tick")
    }

    @Test
    fun `an exponent is raised and smaller and sits higher over a tall letter`() {
        val short = compose("x^2", piece(0.6f), piece(0.5f))
        val tall = compose("b^2", piece(0.6f, top = 1.7f), piece(0.5f))
        val overX = ext(short.ink, 2..3)
        val overB = ext(tall.ink, 2..3)
        assertTrue(abs((overX.maxY - overX.minY) - MathLayout.SCRIPT_SCALE) < 0.01f, "scaled")
        assertTrue(overX.minY >= MathLayout.SUP_MIN - 0.001f, "raised")
        assertTrue(overB.minY > overX.minY + 0.5f, "higher over a b than over an x")
        val subscript = ext(compose("x_1", piece(0.6f), piece(0.4f)).ink, 2..3)
        assertTrue(subscript.minY < 0f && subscript.maxY < 0.5f, "a subscript is lowered")
    }

    @Test
    fun `brackets round a fraction are stretched to cover it`() {
        val maths = compose("((a)/(b))^2", piece(0.6f), piece(0.6f), piece(0.4f))
        val open = ext(maths.ink, 0..0)
        val inside = ext(maths.ink, 1..5)
        val close = ext(maths.ink, 6..6)
        for (bracket in listOf(open, close)) {
            assertTrue(bracket.minY < inside.minY && bracket.maxY > inside.maxY, "the bracket covers the fraction")
        }
        assertTrue(open.maxX < inside.minX && close.minX > inside.maxX, "and stands either side of it")
    }

    @Test
    fun `marks of the user are used where they exist`() {
        val asked = ArrayList<Char>()
        val wavy = PenTrajectory.Builder(8, false, false).run {
            add(0f, 0.5f, 0)
            add(0.1f, 0.52f, 10)
            add(0.2f, 0.48f, 20)
            add(0.3f, 0.5f, 30)
            build()
        }
        val maths = compose("a/b", piece(0.6f), piece(0.6f)) { mark ->
            asked += mark
            if (mark == '-') wavy else null
        }
        assertEquals(listOf('-'), asked, "the bar is the user's dash")
        val bar = ext(maths.ink, 2..2)
        assertTrue(bar.maxY - bar.minY > 0.03f, "with its wobble")
        assertTrue(bar.maxY - bar.minY <= 2 * MathLayout.MAX_WOBBLE + 0.001f, "kept small")
    }

    @Test
    fun `each stroke says who wrote it and the composite keeps one clock and every channel`() {
        val maths = compose(
            "x/n",
            piece(0.6f, backend = GeneratedInk.NEURAL),
            piece(0.6f, pressure = false),
        )
        assertEquals(
            listOf('n', 'n', 'a', 'a', 'a'),
            maths.strokeTags.map { it.first },
            "the numerator is the network's, the bar drawn, the denominator assembly's",
        )
        val ink = maths.ink
        assertNotNull(ink.pressure, "pressure on every sample, borrowed where a part had none")
        for (i in 1 until ink.pointCount) assertTrue(ink.tMs[i] >= ink.tMs[i - 1], "time never goes back")
    }

    @Test
    fun `a missing piece is not composed`() {
        val tree = assertNotNull(MathNotation.parse("a/b"))
        assertEquals(null, layout().compose(tree, listOf(piece(0.6f))))
    }
}
