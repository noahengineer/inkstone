package dev.inkstone.core.handwriting

import dev.inkstone.core.model.PhraseRecord

/**
 * A phrase written with the *Maths* switch on says so in its record (D3), so *Write again* and *Edit
 * text* write it as maths again. An unknown header key to an older build, which keeps it and writes it
 * back ([PhraseRecord.extras]); that build's *Write again* writes the text on one line — a degrade.
 */
public object MathsPhrase {
    public const val RECORD_KEY: String = "m"

    public fun of(record: PhraseRecord): Boolean = record.extras[RECORD_KEY] == "1"

    public fun extras(maths: Boolean): Map<String, String> = if (maths) mapOf(RECORD_KEY to "1") else emptyMap()
}

/**
 * One node of a maths expression as the Write panel's *Maths* switch reads it
 * (docs/HANDWRITING-MATHS.md §2). The shapes are `:core:calc`'s templates — `FRACTION`, `SQRT`,
 * `CBRT`, `NTH_ROOT`, `POWER`, `ABS` — re-stated here because that model is an editor's, with cursor
 * paths into its slots, and is measured in LCD pixels; what is shared is the design, not the code.
 */
internal sealed interface MathNode

/** A run of characters with no space in it: written by the ladder as one word, and read back as one. */
internal data class MathText(val text: String) : MathNode

/** A space the user typed inside an expression: the hand's own word gap. */
internal data object MathSpace : MathNode

internal data class MathRow(val items: List<MathNode>) : MathNode {
    val isEmpty: Boolean get() = items.isEmpty()
}

/** A stacked fraction: [numerator] over a bar over [denominator]. */
internal data class MathFraction(val numerator: MathRow, val denominator: MathRow) : MathNode

/** `√`, `∛` and `ⁿ√`: [index] is null for a square root, `3` for a cube root. */
internal data class MathRoot(val index: MathRow?, val radicand: MathRow) : MathNode

/** A raised [sup] and a lowered [sub], either may be absent, after whatever precedes it in its row. */
internal data class MathScript(val sup: MathRow?, val sub: MathRow?) : MathNode

/** Brackets drawn as tall as what they hold: `( )`, `[ ]`, `{ }`, and `| |` for an absolute value. */
internal data class MathFence(val open: Char, val close: Char, val content: MathRow) : MathNode

/**
 * ⭐ The *Maths* switch's notation — docs/HANDWRITING-MATHS.md §2. Typed on one line, written in two
 * dimensions:
 *
 * | Typed | Written |
 * |---|---|
 * | `a/b`, `(x+1)/(2x)` | a stacked fraction; brackets round a whole numerator or denominator only group it |
 * | `2 3/4` | a mixed number |
 * | `√x`, `√(x+1)`, `sqrt(x)` | a square root, its bar over the radicand |
 * | `∛x`, `cbrt(x)`, `∜x`, `root(n, x)` | a cube, fourth or nth root, the index in the notch |
 * | `x^2`, `x^(n+1)`, `x_1`, `x_(i+1)` | raised and lowered scripts |
 * | `(…)`, `[…]`, `{…}` | brackets, stretched when what they hold is taller than a line |
 * | `|x|`, `abs(x)` | absolute-value bars |
 *
 * `÷` stays a sign on the line. A token with none of this in it is not maths at all, and is written
 * exactly as it is with the switch off ([parse] answers null).
 */
internal object MathNotation {
    private const val OPENERS = "([{"
    private const val ROOT_SIGNS = "√∛∜"

    /** Where a numerator's term ends, reading back from the `/`: the signs that join terms. */
    private const val TERM_BREAKS = "+-−=×÷±∓·<>≤≥≠≈,;:"
    private const val SIGNS = "-−+±"
    private val FUNCTIONS = listOf("sqrt(", "cbrt(", "root(", "abs(")

    private fun closerOf(open: Char): Char = when (open) {
        '(' -> ')'
        '[' -> ']'
        else -> '}'
    }

    /**
     * The phrase's tokens with the switch on: split at spaces only **outside** brackets and bars, so
     * `(−b ± √(b^2−4ac))/(2a)` stays one token, and a whole number followed by a numeric fraction
     * (`2 3/4`) joined into one mixed number.
     */
    fun words(line: String): List<String> {
        val words = ArrayList<String>()
        val current = StringBuilder()
        var depth = 0
        var insideBars = false
        for (c in line) {
            when {
                c in OPENERS -> depth++
                c in ")]}" -> depth = maxOf(0, depth - 1)
                c == '|' -> insideBars = !insideBars
            }
            if (isBlank(c) && depth == 0 && !insideBars) {
                if (current.isNotEmpty()) words += current.toString()
                current.clear()
            } else {
                current.append(c)
            }
        }
        if (current.isNotEmpty()) words += current.toString()
        return joinMixedNumbers(words)
    }

