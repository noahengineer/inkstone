package dev.inkstone.core.calc.value

import dev.inkstone.core.calc.AngleUnit.DEG
import dev.inkstone.core.calc.AngleUnit.RAD
import dev.inkstone.core.calc.NumberFormat
import dev.inkstone.core.calc.num.Dec
import kotlin.test.Test

/**
 * Degrees-minutes-seconds (research 02 §8.9, 04 C-14/C-15), Pol and Rec (02 §10.10, 04 E-33/E-35)
 * and Rnd (02 §10.11, 04 E-44).
 */
class DmsCoordinatesTest {
    private fun parts(p: DmsParts?): String =
        if (p == null) "none" else "${if (p.negative) "-" else ""}${p.degrees}°${p.minutes}'${p.seconds}\""

    private fun polar(r: Res<Polar>): String = when (r) {
        is Res.Err -> r.error.name
        is Res.Ok -> "r=${show(r.value.r)} θ=${show(r.value.theta)}"
    }

    private fun rect(r: Res<Rectangular>): String = when (r) {
        is Res.Err -> r.error.name
        is Res.Ok -> "X=${show(r.value.x)} Y=${show(r.value.y)}"
    }

    private fun dms(d: Long, m: Long, s: Long): Real = Dms.of(int(d), int(m), int(s)).ok()

    private val sexagesimal = listOf(
        Row("C-14 the sum of two DMS values", { parts(Dms.parts(dms(2, 20, 30) + dms(0, 39, 30))) }, "3°0'0\""),
        Row("C-15 the value of 2 15 18", { show(dms(2, 15, 18)) }, "~2.255dms"),
        Row("C-15 2.255 back to DMS", { parts(Dms.parts(dec("2.255"))) }, "2°15'18\""),
        Row("one third of a degree", { parts(Dms.parts(frac(1, 3))) }, "0°20'0\""),
        Row("one third as a decimal", { parts(Dms.parts(dec("0.333333333333333"))) }, "0°19'59.9999999999988\""),
        Row("and rounded for display", { parts(Dms.parts(dec("0.333333333333333"))?.roundedSeconds(2)) }, "0°20'0\""),
        Row("a negative angle", { parts(Dms.parts(dec("-1.5"))) }, "-1°30'0\""),
        Row("seconds keep their decimals", { parts(Dms.parts(dec("0.0001"))) }, "0°0'0.36\""),
        Row("the largest convertible angle", { parts(Dms.parts(dec("9999999.99972222"))) }, "9999999°59'58.999992\""),
        Row("past the conversion range", { parts(Dms.parts(dec("10000000"))) }, "none"),
        Row(
            "rounding carries into minutes and degrees",
            { parts(DmsParts(false, 0, 59, Dec.parse("59.999") ?: Dec.ZERO).roundedSeconds(2)) },
            "1°0'0\"",
        ),
        Row("a negative degrees part signs the whole", { show(Dms.of(int(-2), int(30), null)) }, "~-2.5dms"),
        Row("minutes alone", { show(Dms.of(int(0), int(90), null)) }, "~1.5dms"),
        Row("negative minutes", { show(Dms.of(int(1), int(-1), null)) }, "MATH"),
        Row("marking a value", { show(Dms.marked(int(2))) }, "~2dms"),
    )

    private val coordinates = listOf(
        Row("E-33 Pol root 2 root 2", { polar(Coordinates.pol(rt(2), rt(2), DEG)) }, "r=2 θ=45"),
        Row("E-35 Rec root 2 45", { rect(Coordinates.rec(rt(2), int(45), DEG)) }, "X=1 Y=1"),
        Row("Pol 1 1", { polar(Coordinates.pol(int(1), int(1), DEG)) }, "r=√2 θ=45"),
        Row("Pol 3 4", { polar(Coordinates.pol(int(3), int(4), DEG)) }, "r=5 θ=~53.13010235"),
        Row("Pol minus 1 0", { polar(Coordinates.pol(int(-1), int(0), DEG)) }, "r=1 θ=180"),
        Row("Pol 0 0", { polar(Coordinates.pol(int(0), int(0), DEG)) }, "r=0 θ=0"),
        Row("Pol of huge", { polar(Coordinates.pol(dec("1E60"), dec("1E60"), DEG)) }, "r=~1.414213562E60 θ=~45"),
        Row("Pol in radians", { polar(Coordinates.pol(rt(2), rt(2), RAD)) }, "r=2 θ=~0.7853981634"),
        Row("Rec 2 30", { rect(Coordinates.rec(int(2), int(30), DEG)) }, "X=√3 Y=1"),
        Row("Rec 1 1 radian", { rect(Coordinates.rec(int(1), int(1), RAD)) }, "X=~0.5403023059 Y=~0.8414709848"),
        Row("Rec of a negative r", { rect(Coordinates.rec(int(-1), int(0), DEG)) }, "MATH"),
        Row("Rec past the angle range", { rect(Coordinates.rec(int(1), dec("9E9"), DEG)) }, "MATH"),
    )

    private val rounding = listOf(
        Row(
            "E-44 Rnd 10 over 3 times 3 in Fix 3",
            { show(Coordinates.rnd(int(10) / int(3), NumberFormat.Fix(3)).ok() * int(3)) },
            "~9.999",
        ),
        Row("Rnd one third in Norm", { show(Coordinates.rnd(frac(1, 3), NumberFormat.Norm(2))) }, "~0.3333333333"),
        Row("Rnd two thirds in Sci 2", { show(Coordinates.rnd(frac(2, 3), NumberFormat.Sci(2))) }, "~0.67"),
        Row("Rnd in Sci 0", { show(Coordinates.rnd(dec("123456.789"), NumberFormat.Sci(0))) }, "~123456.789"),
        Row("Rnd in Fix 0", { show(Coordinates.rnd(dec("123456.789"), NumberFormat.Fix(0))) }, "~123457"),
        Row("Rnd half away from zero", { show(Coordinates.rnd(dec("-2.5"), NumberFormat.Fix(0))) }, "~-3"),
    )

    @Test
    fun `DMS values convert both ways and carry when rounded`() = checkRows(sexagesimal)

    @Test
    fun `Pol and Rec are exact on special rays`() = checkRows(coordinates)

    @Test
    fun `Rnd replaces the stored value with the displayed rounding`() = checkRows(rounding)
}
