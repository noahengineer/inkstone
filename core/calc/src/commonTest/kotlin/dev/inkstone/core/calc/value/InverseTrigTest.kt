package dev.inkstone.core.calc.value

import dev.inkstone.core.calc.AngleUnit.DEG
import dev.inkstone.core.calc.AngleUnit.GRA
import dev.inkstone.core.calc.AngleUnit.RAD
import dev.inkstone.core.calc.num.Dec
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Inverse trig into the current unit, the exact point angle behind Pol and arg, and the DRG▶
 * conversions (research 02 §10.4, 04 E-02, E-05, E-06). An exact table value gives the exact
 * angle; anything else is computed in radians at working precision and converted once.
 */
class InverseTrigTest {
    private val inverses = listOf(
        Row("E-02 asin one half", { show(Trig.asin(frac(1, 2), DEG)) }, "30"),
        Row("asin decimal 0.5 is exactly 30", { show(Trig.asin(dec("0.5"), DEG)) }, "~30"),
        Row("asin 1", { show(Trig.asin(int(1), DEG)) }, "90"),
        Row("asin minus root 3 over 2", { show(Trig.asin(-(rt(3) / int(2)), DEG)) }, "-60"),
        Row("asin root 6 minus root 2 over 4", { show(Trig.asin((rt(6) - rt(2)) / int(4), DEG)) }, "15"),
        Row("asin root 2 over 2 in grads", { show(Trig.asin(rt(2) / int(2), GRA)) }, "50"),
        Row("asin one half in grads", { show(Trig.asin(frac(1, 2), GRA)) }, "100/3"),
        Row("asin one half in radians", { show(Trig.asin(frac(1, 2), RAD)) }, "~0.5235987756"),
        Row("asin 2", { show(Trig.asin(int(2), DEG)) }, "MATH"),
        Row("acos 0 in radians", { show(Trig.acos(int(0), RAD)) }, "~1.570796327"),
        Row("acos minus one half", { show(Trig.acos(frac(-1, 2), DEG)) }, "120"),
        Row("acos minus 1", { show(Trig.acos(int(-1), DEG)) }, "180"),
        Row("acos root 3 over 2", { show(Trig.acos(rt(3) / int(2), DEG)) }, "30"),
        Row("acos decimal 0.3", { show(Trig.acos(dec("0.3"), DEG)) }, "~72.54239688"),
        Row("acos 1.5", { show(Trig.acos(dec("1.5"), DEG)) }, "MATH"),
        Row("atan 1", { show(Trig.atan(int(1), DEG)) }, "45"),
        Row("atan root 3", { show(Trig.atan(rt(3), DEG)) }, "60"),
        Row("atan minus 1", { show(Trig.atan(int(-1), DEG)) }, "-45"),
        Row("atan 2 plus root 3", { show(Trig.atan(int(2) + rt(3), DEG)) }, "75"),
        Row("atan 2 minus root 3", { show(Trig.atan(int(2) - rt(3), DEG)) }, "15"),
        Row("atan of 1E99", { show(Trig.atan(dec("1E99"), DEG)) }, "~90"),
        Row("atan decimal 1", { show(Trig.atan(dec("1"), DEG)) }, "~45"),
        Row("atan 1 in grads", { show(Trig.atan(int(1), GRA)) }, "50"),
    )

    private val points = listOf(
        Row("point root 2 root 2", { show(Trig.atan2(rt(2), rt(2), DEG)) }, "45"),
        Row("point minus 1 1", { show(Trig.atan2(int(1), int(-1), DEG)) }, "135"),
        Row("point minus 1 minus 1", { show(Trig.atan2(int(-1), int(-1), DEG)) }, "-135"),
        Row("point minus 1 0", { show(Trig.atan2(int(0), int(-1), DEG)) }, "180"),
        Row("point 0 minus 1", { show(Trig.atan2(int(-1), int(0), DEG)) }, "-90"),
        Row("point 1 root 3", { show(Trig.atan2(rt(3), int(1), DEG)) }, "60"),
        Row("point minus root 3 1", { show(Trig.atan2(int(1), -rt(3), DEG)) }, "150"),
        Row("the origin", { show(Trig.atan2(int(0), int(0), DEG)) }, "0"),
        Row("point 3 4", { show(Trig.atan2(int(4), int(3), DEG)) }, "~53.13010235"),
        Row("point minus 1 0 in radians", { show(Trig.atan2(int(0), int(-1), RAD)) }, "~3.141592654"),
    )

    private val conversions = listOf(
        Row("E-05", { show(Angles.convert(DecimalReal(Dec.PI) / int(2), RAD, DEG)) }, "~90"),
        Row("E-06 50 grads in degrees", { show(Angles.convert(int(50), GRA, DEG)) }, "45"),
        Row("90 degrees in grads", { show(Angles.convert(int(90), DEG, GRA)) }, "100"),
        Row("1 degree in grads", { show(Angles.convert(int(1), DEG, GRA)) }, "10/9"),
        Row("90 degrees in radians", { show(Angles.convert(int(90), DEG, RAD)) }, "~1.570796327"),
        Row("100 grads in radians", { show(Angles.convert(int(100), GRA, RAD)) }, "~1.570796327"),
        Row("1 radian in grads", { show(Angles.convert(int(1), RAD, GRA)) }, "~63.66197724"),
        Row("same unit is untouched", { show(Angles.convert(rt(2), RAD, RAD)) }, "√2"),
        // Review regression: the same-unit shortcut returned a DMS value still flagged, contradicting
        // "converting an angle leaves the DMS display behind" in Angles.
        Row("DMS to plain in the same unit", { show(Angles.convert(Dms.marked(int(30)).ok(), DEG, DEG)) }, "~30"),
        Row("DMS to plain", { show(Angles.convert(Dms.of(int(90), null, null).ok(), DEG, GRA)) }, "~100"),
    )

    @Test
    fun `inverse trig answers in the unit and exactly for table values`() = checkRows(inverses)

    @Test
    fun `a point on a fifteen degree ray has an exact angle`() = checkRows(points)

    @Test
    fun `angle marks convert with Casio pi and grads stay exact`() = checkRows(conversions)

    @Test
    fun `pi over 2 radians is exactly 90 degrees so its sine is special again`() {
        val ninety = Angles.convert(DecimalReal(Dec.PI) / int(2), RAD, DEG).ok()
        assertEquals("90", ninety.dec.toString())
        assertEquals("1", show(Trig.sin(ninety, DEG)))
    }

    @Test
    fun `asin of a decimal half in radians is the true pi over 6 to fifteen digits`() {
        assertEquals("0.523598775598299", Trig.asin(dec("0.5"), RAD).ok().dec.toString())
        // The exact table answer uses Casio's pi, so it equals what typing pi divided by 6 gives.
        assertEquals((Dec.PI / Dec.of(6)).toString(), Trig.asin(frac(1, 2), RAD).ok().dec.toString())
    }
}