    private fun joinMixedNumbers(words: List<String>): List<String> {
        val joined = ArrayList<String>()
        var i = 0
        while (i < words.size) {
            val next = words.getOrNull(i + 1)
            if (next != null && words[i].all(Char::isDigit) && MIXED_FRACTION.matches(next)) {
                joined += words[i] + " " + next
                i += 2
            } else {
                joined += words[i]
                i++
            }
        }
        return joined
    }

    private val MIXED_FRACTION = Regex("[0-9]+/[0-9]+")

    /** [token] as maths, or null when it holds nothing two-dimensional and is an ordinary word. */
    fun parse(token: String): MathRow? {
        val parsed = Parser(token).whole()
        val row = flatten(mixed(parsed))
        return row.takeIf { hasStructure(it) }
    }

    /** Every run the ladder writes, in the order [MathLayout] reads them back. */
    fun texts(row: MathRow): List<String> = ArrayList<String>().also { collect(row, it) }

    private fun collect(node: MathNode, into: MutableList<String>) {
        when (node) {
            is MathText -> into += node.text

            MathSpace -> Unit

            is MathRow -> node.items.forEach { collect(it, into) }

            is MathFraction -> {
                collect(node.numerator, into)
                collect(node.denominator, into)
            }

            is MathRoot -> {
                node.index?.let { collect(it, into) }
                collect(node.radicand, into)
            }

            is MathScript -> {
                node.sup?.let { collect(it, into) }
                node.sub?.let { collect(it, into) }
            }

            is MathFence -> collect(node.content, into)
        }
    }

    private fun hasStructure(node: MathNode): Boolean = when (node) {
        is MathText, MathSpace -> false
        is MathRow -> node.items.any(::hasStructure)
        is MathFraction, is MathRoot, is MathScript, is MathFence -> true
    }

    /**
     * A bracket pair round nothing taller than a line is written as text, by the user's own hand:
     * `(x+1)^2` is the word `(x+1)` with a `2` raised after it. Only brackets round a fraction, a root
     * or a script are drawn and stretched. Bars are always drawn: few hands have written a `|`.
     */
    private fun flatten(row: MathRow): MathRow {
        val items = ArrayList<MathNode>()
        for (node in row.items) {
            when (val done = flattenNode(node)) {
                is MathRow -> done.items.forEach { appendMerging(items, it) }
                else -> appendMerging(items, done)
            }
        }
        return MathRow(items)
    }

    private fun flattenNode(node: MathNode): MathNode = when (node) {
        is MathText, MathSpace -> node

        is MathRow -> flatten(node)

        is MathFraction -> MathFraction(flatten(node.numerator), flatten(node.denominator))

        is MathRoot -> MathRoot(node.index?.let(::flatten), flatten(node.radicand))

        is MathScript -> MathScript(node.sup?.let(::flatten), node.sub?.let(::flatten))

        is MathFence -> {
            val content = flatten(node.content)
            if (node.open == '|' || hasStructure(content) || content.isEmpty) {
                MathFence(node.open, node.close, content)
            } else {
                // Flat: the brackets join the first and last runs, and are written with them.
                val inner = content.items.toMutableList()
                inner[0] = MathText(node.open + (inner[0] as MathText).text)
                inner[inner.lastIndex] = MathText((inner.last() as MathText).text + node.close)
                MathRow(inner)
            }
        }
    }

    private fun appendMerging(items: MutableList<MathNode>, node: MathNode) {
        val last = items.lastOrNull()
        if (node is MathText && last is MathText) {
            items[items.lastIndex] = MathText(last.text + node.text)
        } else {
            items += node
        }
    }

    /** `2 3/4`: no word gap between a whole number and the fraction it is the whole part of. */
    private fun mixed(row: MathRow): MathRow {
        val items = row.items
        val kept = ArrayList<MathNode>()
        for ((i, node) in items.withIndex()) {
            val before = items.getOrNull(i - 1)
            val after = items.getOrNull(i + 1)
            if (!(node == MathSpace && isMixedGap(before, after))) kept += node
        }
        return MathRow(kept)
    }

