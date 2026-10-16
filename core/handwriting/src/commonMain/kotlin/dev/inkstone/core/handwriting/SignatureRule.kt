package dev.inkstone.core.handwriting

/**
 * ⭐ D11, at the strict setting the user chose (§14, Q6): handwriting synthesis never writes a
 * signature.
 *
 * A phrase is refused when **every** word of it is part of the account holder's name — a name, or
 * an initial, or a run of initials (`AL`) — in any order, **and** the surname is one of them. So
 * `Ada Lovelace`, `Lovelace, Ada`, `A. Lovelace` and `A Lovelace` are refused, and `Ada` alone, `AL`
 * alone, or the name inside a sentence (`ask Ada Lovelace`) are not: a signature is a name standing
 * on its own, with the surname that makes it someone's.
 *
 * A one-word display name is its own surname. A missing name refuses nothing — there is no one to
 * protect, and the rule is about the account holder, not about names in general.
 *
 * This is one of two things that make *"it looks just like you did it"* defensible rather than a
 * forgery tool. The other is that generated ink is always marked, in the note (`hw`) and in the PDF
 * (`/INKS_Generated`).
 */
public object SignatureRule {

    public fun refuses(text: String, displayName: String?): Boolean {
        val name = wordsOf(displayName ?: return false)
        if (name.isEmpty()) return false
        val surname = name.last()
        val words = wordsOf(text)
        if (words.isEmpty() || surname !in words) return false
        val initials = name.map { it.first() }.toSet()
        return words.all { word -> word in name || word.all { it in initials } }
    }

    /** Letters only, lower case, split on anything else: `"A. Lovelace"` → `[a, lovelace]`. */
    private fun wordsOf(text: String): List<String> = text
        .split { !it.isLetter() }
        .filter { it.isNotEmpty() }
        .map { it.lowercase() }

    private fun String.split(isSeparator: (Char) -> Boolean): List<String> {
        val parts = ArrayList<String>()
        val current = StringBuilder()
        for (c in this) {
            if (isSeparator(c)) {
                parts += current.toString()
                current.clear()
            } else {
                current.append(c)
            }
        }
        parts += current.toString()
        return parts
    }
}
