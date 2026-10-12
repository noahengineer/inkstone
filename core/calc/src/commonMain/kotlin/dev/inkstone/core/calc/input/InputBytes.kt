package dev.inkstone.core.calc.input

/**
 * The input buffer's byte accounting (research 02 §7.2: 99 bytes, the ■ cursor at 10 or fewer
 * left).
 *
 * The costs are the ES family's printed Math-format byte table (docs/CALCULATOR.md §3, row "Bytes
 * per token"; research 02 [S570] p.E-15, found by the stage-1 review and recorded in research 00
 * §5a): fraction 9, mixed fraction 13, log▪▯ 6, 10^▪ and e^▪ 4, √ 4, ∛ 9, x² and x³ 4, x⁻¹ 5,
 * x^▪ 4, ⁿ√ 9, ∫ 8, d/dx 6, Σ 8, Abs 4, and **1** for everything else — every other token,
 * constant and conversion. Its maximum, 13, is exactly the top of the ZA guide's "some functions
 * take 3 to 13 bytes". A template costs its own figure **plus** whatever its slots hold.
 *
 * ⚠️ GUESS, two ways (research 02 §15 #2 is how to settle both: fill the buffer on the emulator
 * and watch for the ■ cursor):
 * - The table is a 2005 sibling's; the ZA's own guide prints no figures.
 * - It is a *Math-format* table, and x², x³ and x⁻¹ are the same tokens in LineIO, where the
 *   firmware may well store them in one byte. [of] has no display mode to tell them apart (the
 *   editor asks it about an [Expr] alone), so they cost their Math figure in both displays. The
 *   LineIO spellings of the templates (`√(`, `^(`, `⌟`, …) are ordinary one-byte tokens.
 */
object InputBytes {
    /** Research 02 §7.2, VERIFIED. */
    const val CAPACITY = 99

    /** At this many bytes left, or fewer, the cursor is the filled block (research 02 §7.2). */
    const val BLOCK_CURSOR_THRESHOLD = 10

    fun of(expr: Expr): Int = expr.items.sumOf { of(it) }

    fun of(item: Item): Int = when (item) {
        is Template -> (TEMPLATE_COSTS[item.kind] ?: 1) + item.slots.sumOf { of(it) }
        is TokenItem -> TOKEN_COSTS[item.tok] ?: 1
        is ConstantItem, is ConversionItem -> 1
    }

    private const val FRACTION = 9
    private const val MIXED_FRACTION = 13
    private const val LOG_BASE = 6
    private const val POWER_OF = 4
    private const val ROOT = 4
    private const val INDEXED_ROOT = 9
    private const val SQUARE = 4
    private const val RECIPROCAL = 5
    private const val INTEGRAL = 8
    private const val DERIVATIVE = 6
    private const val SUMMATION = 8
    private const val ABS = 4

    /**
     * [TemplateKind.SCI_EXPONENT] is result-only and never typed, so it falls through to 1 like
     * every unlisted item.
     */
    private val TEMPLATE_COSTS: Map<TemplateKind, Int> = mapOf(
        TemplateKind.FRACTION to FRACTION,
        TemplateKind.MIXED_FRACTION to MIXED_FRACTION,
        TemplateKind.SQRT to ROOT,
        TemplateKind.CBRT to INDEXED_ROOT,
        TemplateKind.NTH_ROOT to INDEXED_ROOT,
        TemplateKind.POWER to POWER_OF,
        TemplateKind.LOG_BASE to LOG_BASE,
        TemplateKind.TEN_POWER to POWER_OF,
        TemplateKind.E_POWER to POWER_OF,
        TemplateKind.ABS to ABS,
        TemplateKind.INTEGRAL to INTEGRAL,
        TemplateKind.DERIVATIVE to DERIVATIVE,
        TemplateKind.SUMMATION to SUMMATION,
    )

    private val TOKEN_COSTS: Map<Tok, Int> = mapOf(
        Tok.SQUARE to SQUARE,
        Tok.CUBE to SQUARE,
        Tok.RECIPROCAL to RECIPROCAL,
    )
}