    private fun isMixedGap(before: MathNode?, after: MathNode?): Boolean {
        val whole = before is MathText && before.text.all(Char::isDigit)
        return whole && after is MathFraction && isNumeric(after.numerator) && isNumeric(after.denominator)
    }

    private fun isBlank(c: Char): Boolean = c == ' ' || c == '\t'

    private fun isNumeric(row: MathRow): Boolean =
        row.items.size == 1 && (row.items[0] as? MathText)?.text?.all(Char::isDigit) == true

    /** Recursive descent over one token. Never throws: whatever does not parse is text. */
    // A recursive-descent parser branches once per kind of character; splitting each `when` would only
    // move the branches. Each rule reads as the table in this object's KDoc.
    @Suppress("CyclomaticComplexMethod", "LoopWithTooManyJumpStatements")
    private class Parser(private val s: String) {
        private var i = 0

        private fun at(c: Char): Boolean = i < s.length && s[i] == c

        private fun skip(c: Char) {
            if (at(c)) i++
        }

        fun whole(): MathRow {
            val items = ArrayList<MathNode>()
            while (i < s.length) {
                items += row(closer = null).items
                // A closer with no opener at this level is a character like any other.
                if (i < s.length) text(items, s[i++])
            }
            return MathRow(trimmed(items))
        }

        private fun row(closer: Char?): MathRow {
            val items = ArrayList<MathNode>()
            while (i < s.length) {
                val c = s[i]
                if (c == closer || (closer == null && c in ")]}")) break
                when {
                    c == ' ' || c == '\t' -> space(items)

                    c in OPENERS -> fence(items, c)

                    c == '|' -> bars(items)

                    function() != null -> items += functionCall()

                    c in ROOT_SIGNS -> root(items, c)

                    c == '^' || c == '_' -> script(items, c)

                    c == '/' -> fraction(items)

                    else -> {
                        text(items, c)
                        i++
                    }
                }
            }
            return MathRow(trimmed(items))
        }

        private fun space(items: MutableList<MathNode>) {
            while (i < s.length && (s[i] == ' ' || s[i] == '\t')) i++
            if (items.isNotEmpty() && items.last() != MathSpace) items += MathSpace
        }

        private fun fence(items: MutableList<MathNode>, open: Char) {
            i++
            val close = closerOf(open)
            val content = row(close)
            if (at(close)) {
                i++
                items += MathFence(open, close, content)
            } else {
                // Unclosed: the bracket is a character, and what followed it is ordinary.
                text(items, open)
                content.items.forEach { appendText(items, it) }
            }
        }

        private fun bars(items: MutableList<MathNode>) {
            i++
            val content = row('|')
            if (at('|') && !content.isEmpty) {
                i++
                items += MathFence('|', '|', content)
            } else {
                text(items, '|')
                content.items.forEach { appendText(items, it) }
            }
        }

        private fun function(): String? = FUNCTIONS.firstOrNull { s.startsWith(it, i) }

        private fun functionCall(): MathNode {
            val name = function() ?: return MathText("")
            i += name.length
            return when (name) {
                "root(" -> {
                    val index = row(',')
                    skip(',')
                    while (at(' ')) i++
                    val radicand = row(')')
                    skip(')')
                    MathRoot(index.takeUnless { it.isEmpty }, radicand)
                }

                else -> {
                    val content = row(')')
                    skip(')')
                    when (name) {
                        "sqrt(" -> MathRoot(null, content)
                        "cbrt(" -> MathRoot(MathRow(listOf(MathText("3"))), content)
                        else -> MathFence('|', '|', content)
                    }
                }
            }
        }

        private fun root(items: MutableList<MathNode>, sign: Char) {
            i++
            val radicand = operand()
            if (radicand.isEmpty) {
                text(items, sign)
                return
            }
            val index = when (sign) {
                '∛' -> MathRow(listOf(MathText("3")))
                '∜' -> MathRow(listOf(MathText("4")))
                else -> null
            }
            items += MathRoot(index, radicand)
        }

