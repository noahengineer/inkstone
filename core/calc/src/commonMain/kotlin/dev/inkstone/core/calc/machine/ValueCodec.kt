package dev.inkstone.core.calc.machine

import dev.inkstone.core.calc.num.Dec
import dev.inkstone.core.calc.num.DecMath
import dev.inkstone.core.calc.value.ComplexValue
import dev.inkstone.core.calc.value.DecimalReal
import dev.inkstone.core.calc.value.FractionReal
import dev.inkstone.core.calc.value.MatrixValue
import dev.inkstone.core.calc.value.Real
import dev.inkstone.core.calc.value.SurdReal
import dev.inkstone.core.calc.value.SurdTerm
import dev.inkstone.core.calc.value.Value
import dev.inkstone.core.calc.value.VectorValue

/**
 * Every [Value] as one line of text and back, losslessly: decimals by [Dec.toCanonicalString]
 * (never through a `Double`), fractions and surds by their integers, so a restored value is
 * `==` to the one saved. Words are separated by single spaces and no word contains one:
 *
 * - a real is one word: `F3/4` (fraction), `D0.125` (decimal), `G2.255` (a decimal shown in
 *   degrees-minutes-seconds), `S1/2/3,5/1/2@1.23` (surd terms `num/den/radicand`, then its decimal);
 * - `C <real> <real>` is a complex number;
 * - `M <rows> <cols> <real>…` a matrix, row-major; `V <n> <real>…` a vector.
 *
 * [decode] answers null for anything malformed, including values the constructors would reject
 * (a zero denominator, three surd terms, the wrong number of cells) — it never throws — and values
 * that construct but break an invariant the rest of the engine relies on: a fraction not in lowest
 * terms (`F2/4` would never `==` the `F1/2` arithmetic produces), a `Long.MIN_VALUE` numerator
 * (which no negation survives), a decimal of 1×10¹⁰⁰ or more (outside the calculation range,
 * research 02 §8.2), a matrix outside 1×1…3×3 or a vector outside 2–3 dimensions (research 03 §7,
 * §9 — and a `rows × cols` that overflows an `Int` to the cell count).
 *
 * The same goes for a value past the record that holds it (docs/CALCULATOR.md §3, research 07
 * §4.1), which arithmetic never produces and the value layer relies on never seeing: a fraction of
 * more than 14 digits across numerator and denominator (square roots assume `n·d < 10¹⁴`), and a
 * surd term outside `0 < |c| < 100`, `0 < q < 100`, `0 < r < 1000` (its decimal narrows a term to an
 * `Int`) or not in the one canonical form arithmetic produces — `c/q` reduced, `r` squarefree, the
 * radicands distinct, at least one of them irrational — so that a restored surd `==` a computed one.
 */
internal object ValueCodec {
    fun encode(value: Value): String = when (value) {
        is Real -> real(value)

        is ComplexValue -> "C ${real(value.re)} ${real(value.im)}"

        is MatrixValue -> (listOf("M", value.rows.toString(), value.cols.toString()) + value.cells.map(::real))
            .joinToString(" ")

        is VectorValue -> (listOf("V", value.cells.size.toString()) + value.cells.map(::real)).joinToString(" ")
    }

    fun decode(text: String): Value? {
        val words = text.split(' ')
        return when (words.first()) {
            "C" -> complex(words)
            "M" -> matrix(words)
            "V" -> vector(words)
            else -> if (words.size == 1) real(words.single()) else null
        }
    }

    private fun real(value: Real): String = when (value) {
        is FractionReal -> "F${value.num}/${value.den}"

        is DecimalReal -> (if (value.dms) "G" else "D") + value.dec.toCanonicalString()

        is SurdReal -> "S" + value.terms.joinToString(",") { "${it.num}/${it.den}/${it.radicand}" } +
            "@" + value.dec.toCanonicalString()
    }

    private fun real(word: String): Real? {
        val body = word.drop(1)
        return when (word.firstOrNull()) {
            'F' -> fraction(body)
            'D' -> decimal(body)?.let { DecimalReal(it) }
            'G' -> decimal(body)?.let { DecimalReal(it, dms = true) }
            'S' -> surd(body)
            else -> null
        }
    }

