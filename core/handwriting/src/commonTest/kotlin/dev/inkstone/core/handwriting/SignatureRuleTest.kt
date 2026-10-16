package dev.inkstone.core.handwriting

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** D11 at the strict setting (§14, Q6): a name standing on its own, surname included, is refused. */
class SignatureRuleTest {

    private val name = "Ada Lovelace"

    @Test
    fun `the name in any order - with or without punctuation and initials is refused`() {
        val forms = listOf("Ada Lovelace", "lovelace ada", "Lovelace, Ada", "A. Lovelace", "A Lovelace", "ADA LOVELACE")
        for (text in forms) {
            assertTrue(SignatureRule.refuses(text, name), text)
        }
    }

    @Test
    fun `a first name or initials alone are not a signature`() {
        for (text in listOf("Ada", "AL", "A.L.", "ada a")) assertFalse(SignatureRule.refuses(text, name), text)
    }

    @Test
    fun `the name inside a sentence is not a signature`() {
        assertFalse(SignatureRule.refuses("ask Ada Lovelace", name))
        assertFalse(SignatureRule.refuses("Lovelace equation", name))
    }

    @Test
    fun `with no account name nothing is refused`() {
        assertFalse(SignatureRule.refuses("Ada Lovelace", null))
        assertFalse(SignatureRule.refuses("Ada Lovelace", "  "))
    }

    @Test
    fun `a one word name is its own surname`() {
        assertTrue(SignatureRule.refuses("Ada", "Ada"))
    }
}
