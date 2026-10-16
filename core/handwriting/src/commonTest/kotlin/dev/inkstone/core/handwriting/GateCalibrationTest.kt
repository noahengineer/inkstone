package dev.inkstone.core.handwriting

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * docs/HANDWRITING-SYNTHESIS.md §8.2 — the calibration's *decisions*, over readings written by hand.
 * What ML Kit actually reads back from the user's words is the device's measurement (§20); these hold
 * the arithmetic that turns those readings into a rule.
 */
class GateCalibrationTest {

    @Test
    fun tokensAreClassedByWhatTheyAreMadeOf() {
        assertEquals(TokenClass.WORD, TokenClass.of("sin"))
        assertEquals(TokenClass.WORD, TokenClass.of("d)"))
        assertEquals(TokenClass.NUMBER, TokenClass.of("0.0375"))
        assertEquals(TokenClass.NUMBER, TokenClass.of("-3"))
        assertEquals(TokenClass.NUMBER, TokenClass.of("46%"))
        assertEquals(TokenClass.FORMULA, TokenClass.of("2x+3"))
        assertEquals(TokenClass.FORMULA, TokenClass.of("CaCO3"))
        assertEquals(TokenClass.FORMULA, TokenClass.of("(4.9"))
        assertEquals(TokenClass.FORMULA, TokenClass.of("1.2.3"), "not one number")
        assertEquals(TokenClass.SYMBOL, TokenClass.of("="))
    }

    private fun r(label: String, vararg read: String, vouched: Boolean = true) =
        GateCalibrator.Reading(label, vouched, read.toList())

    @Test
    fun theStrictestRuleTheUsersOwnWordsPassIsChosen() {
        // Words read back in the wrong case one time in four: EXACT passes 75 %, CASE_ALIKE every one.
        val words = List(12) { i -> if (i % 4 == 0) r("sox", "SOX") else r("sox", "sox") }
        val c = GateCalibrator.calibrate(words, 1L).classes.getValue(TokenClass.WORD)
        assertEquals(GateRule.CASE_ALIKE, c.rule)
        assertTrue(c.judgeable)
        assertEquals(1f, c.positive.getValue(GateRule.CASE_ALIKE))
        assertEquals(0.75f, c.positive.getValue(GateRule.EXACT))
    }

    @Test
    fun aNumberReadWithoutItsPointIsNotJudgeableAndNeverGetsPunctuationFolded() {
        // The §18.10 failure in the recogniser's own words: `2.5` read as `25`.
        val numbers = List(10) { r("2.5", "25", "2S") }
        val c = GateCalibrator.calibrate(numbers, 1L).classes.getValue(TokenClass.NUMBER)
        assertFalse(c.judgeable, "the user's own numbers fail every safe rule")
        assertFalse(GateRule.PUNCTUATION in c.positive, "punctuation folding is never offered to a number")
        assertTrue(GateRule.PUNCTUATION.passes("2.5", listOf("25")), "…which is why")
    }

    @Test
    fun aRuleThatPassesWrongLabelsIsNotSafe() {
        // Every reading is the label without its punctuation: PUNCTUATION passes the user's words, and
        // passes `sun` for `sun)` — so the negative control fails for anything that folds it.
        val words = List(12) { r("sun)", "sun") } + List(12) { r("mix)", "mix") }
        val c = GateCalibrator.calibrate(words, 1L).classes.getValue(TokenClass.WORD)
        assertEquals(GateRule.PUNCTUATION, c.rule, "it is the only rule that passes this hand")
        assertTrue(
            (c.negative[GateRule.PUNCTUATION] ?: 0f) >= GateCalibrator.NEGATIVE,
            "a wrong label of similar length still fails",
        )
    }

    @Test
    fun aClassWithTooFewWordsKeepsTheDefault() {
        val cal = GateCalibrator.calibrate(List(GateCalibrator.MIN_SAMPLES - 1) { r("=", "=") }, 1L)
        assertFalse(TokenClass.SYMBOL in cal.classes)
        assertEquals(GateRule.DEFAULT, cal.ruleFor("="))
        assertTrue(cal.judgeable("="), "uncalibrated is judged as before")
    }

    @Test
    fun theControlIsTheVouchedWordsWhenThereAreEnough() {
        // Harvested words passed by construction; the vouched ones are the honest witnesses.
        val harvested = List(20) { r("sun", "sun", vouched = false) }
        val vouched = List(GateCalibrator.MIN_VOUCHED) { r("sun", "sum") }
        val c = GateCalibrator.calibrate(harvested + vouched, 1L).classes.getValue(TokenClass.WORD)
        assertFalse(c.biased)
        assertEquals(0f, c.positive.getValue(GateRule.EXACT), "measured on the vouched words only")
        val few = GateCalibrator.calibrate(harvested + vouched.take(3), 1L).classes.getValue(TokenClass.WORD)
        assertTrue(few.biased, "too few vouched words: measured on all, and marked so")
    }

    @Test
    fun aOneEditLabelDiffersInOneCharacterOfTheSameKind() {
        val rng = Random(4)
        repeat(50) {
            val edited = GateCalibrator.oneEdit("0.0375", rng)
            assertEquals(6, edited.length)
            assertEquals(1, edited.indices.count { edited[it] != "0.0375"[it] })
            assertNotEquals("0.0375", edited)
            assertTrue(edited.count(Char::isDigit) == 5 && '.' in edited, "a digit for a digit: $edited")
        }
    }

    @Test
    fun theCalibrationSurvivesItsJson() {
        val cal = GateCalibrator.calibrate(List(12) { r("sun", "sun") } + List(10) { r("2.5", "25") }, 99L)
        assertEquals(cal, GateCalibration.fromJson(cal.toJson()))
        assertEquals(null, GateCalibration.fromJson(null))
    }

