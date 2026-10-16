package dev.inkstone.core.handwriting

/**
 * ⭐ Where a cut letter sits in its word's normalised frame (y up, baseline 0, x-height 1), against
 * where a letter of its kind belongs — docs/HANDWRITING-SYNTHESIS.md §18.7 and §18.10.
 *
 * A sample's frame is its *line's* (§5.2). When the line's baseline or angle is wrong for the word —
 * a word written at a slant inside a diagram, a word beside a fraction — every letter cut from it is
 * stored at the wrong height, and assembly copies it there. On the tablet's 136-word profile this put
 * a `p` from `tap` a whole descender above the line and its `t` one and a half x-heights below it,
 * read back as legible both times (ML Kit reads a word, not a baseline).
 *
 * So a word whose cut letters disagree with its frame is not assembled from. That refuses material
 * rather than guessing a correction: a rotation, an offset and a scale can each explain the same
 * misplaced letter, and a wrong guess is a wrong word on the page.
 *
 * **Lower-case letters** are judged by their shape, which is unambiguous. **Capitals, digits and
 * symbols** were not judged at all until §18.10, because a rule that expected capitals and digits
 * *tall* set aside 49 of 85 words — this user writes `NO2` and `CaCO3` at x-height. The cost of
 * judging nothing was a `7` stored at **8.6** x-heights (from `=27`), an `A` at 10.5 and an `=` at
 * 5.7, each drawn that size the moment assembly picked it: *"a number or symbol will show up very
 * big"*. Their windows now ask for nothing about size beyond *not absurd*: anything from x-height up
 * to [MAX_FULL_HEIGHT], on the line. That keeps every honest copy on the profile — the tallest is a
 * `C` at 2.74, from `CaCO3` — and drops every one that was 3 or more x-heights out.
 */
public object LetterFit {

    /** How a letter stands on the baseline. */
    public enum class Shape {
        X_HEIGHT,
        TALL,
        DESCENDER,

        /** `i`: an x-height letter with a dot, so its top may be well above the x-height. */
        DOTTED,

        /** Capitals and digits: on the line, anything from x-height to [MAX_FULL_HEIGHT] tall. */
        FULL_HEIGHT,

        /** `.` and `,`: down at the line. */
        POINT,

        /** `-`, `=`, `+`, `~`, `*`: across the middle of the x-height. */
        ACROSS,

        /** Everything else — `f`, `j`, brackets, `/`, `%`, `?`, quotes, `°` — judged only for absurdity. */
        OTHER,
    }

    public fun shapeOf(letter: String): Shape {
        val c = letter.singleOrNull() ?: return Shape.OTHER
        return when {
            c in X_HEIGHT_LETTERS -> Shape.X_HEIGHT
            c in TALL_LETTERS -> Shape.TALL
            c in DESCENDER_LETTERS -> Shape.DESCENDER
            c == 'i' -> Shape.DOTTED
            c.isUpperCase() || c.isDigit() -> Shape.FULL_HEIGHT
            c in POINTS -> Shape.POINT
            c in ACROSS_MARKS -> Shape.ACROSS
            else -> Shape.OTHER
        }
    }

    /**
     * Whether [letter], whose ink runs from [low] to [high] in the word's normalised frame, sits
     * where its kind belongs. The windows are wide on purpose: they exist to catch a frame that is
     * wrong by most of an x-height, not to tidy a hand.
     */
    public fun fits(letter: String, low: Float, high: Float): Boolean = when (val shape = shapeOf(letter)) {
        Shape.X_HEIGHT, Shape.TALL, Shape.DESCENDER, Shape.DOTTED -> lowerCaseFits(shape, low, high)
        Shape.FULL_HEIGHT, Shape.POINT, Shape.ACROSS, Shape.OTHER -> markFits(shape, low, high)
    }

    private fun lowerCaseFits(shape: Shape, low: Float, high: Float): Boolean = when (shape) {
        Shape.X_HEIGHT -> low in ON_BASELINE && high in AT_X_HEIGHT
        Shape.TALL -> low in ON_BASELINE && high >= MIN_TALL && high <= MAX_FULL_HEIGHT
        Shape.DESCENDER -> low <= MAX_DESCENDER_LOW && high in AT_X_HEIGHT_OR_DOT
        else -> low in ON_BASELINE && high in AT_X_HEIGHT_TO_DOT
    }