        private fun script(items: MutableList<MathNode>, mark: Char) {
            i++
            val body = operand(allowSign = true, allowScripts = false)
            if (body.isEmpty) {
                text(items, mark)
                return
            }
            val last = items.lastOrNull()
            val merged = when {
                last is MathScript && mark == '^' && last.sup == null -> MathScript(body, last.sub)
                last is MathScript && mark == '_' && last.sub == null -> MathScript(last.sup, body)
                else -> null
            }
            if (merged != null) {
                items[items.lastIndex] = merged
            } else {
                items += if (mark == '^') MathScript(body, null) else MathScript(null, body)
            }
        }

        private fun fraction(items: MutableList<MathNode>) {
            i++
            val numerator = takeNumerator(items)
            val denominator = operand()
            if (numerator.isEmpty || denominator.isEmpty) {
                numerator.items.forEach { appendText(items, it) }
                text(items, '/')
                denominator.items.forEach { appendText(items, it) }
            } else {
                items += MathFraction(numerator, denominator)
            }
        }

        /**
         * The term just before a `/`: a bracket group, whose brackets only grouped it, or the run of
         * letters, digits, roots and scripts back to the last sign that joins terms (`+`, `=`, a space).
         */
        private fun takeNumerator(items: MutableList<MathNode>): MathRow {
            val last = items.lastOrNull()
            if (last is MathFence && last.open == '(') {
                items.removeAt(items.lastIndex)
                return last.content
            }
            val taken = ArrayList<MathNode>()
            while (items.isNotEmpty()) {
                when (val node = items.last()) {
                    MathSpace -> break

                    is MathText -> {
                        val cut = node.text.indexOfLast { it in TERM_BREAKS }
                        if (cut == node.text.lastIndex) break
                        items.removeAt(items.lastIndex)
                        if (cut >= 0) {
                            items += MathText(node.text.substring(0, cut + 1))
                            taken.add(0, MathText(node.text.substring(cut + 1)))
                            break
                        }
                        taken.add(0, node)
                    }

                    else -> {
                        items.removeAt(items.lastIndex)
                        taken.add(0, node)
                    }
                }
            }
            return MathRow(taken)
        }

        /**
         * What a `/`, a `^`, a `_` or a root sign applies to: a bracket group (its brackets dropped),
         * a root, or a run of letters and digits — with a leading sign for an exponent (`x^-1`), and
         * scripts after it for a denominator or radicand (`x/y^2`).
         */
        private fun operand(allowSign: Boolean = false, allowScripts: Boolean = true): MathRow {
            if (i >= s.length) return MathRow(emptyList())
            val c = s[i]
            if (c == '(') {
                i++
                val content = row(')')
                skip(')')
                return content
            }
            val items = ArrayList<MathNode>()
            when {
                c == '[' || c == '{' -> fence(items, c)
                c == '|' -> bars(items)
                function() != null -> items += functionCall()
                c in ROOT_SIGNS -> root(items, c)
                else -> run(items, allowSign, short = !allowScripts)
            }
            if (items.isEmpty()) return MathRow(emptyList())
            while (allowScripts && (at('^') || at('_'))) script(items, s[i])
            return MathRow(items)
        }

        /**
         * A run of letters and digits. A [short] one — a script's — is a number or a single letter, so
         * `x^2y` is x²·y and `x_ij` is x with an `i` lowered and a `j` after it, as a calculator reads them;
         * anything longer is bracketed, `x^(2y)`.
         */
        private fun run(items: MutableList<MathNode>, allowSign: Boolean, short: Boolean) {
            val start = i
            if (allowSign && i < s.length && s[i] in SIGNS) i++
            val body = i
            when {
                !short -> while (i < s.length && isRunChar(s[i])) i++
                i < s.length && s[i].isDigit() -> while (i < s.length && (s[i].isDigit() || s[i] == '.')) i++
                i < s.length && isRunChar(s[i]) -> i++
            }
            if (i == body) {
                i = start
                return
            }
            items += MathText(s.substring(start, i))
        }

        private fun isRunChar(c: Char): Boolean = c.isLetterOrDigit() || c == '.' || c == '\'' || c == '!' || c == '°'

        private fun text(items: MutableList<MathNode>, c: Char) = appendText(items, MathText(c.toString()))

        private fun appendText(items: MutableList<MathNode>, node: MathNode) {
            val last = items.lastOrNull()
            if (node is MathText &&
                last is MathText
            ) {
                items[items.lastIndex] = MathText(last.text + node.text)
            } else {
                items += node
            }
        }

        private fun trimmed(items: List<MathNode>): List<MathNode> =
            items.dropWhile { it == MathSpace }.dropLastWhile { it == MathSpace }
    }
}
