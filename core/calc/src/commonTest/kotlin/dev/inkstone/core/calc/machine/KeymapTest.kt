package dev.inkstone.core.calc.machine

import dev.inkstone.core.calc.Key
import dev.inkstone.core.calc.Mode
import dev.inkstone.core.calc.input.TemplateKind
import dev.inkstone.core.calc.input.Tok
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Every legend printed on or above a key (research 01 §5.2–5.4, 06 §4) resolves to what the guide
 * says it does, on its layer and in its mode. The tables below are the printed face, written out
 * independently of [Keymap]'s own tables.
 */
class KeymapTest {
    private fun t(tok: Tok): Action = Action.Type(tok)

    private fun o(kind: TemplateKind): Action = Action.Open(kind)

    private fun c(command: Command): Action = Action.Do(command)

    private val shiftLegends: Map<Key, Action> = mapOf(
        Key.MODE to c(Command.SETUP), Key.CALC to c(Command.SOLVE), Key.INTEGRAL to o(TemplateKind.DERIVATIVE),
        Key.RECIPROCAL to t(Tok.FACTORIAL), Key.LOG_BASE to o(TemplateKind.SUMMATION),
        Key.FRACTION to o(TemplateKind.MIXED_FRACTION), Key.SQRT to o(TemplateKind.CBRT), Key.SQUARE to t(Tok.CUBE),
        Key.POWER to o(TemplateKind.NTH_ROOT), Key.LOG to o(TemplateKind.TEN_POWER), Key.LN to o(TemplateKind.E_POWER),
        Key.DMS to c(Command.FACT), Key.HYP to o(TemplateKind.ABS), Key.SIN to t(Tok.ASIN), Key.COS to t(Tok.ACOS),
        Key.TAN to t(Tok.ATAN), Key.RCL to c(Command.STO), Key.ENG to c(Command.ENG_LEFT),
        Key.OPEN_PAREN to t(Tok.PERCENT), Key.CLOSE_PAREN to t(Tok.SEMICOLON), Key.S_TO_D to c(Command.MIXED),
        Key.M_PLUS to c(Command.M_MINUS), Key.D7 to c(Command.CONST), Key.D8 to c(Command.CONV),
        Key.D9 to c(Command.CLR), Key.DEL to c(Command.INS), Key.AC to c(Command.OFF), Key.MULTIPLY to t(Tok.NPR),
        Key.DIVIDE to t(Tok.NCR), Key.PLUS to t(Tok.POL), Key.MINUS to t(Tok.REC), Key.D0 to t(Tok.RND),
        Key.DECIMAL to t(Tok.RAN), Key.EXP to t(Tok.PI), Key.ANS to c(Command.DRG),
        Key.EQUALS to c(Command.EQUALS_DECIMAL),
    )

    private val alphaLegends: Map<Key, Action> = mapOf(
        Key.CALC to t(Tok.EQUALS), Key.INTEGRAL to t(Tok.COLON), Key.FRACTION to t(Tok.DIV_R),
        Key.NEGATIVE to t(Tok.VAR_A), Key.DMS to t(Tok.VAR_B), Key.HYP to t(Tok.VAR_C), Key.SIN to t(Tok.VAR_D),
        Key.COS to t(Tok.VAR_E), Key.TAN to t(Tok.VAR_F), Key.CLOSE_PAREN to t(Tok.VAR_X), Key.S_TO_D to t(Tok.VAR_Y),
        Key.M_PLUS to t(Tok.VAR_M), Key.MULTIPLY to t(Tok.GCD), Key.DIVIDE to t(Tok.LCM),
        Key.DECIMAL to t(Tok.RAN_INT), Key.EXP to t(Tok.E), Key.ANS to t(Tok.PRE_ANS),
    )

    @Test
    fun everyGoldShiftLegendResolvesInComp() {
        for ((key, action) in shiftLegends) assertEquals(action, Keymap.resolve(key, Layer.SHIFT, Mode.COMP), "$key")
    }

    @Test
    fun everyMagentaAlphaLegendResolvesInComp() {
        for ((key, action) in alphaLegends) assertEquals(action, Keymap.resolve(key, Layer.ALPHA, Mode.COMP), "$key")
    }

    @Test
    fun keyCapsTypeTheirTokensAndTemplates() {
        assertEquals(o(TemplateKind.FRACTION), Keymap.resolve(Key.FRACTION, Layer.NONE, Mode.COMP))
        assertEquals(o(TemplateKind.LOG_BASE), Keymap.resolve(Key.LOG_BASE, Layer.NONE, Mode.COMP))
        assertEquals(t(Tok.EXP10), Keymap.resolve(Key.EXP, Layer.NONE, Mode.COMP))
        assertEquals(t(Tok.NEGATIVE), Keymap.resolve(Key.NEGATIVE, Layer.NONE, Mode.COMP))
        assertEquals(c(Command.HYP), Keymap.resolve(Key.HYP, Layer.NONE, Mode.COMP))
        assertEquals(t(Tok.D7), Keymap.resolve(Key.D7, Layer.NONE, Mode.COMP))
        assertEquals(c(Command.ENG), Keymap.resolve(Key.ENG, Layer.NONE, Mode.COMP))
    }