    /** Capitals, digits and symbols: only a frame that is wrong by most of an x-height fails (§18.10). */
    private fun markFits(shape: Shape, low: Float, high: Float): Boolean = when (shape) {
        Shape.FULL_HEIGHT -> low in NEAR_BASELINE && high in AT_X_HEIGHT_TO_FULL
        Shape.POINT -> low >= MIN_POINT_LOW && high in POINT_TOP
        Shape.ACROSS -> (low + high) / 2 in ACROSS_THE_MIDDLE && high - low <= MAX_ACROSS_HEIGHT
        else -> low >= MIN_ANY_LOW && high <= MAX_FULL_HEIGHT
    }

    /**
     * ⭐ §18.10 — a digit written as a subscript: the `3` of `CaCO3`, the `2` of `NO2`.
     *
     * It fits ([fits]) — its word's frame is fine, and the word and its runs are exactly right for
     * writing chemistry — but alone it is the wrong size for a number: `y = -0.25x + 3` came out with a
     * `3` two-thirds the height of everything beside it. So it is kept out of the *single-letter*
     * units only. This hand's digits stand 1.1–1.25 x-heights at the median; every one under
     * [MAX_SUBSCRIPT_TOP] on the profile was from a formula.
     */
    public fun isSubscript(letter: String, high: Float): Boolean =
        letter.singleOrNull()?.isDigit() == true && high < MAX_SUBSCRIPT_TOP

    /**
     * ⭐ [isSubscript] and its raised twin (docs/HANDWRITING-MATHS.md §5.2): a digit written as an
     * exponent, the `2` of `m/s²`. Its top is where a digit's top belongs, so [isSubscript] cannot see
     * it; its height gives it away. On the tablet's profile every single-letter digit stood 0.94–2.07
     * x-heights tall except one `2` at 0.55, raised 0.28 off the line, which the maths sheet then drew
     * as a dot in an exponent's place.
     */
    public fun isScript(letter: String, low: Float, high: Float): Boolean =
        isSubscript(letter, high) || (letter.singleOrNull()?.isDigit() == true && high - low < MIN_DIGIT_HEIGHT)

    private const val MAX_SUBSCRIPT_TOP = 0.8f
    private const val MIN_DIGIT_HEIGHT = 0.7f

    private const val X_HEIGHT_LETTERS = "acemnorsuvwxz"
    private const val TALL_LETTERS = "bdhklt"
    private const val DESCENDER_LETTERS = "gpqy"
    private const val POINTS = ".,"
    private const val ACROSS_MARKS = "-=+~*"

    /**
     * A letter that stands on the line starts within about half an x-height of it. On the tablet's
     * profile the misplaced letters were 0.5 to 1.5 x-heights off, and an honest hand's ±0.4.
     */
    private val ON_BASELINE = -0.45f..0.45f

    /**
     * A capital or digit, a little looser below: this hand's `6`s sit at −0.47 at the median, and a
     * digit a little under the line is a digit, where a lower-case letter that far down is a frame
     * error. Every misframed copy on the profile was 0.75 or more out.
     */
    private val NEAR_BASELINE = -0.6f..0.5f

    /** An x-height letter's top is somewhere near one x-height. */
    private val AT_X_HEIGHT = 0.55f..1.6f

    /** A descender's top is at the x-height, unless it is a capital-sized loop. */
    private val AT_X_HEIGHT_OR_DOT = 0.55f..1.9f

    /** An `i`'s top is its dot, which can float well clear of the x-height. */
    private val AT_X_HEIGHT_TO_DOT = 0.55f..2.2f

    /** A capital or digit: at least x-height-ish (`NO2`), at most [MAX_FULL_HEIGHT]. */
    private val AT_X_HEIGHT_TO_FULL = 0.55f..2.8f

    /**
     * The tallest anything may be. The tallest honest copy on the tablet's profile is a `C` at 2.74
     * (from `CaCO3`); the shortest misframed one is a `P` at 3.08, then everything from 5 to 10.5.
     */
    private const val MAX_FULL_HEIGHT = 2.8f

    /** A tall letter reaches clearly above the x-height. */
    private const val MIN_TALL = 1.05f

    /** A descender reaches clearly below the line. */
    private const val MAX_DESCENDER_LOW = -0.25f

    /**
     * A `.` or `,` sits at the line: its top is near it (the profile's `p.` had its dot at −0.74, a
     * whole dot-and-more below), and a comma's tail may hang a little but never a line's worth.
     */
    private const val MIN_POINT_LOW = -1.2f
    private val POINT_TOP = -0.35f..0.6f

    /** A dash, an equals or a plus is centred somewhere across the x-height band, not above or below it. */
    private val ACROSS_THE_MIDDLE = -0.1f..1.0f
    private const val MAX_ACROSS_HEIGHT = 1.8f

    /** How far below the line anything else may reach: a bracket's or an `f`'s tail, not a whole line. */
    private const val MIN_ANY_LOW = -1.6f
}
