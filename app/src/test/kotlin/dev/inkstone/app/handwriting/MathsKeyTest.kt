package dev.inkstone.app.handwriting

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import dev.inkstone.core.handwriting.QuickStart
import org.junit.Assert.assertEquals
import org.junit.Test

/** docs/HANDWRITING-MATHS.md §2.2 — the *Maths* keys type a template at the cursor. */
class MathsKeyTest {

    private fun key(label: String) = MathsKey.ROWS.flatten().single { it.label == label }

    @Test
    fun `a key types its template at the cursor and leaves the cursor in its first slot`() {
        val typed = key("a/b").insertInto(TextFieldValue("y = ", TextRange(4)))
        assertEquals("y = ()/()", typed.text)
        assertEquals(TextRange(5), typed.selection)
    }

    @Test
    fun `a key wraps what is selected and puts the cursor after it`() {
        val wrapped = key("√").insertInto(TextFieldValue("2x+1", TextRange(0, 4)))
        assertEquals("√(2x+1)", wrapped.text)
        assertEquals(TextRange(6), wrapped.selection)
    }

    @Test
    fun `the nth root asks for its index first`() {
        val typed = key("ⁿ√").insertInto(TextFieldValue(""))
        assertEquals("root(, )", typed.text)
        assertEquals(TextRange(5), typed.selection)
    }

    @Test
    fun `every key is one of the calculators templates or a bracket`() {
        assertEquals(listOf("a/b", "√", "∛", "ⁿ√", "xⁿ", "x₁", "( )", "|x|"), MathsKey.ROWS.flatten().map { it.label })
    }

    /** §2.4: the quick start's symbols, each typed as itself at the cursor. */
    @Test
    fun `the symbol keys are the quick starts symbols and type themselves`() {
        val symbols = MathsKey.SYMBOL_ROWS.flatten()
        // √ is a template key already.
        val prompt = QuickStart.PROMPTS.last().split(' ') - "√"
        assertEquals(prompt.toSet(), symbols.map { it.label }.toSet())
        val typed = symbols.first { it.label == "≤" }.insertInto(TextFieldValue("x3", TextRange(1)))
        assertEquals("x≤3", typed.text)
        assertEquals(TextRange(2), typed.selection)
    }

    /** §2.4: a symbol is never read back, and the ready line must not say it was. */
    @Test
    fun `the ready line says which words were not read back`() {
        assertEquals("Every word was read back.", checkedLine(listOf(true, true)))
        assertEquals("Built from your own copies: nothing here can be read back.", checkedLine(listOf(false)))
        assertEquals("Every word was read back but one, built from your own copies.", checkedLine(listOf(true, false)))
        val two = checkedLine(listOf(false, true, false))
        assertEquals("Every word was read back but 2, built from your own copies.", two)
    }
}