    /** A decimal inside the calculation range; an overflowed one is no value the calculator holds. */
    private fun decimal(text: String): Dec? = Dec.parse(text)?.takeUnless { it.isOverflow }

    private fun fraction(body: String): FractionReal? {
        val parts = body.split('/')
        if (parts.size != 2) return null
        val num = parts[0].toLongOrNull()?.takeIf { it != Long.MIN_VALUE } ?: return null
        val den = parts[1].toLongOrNull() ?: return null
        val reduced = den > 0 && DecMath.gcd(num, den) == 1L
        return if (reduced && digits(num) + digits(den) <= FRACTION_DIGITS) FractionReal(num, den) else null
    }

    private fun surd(body: String): SurdReal? {
        val terms = body.substringBefore('@', "").split(',').map { term(it) ?: return null }
        val dec = decimal(body.substringAfter('@', "")) ?: return null
        val radicands = terms.map { it.radicand }
        val canonical = radicands.distinct().size == radicands.size && radicands.any { it > 1 }
        return if (terms.size in 1..2 && canonical) SurdReal(terms, dec) else null
    }

    private fun term(text: String): SurdTerm? {
        val parts = text.split('/').map { it.toLongOrNull() ?: return null }
        if (parts.size != SURD_PARTS) return null
        val (num, den, radicand) = parts
        val fits = num != 0L && num in SURD_COEFFICIENTS && den in SURD_DENOMINATORS
        val canonical = radicand in SURD_RADICANDS && squarefree(radicand)
        return if (fits && canonical && DecMath.gcd(num, den) == 1L) SurdTerm(num, den, radicand) else null
    }

    /** Decimal digits of [n], which is not `Long.MIN_VALUE`. */
    private fun digits(n: Long): Int = (if (n < 0) -n else n).toString().length

    private fun squarefree(n: Long): Boolean =
        generateSequence(2L) { it + 1 }.takeWhile { it * it <= n }.none { n % (it * it) == 0L }

    private fun complex(words: List<String>): ComplexValue? {
        if (words.size != COMPLEX_WORDS) return null
        val re = real(words[1]) ?: return null
        val im = real(words[2]) ?: return null
        return ComplexValue(re, im)
    }

    private fun matrix(words: List<String>): MatrixValue? {
        val rows = words.getOrNull(1)?.toIntOrNull()?.takeIf { it in MATRIX_SIDE } ?: return null
        val cols = words.getOrNull(2)?.toIntOrNull()?.takeIf { it in MATRIX_SIDE } ?: return null
        val cells = words.drop(MATRIX_HEADER).map { real(it) ?: return null }
        return if (cells.size == rows * cols) MatrixValue(rows, cols, cells) else null
    }

    private fun vector(words: List<String>): VectorValue? {
        val size = words.getOrNull(1)?.toIntOrNull()?.takeIf { it in VECTOR_SIZE } ?: return null
        val cells = words.drop(2).map { real(it) ?: return null }
        return if (cells.size == size) VectorValue(cells) else null
    }

    private const val SURD_PARTS = 3
    private const val COMPLEX_WORDS = 3
    private const val MATRIX_HEADER = 3

    /** Digits a fraction record holds across numerator and denominator (docs/CALCULATOR.md §3). */
    private const val FRACTION_DIGITS = 14

    /** A surd term's `1 ≤ a < 100`, `1 ≤ c < 100`, `1 < b < 1000` (research 02 §8.6), signed. */
    private val SURD_COEFFICIENTS = -99L..99L
    private val SURD_DENOMINATORS = 1L..99L
    private val SURD_RADICANDS = 1L..999L

    /** "Up to 3×3", including the 1×n shapes of the dimension menu's page 2 (research 03 §7). */
    private val MATRIX_SIDE = 1..3

    /** "Two or three dimensions" (research 03 §9). */
    private val VECTOR_SIZE = 2..3
}