    @Test
    fun keysWithoutALegendDoNothing() {
        assertEquals(Action.None, Keymap.resolve(Key.D6, Layer.SHIFT, Mode.COMP))
        assertEquals(Action.None, Keymap.resolve(Key.D6, Layer.ALPHA, Mode.COMP))
        assertEquals(Action.None, Keymap.resolve(Key.LEFT, Layer.SHIFT, Mode.COMP))
        assertEquals(Action.None, Keymap.resolve(Key.SHIFT, Layer.NONE, Mode.COMP))
    }

    @Test
    fun compOnlyLegendsAreDeadElsewhere() {
        assertEquals(Action.None, Keymap.resolve(Key.FRACTION, Layer.ALPHA, Mode.CMPLX))
        assertEquals(Action.None, Keymap.resolve(Key.ANS, Layer.ALPHA, Mode.BASE_N))
    }

    @Test
    fun cmplxTypesIAndAngleAndOpensItsMenu() {
        assertEquals(t(Tok.IMAGINARY), Keymap.resolve(Key.ENG, Layer.NONE, Mode.CMPLX))
        assertEquals(t(Tok.ANGLE), Keymap.resolve(Key.NEGATIVE, Layer.SHIFT, Mode.CMPLX))
        assertEquals(c(Command.MODE_MENU), Keymap.resolve(Key.D2, Layer.SHIFT, Mode.CMPLX))
        assertEquals(Action.None, Keymap.resolve(Key.NEGATIVE, Layer.SHIFT, Mode.COMP))
        assertEquals(Action.None, Keymap.resolve(Key.D2, Layer.SHIFT, Mode.COMP))
    }

    @Test
    fun baseNKeysSwitchBaseAndTypeHexDigitsWithoutAlpha() {
        val expected = mapOf(
            Key.SQUARE to c(Command.BASE_DEC), Key.POWER to c(Command.BASE_HEX), Key.LOG to c(Command.BASE_BIN),
            Key.LN to c(Command.BASE_OCT), Key.NEGATIVE to t(Tok.HEX_A), Key.DMS to t(Tok.HEX_B),
            Key.HYP to t(Tok.HEX_C), Key.SIN to t(Tok.HEX_D), Key.COS to t(Tok.HEX_E), Key.TAN to t(Tok.HEX_F),
        )
        for ((key, action) in expected) assertEquals(action, Keymap.resolve(key, Layer.NONE, Mode.BASE_N), "$key")
        assertEquals(t(Tok.VAR_A), Keymap.resolve(Key.NEGATIVE, Layer.ALPHA, Mode.BASE_N))
        assertEquals(c(Command.MODE_MENU), Keymap.resolve(Key.D3, Layer.SHIFT, Mode.BASE_N))
        assertEquals(Action.None, Keymap.resolve(Key.D7, Layer.SHIFT, Mode.BASE_N))
        assertEquals(Action.None, Keymap.resolve(Key.D8, Layer.SHIFT, Mode.BASE_N))
        assertEquals(Action.None, Keymap.resolve(Key.DECIMAL, Layer.NONE, Mode.BASE_N))
    }

    @Test
    fun eachModeMenuLegendWorksOnlyInItsMode() {
        val menus = mapOf(
            Key.D1 to setOf(Mode.STAT, Mode.DIST),
            Key.D2 to setOf(Mode.CMPLX),
            Key.D3 to setOf(Mode.BASE_N),
            Key.D4 to setOf(Mode.MATRIX),
            Key.D5 to setOf(Mode.VECTOR),
        )
        for ((key, modes) in menus) {
            for (mode in Mode.entries) {
                val expected = if (mode in modes) c(Command.MODE_MENU) else Action.None
                assertEquals(expected, Keymap.resolve(key, Layer.SHIFT, mode), "$key in $mode")
            }
        }
    }

    @Test
    fun convIsRefusedInTableButConstIsNot() {
        assertEquals(Action.None, Keymap.resolve(Key.D8, Layer.SHIFT, Mode.TABLE))
        assertEquals(c(Command.CONST), Keymap.resolve(Key.D7, Layer.SHIFT, Mode.TABLE))
    }

    @Test
    fun aControllersOverlayWinsOverEveryTable() {
        val overlay = mapOf(Binding(Layer.NONE, Key.SIN) to Action.None)
        assertEquals(Action.None, Keymap.resolve(Key.SIN, Layer.NONE, Mode.COMP, overlay))
        assertEquals(t(Tok.SIN), Keymap.resolve(Key.SIN, Layer.NONE, Mode.COMP))
    }

    @Test
    fun theStoVariableKeysNeedNoAlpha() {
        assertEquals(9, VARIABLE_KEYS.size)
        for ((key, variable) in VARIABLE_KEYS) {
            val alpha = Keymap.resolve(key, Layer.ALPHA, Mode.COMP) as Action.Type
            assertEquals(variable.name, alpha.tok.text, "$key")
        }
    }
}
