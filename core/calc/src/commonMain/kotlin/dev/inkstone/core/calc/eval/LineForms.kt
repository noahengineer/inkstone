package dev.inkstone.core.calc.eval

import dev.inkstone.core.calc.value.ComplexValue
import dev.inkstone.core.calc.value.DecimalReal
import dev.inkstone.core.calc.value.FractionReal
import dev.inkstone.core.calc.value.MatrixValue
import dev.inkstone.core.calc.value.Real
import dev.inkstone.core.calc.value.SurdReal
import dev.inkstone.core.calc.value.Value
import dev.inkstone.core.calc.value.VectorValue

/**
 * What linear output can show exactly: whole numbers, and fractions typed with `⌟`. A surd has no
 * linear form at all, and a non-integer fraction that a division or a function produced is shown
 * as a decimal (research 02 §8.5, §8.8). See [Session.line] for where this applies.
 */
internal object LineForms {
    fun real(x: Real): Real = when {
        x is SurdReal -> DecimalReal(x.dec)
        x is FractionReal && x.den != 1L -> DecimalReal(x.dec)
        else -> x
    }

    fun of(value: Value): Value = when (value) {
        is Real -> real(value)
        is ComplexValue -> ComplexValue(real(value.re), real(value.im))
        is MatrixValue -> MatrixValue(value.rows, value.cols, value.cells.map(::real))
        is VectorValue -> VectorValue(value.cells.map(::real))
    }

    /**
     * A fraction that is not a whole number. In linear output the only such values are typed
     * fractions and what arithmetic made of them — every other producer gave a decimal — so this
     * is how "a fraction took part" can be told without carrying provenance.
     */
    fun isFraction(value: Value): Boolean = when (value) {
        is FractionReal -> value.den != 1L
        is ComplexValue -> isFraction(value.re) || isFraction(value.im)
        else -> false
    }
}
