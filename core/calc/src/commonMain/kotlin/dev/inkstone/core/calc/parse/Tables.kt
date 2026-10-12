package dev.inkstone.core.calc.parse

import dev.inkstone.core.calc.input.ConstantItem
import dev.inkstone.core.calc.input.Item
import dev.inkstone.core.calc.input.Template
import dev.inkstone.core.calc.input.TemplateKind
import dev.inkstone.core.calc.input.Tok
import dev.inkstone.core.calc.input.TokenItem

/** Token → node lookups, as data rather than `when`s, so each level of the parser stays small. */
internal object Tables {
    val orOps: Map<Tok, BinOp> = mapOf(Tok.OR to BinOp.OR, Tok.XOR to BinOp.XOR, Tok.XNOR to BinOp.XNOR)
    val andOps: Map<Tok, BinOp> = mapOf(Tok.AND to BinOp.AND)
    val sumOps: Map<Tok, BinOp> = mapOf(Tok.PLUS to BinOp.ADD, Tok.MINUS to BinOp.SUB)
    val productOps: Map<Tok, BinOp> = mapOf(Tok.TIMES to BinOp.MUL, Tok.DIVIDE to BinOp.DIV, Tok.DIV_R to BinOp.DIV_R)
    val dotOps: Map<Tok, BinOp> = mapOf(Tok.DOT to BinOp.DOT)
    val permutationOps: Map<Tok, BinOp> = mapOf(Tok.NPR to BinOp.NPR, Tok.NCR to BinOp.NCR, Tok.ANGLE to BinOp.ANGLE)

    /** Level 3 postfix tokens (x^□ and ˣ√ are handled beside them, because they carry an operand). */
    val postfix: Map<Tok, PostfixOp> = mapOf(
        Tok.SQUARE to PostfixOp.SQUARE,
        Tok.CUBE to PostfixOp.CUBE,
        Tok.RECIPROCAL to PostfixOp.RECIPROCAL,
        Tok.FACTORIAL to PostfixOp.FACTORIAL,
        Tok.PERCENT to PostfixOp.PERCENT,
        Tok.DEG_MARK to PostfixOp.DEG,
        Tok.RAD_MARK to PostfixOp.RAD,
        Tok.GRAD_MARK to PostfixOp.GRAD,
        Tok.TO_T to PostfixOp.TO_T,
    )

    /** Level 6 postfix: the STAT estimates (research 03 §4.4). Conversions are items, not tokens. */
    val estimates: Map<Tok, PostfixOp> = mapOf(
        Tok.EST_X to PostfixOp.EST_X,
        Tok.EST_X1 to PostfixOp.EST_X1,
        Tok.EST_X2 to PostfixOp.EST_X2,
        Tok.EST_Y to PostfixOp.EST_Y,
    )

    val formats: Map<Tok, PostfixOp> = mapOf(Tok.TO_POLAR to PostfixOp.TO_POLAR, Tok.TO_RECT to PostfixOp.TO_RECT)

    val functions: Map<Tok, Fn> = mapOf(
        Tok.SIN to Fn.SIN, Tok.COS to Fn.COS, Tok.TAN to Fn.TAN,
        Tok.ASIN to Fn.ASIN, Tok.ACOS to Fn.ACOS, Tok.ATAN to Fn.ATAN,
        Tok.SINH to Fn.SINH, Tok.COSH to Fn.COSH, Tok.TANH to Fn.TANH,
        Tok.ASINH to Fn.ASINH, Tok.ACOSH to Fn.ACOSH, Tok.ATANH to Fn.ATANH,
        Tok.LOG to Fn.LOG, Tok.LN to Fn.LN, Tok.POL to Fn.POL, Tok.REC to Fn.REC, Tok.RND to Fn.RND,
        Tok.GCD to Fn.GCD, Tok.LCM to Fn.LCM, Tok.RAN_INT to Fn.RAN_INT,
        Tok.ABS_FN to Fn.ABS, Tok.SQRT_FN to Fn.SQRT, Tok.CBRT_FN to Fn.CBRT,
        Tok.TEN_POW_FN to Fn.TEN_POW, Tok.E_POW_FN to Fn.EXP,
        Tok.ARG to Fn.ARG, Tok.CONJG to Fn.CONJG, Tok.NOT to Fn.NOT, Tok.NEG to Fn.NEG,
        Tok.DET to Fn.DET, Tok.TRN to Fn.TRN, Tok.REF to Fn.REF, Tok.RREF to Fn.RREF,
        Tok.DIST_P to Fn.DIST_P, Tok.DIST_Q to Fn.DIST_Q, Tok.DIST_R to Fn.DIST_R,
    )

    /** `∫(`, `d/dx(`, `Σ(`: functions whose nodes are not [Call]s. */
    val calculus: Set<Tok> = setOf(Tok.INTEGRAL_FN, Tok.DERIVATIVE_FN, Tok.SUM_FN)

    val variables: Map<Tok, Variable> = mapOf(
        Tok.VAR_A to Variable.A, Tok.VAR_B to Variable.B, Tok.VAR_C to Variable.C,
        Tok.VAR_D to Variable.D, Tok.VAR_E to Variable.E, Tok.VAR_F to Variable.F,
        Tok.VAR_M to Variable.M, Tok.VAR_X to Variable.X, Tok.VAR_Y to Variable.Y,
    )

    private val symbols: Map<Tok, Symbolic> = mapOf(
        Tok.ANS to Symbolic.ANS,
        Tok.PRE_ANS to Symbolic.PRE_ANS,
        Tok.PI to Symbolic.PI,
        Tok.E to Symbolic.E,
        Tok.IMAGINARY to Symbolic.I,
        Tok.RAN to Symbolic.RAN,
    )

