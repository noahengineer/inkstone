package dev.inkstone.core.model

import kotlin.jvm.JvmInline

/**
 * SPEC §4.3 — page order, and object z-order, as fractional keys.
 *
 * ## Why not an integer index
 *
 * A plain list with LWW semantics silently drops work: two devices each add a
 * page offline, both write `pages = [...existing, mine]`, and the later write
 * wins — one device's page vanishes from the order while its data sits in the
 * bundle, orphaned. With a fractional key, "insert between B and C" is a value
 * neither device has to agree on in advance, so both pages survive and simply
 * sort. Resolution is by `(sortKey, pageId)`, so even identical keys are a stable
 * order rather than a coin flip.
 *
 * ## The representation
 *
 * A digit string over `[0-9A-Za-z]`, whose ASCII order **is** its digit order —
 * `'0'..'9' < 'A'..'Z' < 'a'..'z'` with no gaps that matter, so plain string
 * comparison is the total order and no parsing is needed to sort. There is no
 * integer part: the whole key is the fraction after an implied radix point.
 *
 * The invariant that makes this work is that a key never ends in the **smallest**
 * digit `'0'`: `"A0"` has no key below it other than `"A"`-prefixed strings that
 * are themselves smaller than `"A0"`, so it cannot be used as an upper bound.
 * [isWellFormed] is the check; [betweenOrNull] is the total version for keys that
 * came from a file rather than from [between].
 */
