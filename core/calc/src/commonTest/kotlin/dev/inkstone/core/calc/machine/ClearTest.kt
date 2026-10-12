package dev.inkstone.core.calc.machine

import dev.inkstone.core.calc.AngleUnit
import dev.inkstone.core.calc.Mode
import dev.inkstone.core.calc.Setup
import dev.inkstone.core.calc.eval.Memory
import dev.inkstone.core.calc.parse.Slot
import dev.inkstone.core.calc.parse.Variable
import dev.inkstone.core.calc.value.FractionReal
import dev.inkstone.core.calc.value.MatrixValue
import dev.inkstone.core.calc.value.VectorValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** CLR, `SHIFT 9` (research 02 §5.3): the menu, each confirmation, each completion, each reset. */
class ClearTest {
    /** A calculator with memory, SETUP and mode all moved away from their defaults. */
    private fun used(): Session {
        val s = Session().press("5 STO (−) 3 STO M+ 9 = 4 = SETUP 4 SETUP ▼ 7 ◀ ◀ AC MODE 2")
        s.machine.memory.matrices[Slot.A] = MatrixValue(1, 1, listOf(FractionReal(1, 1)))
        s.machine.memory.vectors[Slot.B] = VectorValue(listOf(FractionReal(1, 1), FractionReal(2, 1)))
        return s
    }

    @Test
    fun theMenuOffersThreeResets() {
        assertEquals("Clear?\n1:Setup  2:Memory\n3:All", Session().press("CLR").text)
    }

    @Test
    fun clearMemoryConfirmsThenClearsEveryMemory() {
        val s = used().press("CLR 2")
        assertEquals("Clear Memory?\n[=]   :Yes\n[AC]  :Cancel", s.text)
        s.press("=")
        assertEquals("Complete!\nPress [AC] key", s.text)
        val memory = s.machine.memory
        assertTrue(Variable.entries.all { memory.variables[it] == Memory.ZERO })
        assertEquals(Memory.ZERO, memory.ans)
        assertEquals(Memory.ZERO, memory.preAns)
        assertNull(memory.matrices[Slot.A])
        assertNull(memory.vectors[Slot.B])
        assertEquals(Mode.CMPLX, s.machine.mode)
        assertEquals(AngleUnit.RAD, s.machine.setup.angle)
    }

    @Test
    fun theCompletionNoticeWaitsForAcAlone() {
        val s = used().press("CLR 2 = MODE 5 + SETUP")
        assertEquals("Complete!\nPress [AC] key", s.text)
        s.press("AC")
        assertEquals("", s.text)
        assertEquals(Mode.CMPLX, s.machine.mode)
    }

    @Test
    fun clearSetupResetsModeAndSetupButKeepsMemoryAndContrast() {
        val s = used().press("CLR 1")
        assertEquals("Clear Setup?\n[=]   :Yes\n[AC]  :Cancel", s.text)
        s.press("=")
        assertEquals("Complete!\nPress [AC] key", s.text)
        assertEquals(Mode.COMP, s.machine.mode)
        assertEquals(Setup(contrast = Setup.DEFAULT_CONTRAST - 2), s.machine.setup)
        assertEquals(FractionReal(5, 1), s.machine.memory.variables[Variable.A])
        assertEquals(FractionReal(4, 1), s.machine.memory.ans)
        s.press("AC")
        assertEquals("", s.text)
    }

    @Test
    fun resetAllClearsEverythingButTheContrast() {
        val s = used().press("CLR 3")
        assertEquals("Reset All?\n[=]   :Yes\n[AC]  :Cancel", s.text)
        s.press("=")
        assertEquals("Reset All\nPress [AC] key", s.text)
        assertEquals(Mode.COMP, s.machine.mode)
        assertEquals(Setup(contrast = Setup.DEFAULT_CONTRAST - 2), s.machine.setup)
        assertEquals(Memory.ZERO, s.machine.memory.variables[Variable.A])
        assertEquals(Memory.ZERO, s.machine.memory.variables[Variable.M])
        assertEquals(Memory.ZERO, s.machine.memory.ans)
    }

    @Test
    fun acOnTheConfirmationCancelsAndChangesNothing() {
        val s = Session().press("5 STO (−) 1 + 1 CLR 3 AC")
        assertEquals("1+1", s.text)
        assertEquals(FractionReal(5, 1), s.machine.memory.variables[Variable.A])
    }

    @Test
    fun clearSetupInCompKeepsPreAnsButClearsHistory() {
        val s = Session().press("1 = 2 = CLR 1 = AC")
        assertEquals(FractionReal(1, 1), s.machine.memory.preAns)
        s.press("▲")
        assertEquals("", s.text)
    }
}
