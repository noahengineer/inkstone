package dev.inkstone.core.calc.machine

import dev.inkstone.core.calc.num.Dec
import dev.inkstone.core.calc.value.ComplexValue
import dev.inkstone.core.calc.value.DecimalReal
import dev.inkstone.core.calc.value.FractionReal
import dev.inkstone.core.calc.value.MatrixValue
import dev.inkstone.core.calc.value.SurdReal
import dev.inkstone.core.calc.value.SurdTerm
import dev.inkstone.core.calc.value.Value
import dev.inkstone.core.calc.value.VectorValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Values written for persistence come back `==`, and malformed text never throws. */
class ValueCodecTest {
    private fun dec(text: String): Dec = Dec.parse(text) ?: error("test value $text")

    @Test
    fun everyKindOfValueRoundTrips() {
        val samples: List<Value> = listOf(
            FractionReal(0, 1),
            FractionReal(-123456789, 1000),
            DecimalReal(dec("3.1415926535898")),
            DecimalReal(dec("-9.99999999999999E99")),
            DecimalReal(dec("1E-99"), dms = true),
            SurdReal(listOf(SurdTerm(3, 2, 5)), dec("3.35410196624968")),
            SurdReal(listOf(SurdTerm(-1, 1, 1), SurdTerm(2, 3, 7)), dec("0.763834")),
            ComplexValue(FractionReal(1, 2), DecimalReal(dec("-0.5"))),
            MatrixValue(2, 3, List(6) { FractionReal(it.toLong(), 1) }),
            VectorValue(listOf(DecimalReal(dec("1.5")), FractionReal(-2, 7))),
        )
        for (value in samples) {
            val text = ValueCodec.encode(value)
            assertEquals(value, ValueCodec.decode(text), text)
        }
    }

    @Test
    fun malformedTextIsNullNotAThrow() {
        val bad = listOf(
            "", " ", "X1", "F1", "F1/0", "F1/-2", "F1/2/3", "Fa/b", "D", "D1,5", "G--1", "S1/2/3", "S@1",
            "S1/2/3,1/2/3,1/2/3@1", "S1/2@1", "C F1/1", "C F1/1 F1/1 F1/1", "C X1 F1/1", "M 2 2 F1/1",
            "M 0 0", "M a 1 F1/1", "V 3 F1/1 F2/1", "V 0", "M 1 1 C", "F1/1 F2/1",
        )
        for (text in bad) assertNull(ValueCodec.decode(text), "<$text>")
    }
}