@JvmInline
public value class FractionalKey(public val value: String) : Comparable<FractionalKey> {

    override fun compareTo(other: FractionalKey): Int = value.compareTo(other.value)

    override fun toString(): String = value

    /**
     * True when this key can take part in [between].
     *
     * Non-empty, every character a digit, and not ending in the smallest digit.
     *
     * ⚠️ **A range test per character, not `it in DIGITS`.** That was a 62-character `indexOf` per
     * character, and `StrokeStore` asks this of every object a page load installs — on the UI
     * thread. On the physics note's 852-character keys it was ~26,000 comparisons per object and
     * 8% of the main thread while the page opened (see [above] for why keys got that long).
     */
    public val isWellFormed: Boolean
        get() = value.isNotEmpty() &&
            value.all { isDigit(it) } &&
            value.last() != DIGITS.first()

    public companion object {

        /**
         * Base 62 in ASCII order. The order of these characters *is* the order of
         * the keys — do not reorder them, and do not add a character that sorts
         * outside this range.
         */
        internal const val DIGITS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"

        /** The key for the first item in an empty order. Mid-alphabet, so both directions have room. */
        public val Initial: FractionalKey = FractionalKey(DIGITS[DIGITS.length / 2].toString())

        /**
         * A key strictly between [before] and [after]; either bound may be null
         * for "nothing on that side".
         *
         * @throws IllegalArgumentException if the bounds are out of order or not
         *   [isWellFormed]. Both are programming errors — keys reaching this
         *   function come from this app's own order. Use [betweenOrNull] for keys
         *   read from a file.
         */
        public fun between(before: FractionalKey?, after: FractionalKey?): FractionalKey =
            requireNotNull(betweenOrNull(before, after)) {
                "No key exists between $before and $after"
            }

        /** [between], returning null instead of throwing. */
        public fun betweenOrNull(before: FractionalKey?, after: FractionalKey?): FractionalKey? {
            if (before == null && after == null) return Initial
            if (before != null && !before.isWellFormed) return null
            if (after != null && !after.isWellFormed) return null
            if (before != null && after != null && before >= after) return null
            // ⭐ Nothing above: step rather than bisect. See [above].
            if (before != null && after == null) return FractionalKey(above(before.value))
            return FractionalKey(midpoint(before?.value ?: "", after?.value))
        }

        /**
         * [count] evenly spaced keys, for building a fresh order in one go.
         *
         * Used for a new document's pages and for repairing an order whose keys
         * did not survive a foreign tool (see `PageOrder`). Single digits while
         * they last, so the common case stays one character.
         */
        public fun sequence(count: Int): List<FractionalKey> {
            require(count >= 0) { "count must not be negative, was $count" }
            if (count == 0) return emptyList()
            // Leave the extreme digits free so there is always room to prepend
            // and append without lengthening the key.
            val usable = DIGITS.length - 2
            if (count <= usable) {
                val step = usable / count
                return List(count) { FractionalKey(DIGITS[1 + it * step].toString()) }
            }
            // More items than digits: two characters give 60 × 60 slots, which is
            // past any page count this app will see (SPEC §10 sizes a notebook at
            // hundreds of pages). Beyond that, ask for keys one at a time.
            require(count <= usable * usable) { "sequence($count) is beyond the two-digit range" }
            return List(count) { index ->
                val high = 1 + index / usable
                val low = 1 + index % usable
                FractionalKey("${DIGITS[high]}${DIGITS[low]}")
            }
        }

        /**
         * The digit-string midpoint. `a` may be empty (no lower bound), `b` may be
         * null (no upper bound); `a < b` where both are given.
         *
         * This is the standard fractional-indexing midpoint, and the recursion is
         * what keeps keys short: a common prefix is copied rather than re-derived,
         * so repeatedly inserting at the same place grows the key by one character
         * at a time instead of doubling it.
         */
        private fun midpoint(a: String, b: String?): String {
            if (b != null) {
                // Copy the common prefix and solve the shorter problem. `a`'s
                // missing digits read as the smallest digit, which is what makes
                // "insert between AB and ABC" work.
                var shared = 0
                while (shared < b.length && (a.getOrNull(shared) ?: DIGITS.first()) == b[shared]) shared++
                if (shared > 0) return b.take(shared) + midpoint(a.drop(shared), b.drop(shared))
            }

            val digitA = if (a.isEmpty()) 0 else DIGITS.indexOf(a[0])
            val digitB = if (b == null) DIGITS.length else DIGITS.indexOf(b[0])

            if (digitB - digitA > 1) {
                // There is a digit strictly between them: one character, done.
                return DIGITS[(digitA + digitB) / 2].toString()
            }

            // The digits are consecutive, so the answer starts with `a`'s digit and
            // continues below whatever `a` has left — with no upper bound, because
            // any continuation of `a`'s digit is already below `b`.
            return if (b != null && b.length > 1) b.take(1) else DIGITS[digitA] + midpoint(a.drop(1), null)
        }

        /**
         * ⭐ The shortest key above [a]: its first digit that is not the largest, plus one, with
         * everything after it dropped. `"V"` → `"W"`, `"zzk"` → `"zzl"`, and only an all-`z` key
         * grows: `"zz"` → `"zz1"`.
         *
         * ## Why "above everything" does not bisect
         *
         * Because it is the most common call in the app by a distance — every stroke drawn goes on
         * top of the page (`SelectionEdit.keyAbove`) — and bisecting towards the end of the alphabet
         * halves the room left above the key each time. [midpoint] with no upper bound gave `z`,
         * `zV`, `zk`, `zs`, `zw`, `zy`, `zz`, `zzV`…: **one character longer every six strokes**,
         * i.e. linear in the strokes ever drawn on a page, and quadratic in what the log stores.
         * The physics "Electrical energy" note reached **852-character** keys at 5,159 objects —
         * 2.2 MB of its 14.8 MB page log was z keys, every one of them re-read on every open.
         *
         * Stepping by one digit spends the 61 values of a position before reaching for the next,
         * so a page grows a character per ~61 strokes instead of per 6. It is still linear — a key
         * above an all-`z` key cannot be shorter than it, which is also why a note already at 852
         * characters stays there rather than shrinking — but it is ten times slower, and inserting
         * *between* two stepped keys is untouched: that is [midpoint]'s job and it grows a
         * character exactly as it did.
         *
         * Nothing about the order changed, so this is invisible to every build that reads the
         * keys: the result is a digit string, compared as a string, and strictly greater than [a].
         * Not ending in `'0'` holds by construction — the last digit is always a step up from
         * something, or `'1'`.
         */
        private fun above(a: String): String {
            for (index in a.indices) {
                val digit = digitOf(a[index])
                if (digit < DIGITS.length - 1) return a.substring(0, index) + DIGITS[digit + 1]
            }
            return a + DIGITS[1]
        }

        /** [DIGITS]`.indexOf(c)` without the scan. Only called on characters [isDigit] accepted. */
        private fun digitOf(c: Char): Int = when (c) {
            in '0'..'9' -> c - '0'
            in 'A'..'Z' -> c - 'A' + DECIMAL_DIGITS
            else -> c - 'a' + DECIMAL_DIGITS + LETTERS
        }

        private fun isDigit(c: Char): Boolean = c in '0'..'9' || c in 'A'..'Z' || c in 'a'..'z'

        private const val DECIMAL_DIGITS = 10
        private const val LETTERS = 26
    }
}