    /**
     * §21.4, in ML Kit's own words: `9.81`'s ink read `[9.81, 0.81, 1.81, 2.81, 981]` on `Test_Tablet`, so
     * *"among the candidates"* passed a wrong digit and a gained point. A token with a digit is judged on
     * its first reading that holds a digit, under every rule; a word is not.
     */
    @Test
    fun aTokenWithADigitIsJudgedOnItsFirstReadingWithADigit() {
        val nineEightyOne = listOf("9.81", "0.81", "1.81", "2.81", "981")
        for (rule in listOf(GateRule.EXACT, GateRule.SPACING, GateRule.CASE_ALIKE)) {
            assertTrue(rule.passes("9.81", nineEightyOne), "$rule: the first reading with a digit")
            assertFalse(rule.passes("0.81", nineEightyOne), "$rule: a wrong digit")
            assertFalse(rule.passes("981", nineEightyOne), "$rule: a lost point")
            assertFalse(rule.passes("25", listOf("2.5", "25")), "$rule: a gained point")
            assertFalse(rule.passes("CaCO3", listOf("CaC03", "CaCO3")), "$rule: a formula's digit")
            assertTrue(rule.passesAmong("0.81", nineEightyOne), "$rule: what passed before")
        }
        // §21.4.2, the user's own hand: readings with no digit are skipped, so a digit read as a letter passes…
        assertTrue(GateRule.CASE_ALIKE.passes("1", listOf("l", "(", "I", "i", "1")))
        assertTrue(GateRule.CASE_ALIKE.passes("5", listOf("s", "S", "5", "g", "9")))
        // …but a reading that does hold a digit must be the label, and one with none at all confirms nothing.
        assertFalse(GateRule.CASE_ALIKE.passes("x-3", listOf("7-3", "1-3", "X-3")), "an x read as a 7")
        assertFalse(GateRule.CASE_ALIKE.passes("-4ac", listOf("_4ac", "-4ac")), "a minus read as an underscore")
        assertFalse(GateRule.CASE_ALIKE.passes("2", listOf("z", "Z")), "no reading holds a digit")
        assertEquals(listOf("5"), GateRule.digitReading(listOf("s", "S", "5", "9")))
        assertEquals(emptyList<String>(), GateRule.digitReading(listOf("s", "S")))
        // Spaces still do not count, and a formula's letters keep their rule.
        assertTrue(GateRule.SPACING.passes("9.81", listOf("9 .81", "0.81")))
        assertTrue(GateRule.CASE_ALIKE.passes("CaCO3", listOf("Ca CO3")))
        assertTrue(GateRule.CASE_ALIKE.passes("2x+3", listOf("2X+3")), "x is case-alike")
        // A word may still be any of the five.
        assertTrue(GateRule.CASE_ALIKE.passes("sun", listOf("sum", "sun")))
        assertTrue(GateRule.PUNCTUATION.passes("sun)", listOf("sum", "sun")))
        assertTrue(GateRule.digitBearing("46%") && GateRule.digitBearing("2x+3") && GateRule.digitBearing("(4.9"))
        assertFalse(GateRule.digitBearing("sin") || GateRule.digitBearing("=") || GateRule.digitBearing("d)"))
    }

    @Test
    fun numbersAreCalibratedOnTheTopReadingAndTheCostIsReported() {
        // A quarter of this hand's numbers read right only in second place.
        val numbers = List(12) { i -> if (i % 4 == 0) r("9.81", "0.81", "9.81") else r("9.81", "9.81", "0.81") }
        val words = List(12) { i -> if (i % 4 == 0) r("sun", "sum", "sun") else r("sun", "sun") }
        val cal = GateCalibrator.calibrate(numbers + words, 1L)

        val n = cal.classes.getValue(TokenClass.NUMBER)
        assertEquals(0.75f, n.positive.getValue(GateRule.CASE_ALIKE), "measured as the gate reads a number")
        assertEquals(1f, n.positiveAmongCandidates.getValue(GateRule.CASE_ALIKE), "what top-5 would have passed")
        assertFalse(n.judgeable, "0.75 is under the floor: numbers go to verified letters (§8.2)")
        assertFalse(cal.ruleFor("0.81").passes("0.81", numbers[1].readings), "second place is not enough")

        val w = cal.classes.getValue(TokenClass.WORD)
        assertEquals(1f, w.positive.getValue(GateRule.CASE_ALIKE), "a word is still any of the five")
        assertEquals(w.positive, w.positiveAmongCandidates)
        assertEquals(GateCalibration.RULE_VERSION, cal.ruleVersion)
    }

    @Test
    fun aCalibrationWrittenBeforeTheRuleChangedSaysSo() {
        val cal = GateCalibrator.calibrate(List(12) { r("sun", "sun") } + List(10) { r("2.5", "2.5") }, 99L)
        val h5 = JsonObject(
            cal.toJson().toMutableMap().apply {
                remove("ruleVersion")
                this["classes"] = JsonArray(
                    getValue("classes").jsonArray.map { c -> JsonObject(c.jsonObject - "positiveAmongCandidates") },
                )
            },
        )
        val read = assertNotNull(GateCalibration.fromJson(h5))
        assertEquals(1, read.ruleVersion, "H5 wrote no version: its rates were measured over all five")
        assertTrue(read.classes.values.all { it.positiveAmongCandidates.isEmpty() })
        assertEquals(cal.ruleFor("2.5"), read.ruleFor("2.5"), "its rules still apply until it is measured again")
    }
}