    private val matrices: Map<Tok, Slot> =
        mapOf(Tok.MAT_A to Slot.A, Tok.MAT_B to Slot.B, Tok.MAT_C to Slot.C, Tok.MAT_ANS to Slot.ANS)

    private val vectors: Map<Tok, Slot> =
        mapOf(Tok.VCT_A to Slot.A, Tok.VCT_B to Slot.B, Tok.VCT_C to Slot.C, Tok.VCT_ANS to Slot.ANS)

    private val stats: Map<Tok, StatVariable> = mapOf(
        Tok.STAT_N to StatVariable.N, Tok.MEAN_X to StatVariable.MEAN_X, Tok.SIGMA_X to StatVariable.SIGMA_X,
        Tok.SAMPLE_SX to StatVariable.SAMPLE_SX, Tok.MEAN_Y to StatVariable.MEAN_Y,
        Tok.SIGMA_Y to StatVariable.SIGMA_Y, Tok.SAMPLE_SY to StatVariable.SAMPLE_SY,
        Tok.SUM_X to StatVariable.SUM_X, Tok.SUM_X2 to StatVariable.SUM_X2, Tok.SUM_Y to StatVariable.SUM_Y,
        Tok.SUM_Y2 to StatVariable.SUM_Y2, Tok.SUM_XY to StatVariable.SUM_XY, Tok.SUM_X3 to StatVariable.SUM_X3,
        Tok.SUM_X2Y to StatVariable.SUM_X2Y, Tok.SUM_X4 to StatVariable.SUM_X4,
        Tok.MIN_X to StatVariable.MIN_X, Tok.MAX_X to StatVariable.MAX_X,
        Tok.MIN_Y to StatVariable.MIN_Y, Tok.MAX_Y to StatVariable.MAX_Y,
        Tok.Q1 to StatVariable.Q1, Tok.MEDIAN to StatVariable.MEDIAN, Tok.Q3 to StatVariable.Q3,
        Tok.REG_A to StatVariable.REG_A, Tok.REG_B to StatVariable.REG_B,
        Tok.REG_C to StatVariable.REG_C, Tok.REG_R to StatVariable.REG_R,
    )

    val radixPrefixes: Map<Tok, Radix> = mapOf(
        Tok.PREFIX_DEC to Radix.DEC,
        Tok.PREFIX_HEX to Radix.HEX,
        Tok.PREFIX_BIN to Radix.BIN,
        Tok.PREFIX_OCT to Radix.OCT,
    )

    /** A fresh leaf for a value token, or null. Fresh, so that [SourceMap] can tell two `π`s apart. */
    fun value(tok: Tok): Node? = variables[tok]?.let(::Var)
        ?: symbols[tok]?.let(::Sym)
        ?: matrices[tok]?.let(::MatrixRef)
        ?: vectors[tok]?.let(::VectorRef)
        ?: stats[tok]?.let(::StatVar)

    /**
     * May an omitted `×` stand before [item]? VERIFIED (research 02 §7.1): `(`, a function that
     * opens a parenthesis, Ran#, a variable, a scientific constant, π and e. Also allowed, as
     * INFERRED: `i` (`6i`, `Bi` are the guide's own CMPLX inputs), and a Natural template other
     * than x^□ (`10√2` in research 02 §8.6 is a √ template after a number). Also INFERRED: a
     * BASE-N prefix — the ES family's own list says "before a prefix symbol (excluding the minus
     * sign): 2 × h123" (research 02 [S570] p.E-11); the ZA's list is COMP's and names no BASE-N
     * item. ⚠️ GUESS: Ans, PreAns, matrix, vector and STAT names, which neither guide lists.
     */
    fun startsImplicit(item: Item): Boolean = when (item) {
        is Template -> item.kind != TemplateKind.POWER && item.kind != TemplateKind.SCI_EXPONENT
        is ConstantItem -> true
        is TokenItem -> item.tok in openers || item.tok in radixPrefixes || value(item.tok) != null
        else -> false
    }

    private val openers: Set<Tok> = setOf(Tok.OPEN) + functions.keys + calculus

    private val TEMPLATE_FUNCTIONS: Map<TemplateKind, Fn> = mapOf(
        TemplateKind.SQRT to Fn.SQRT,
        TemplateKind.CBRT to Fn.CBRT,
        TemplateKind.TEN_POWER to Fn.TEN_POW,
        TemplateKind.E_POWER to Fn.EXP,
        TemplateKind.ABS to Fn.ABS,
        TemplateKind.LOG_BASE to Fn.LOG,
    )

    /** The node for a Natural template whose slots parsed to [slots]; null for x^□ and results. */
    fun fromTemplate(kind: TemplateKind, slots: List<Node>): Node? {
        TEMPLATE_FUNCTIONS[kind]?.let { return Call(it, slots) }
        return when (kind) {
            TemplateKind.FRACTION -> Fraction(slots[0], slots[1])
            TemplateKind.MIXED_FRACTION -> MixedFraction(slots[0], slots[1], slots[2])
            TemplateKind.NTH_ROOT -> Root(slots[0], slots[1])
            TemplateKind.INTEGRAL -> Integral(slots[0], slots[1], slots[2])
            TemplateKind.DERIVATIVE -> Derivative(slots[0], slots[1])
            TemplateKind.SUMMATION -> Summation(slots[0], slots[1], slots[2])
            else -> null
        }
    }
}
