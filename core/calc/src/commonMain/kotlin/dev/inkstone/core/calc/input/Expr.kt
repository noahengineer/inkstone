package dev.inkstone.core.calc.input

/**
 * An expression as the calculator holds it while you type: a run of [Item]s, some of which are
 * two-dimensional [Template]s with their own nested runs ([Template.slots]).
 *
 * The same structure carries **results** that the formatter lays out for display (a fraction
 * result is a [TemplateKind.FRACTION]; `1,25×10⁻³` uses [TemplateKind.SCI_EXPONENT]) so one layout
 * engine draws both lines of the LCD.
 *
 * Immutable on purpose: an edit returns a new tree, so history replay, CALC's stored expression
 * and SOLVE's equation are plain references with no copying discipline to get wrong. Inputs are at
 * most 99 bytes, so copying a path on each keystroke costs nothing.
 */
data class Expr(val items: List<Item> = emptyList()) {
    val isEmpty: Boolean get() = items.isEmpty()

    companion object {
        val EMPTY = Expr()
    }
}

/** One element of an [Expr]. */
sealed interface Item

/** An atomic token, see [Tok]. */
data class TokenItem(val tok: Tok) : Item

/** `SHIFT 7` + two digits: scientific constant [number] (1…40), see [ScientificConstants]. */
data class ConstantItem(val number: Int) : Item

/** `SHIFT 8` + two digits: postfix metric conversion [number] (1…40), see [MetricConversions]. */
data class ConversionItem(val number: Int) : Item

/** A Natural-display template. `slots.size == kind.slotCount` always. */
data class Template(val kind: TemplateKind, val slots: List<Expr>) : Item {
    init {
        require(slots.size == kind.slotCount) { "$kind needs ${kind.slotCount} slots, got ${slots.size}" }
    }

    companion object {
        fun empty(kind: TemplateKind): Template = Template(kind, List(kind.slotCount) { Expr.EMPTY })
    }
}

private const val THREE = 3

/**
 * The Natural-display templates (research 02 §7.4). Slots are listed in the order the cursor
 * visits them with ▶ (research 02 §7.3), which is also their storage order.
 */
enum class TemplateKind(val slotCount: Int) {
    /** `FRAC`: [numerator, denominator]. */
    FRACTION(2),

    /** `SHIFT FRAC`: [whole, numerator, denominator]. */
    MIXED_FRACTION(THREE),

    /** `√`: [radicand]. */
    SQRT(1),

    /** `SHIFT √`: [radicand]. */
    CBRT(1),

    /** `SHIFT x^`: [index, radicand]. */
    NTH_ROOT(2),

    /** `x^`: [exponent]. Applies to the item before it, like the postfix tokens. */
    POWER(1),

    /** `log▪▯`: [base, argument]. */
    LOG_BASE(2),

    /** `SHIFT log`: [exponent] of 10. */
    TEN_POWER(1),

    /** `SHIFT ln`: [exponent] of e. */
    E_POWER(1),

    /** `SHIFT hyp`: [argument], drawn between bars. */
    ABS(1),

    /** `∫`: [integrand, lower, upper]. The cursor visits integrand → lower → upper. */
    INTEGRAL(THREE),

    /** `SHIFT ∫`: [function, x value]. */
    DERIVATIVE(2),

    /** `SHIFT log▪▯`: [function, lower, upper]. */
    SUMMATION(THREE),

    /**
     * Result display only, never typed: the `×10` and raised exponent of a number shown in
     * exponential form. [exponent digits, with a leading `-` token if negative].
     */
    SCI_EXPONENT(1),
}

/**
 * Where the cursor is: [path] leads from the root expression down through templates, and [index]
 * is the gap within the innermost expression (0 = before its first item, `items.size` = after the
 * last). An empty [path] means the root.
 */
data class Cursor(val path: List<PathStep> = emptyList(), val index: Int = 0)

/** Step into slot [slot] of the template at position [item] of the current expression. */
data class PathStep(val item: Int, val slot: Int)

/** A symbol drawn with an optional small subscript, as the LCD shows `mₚ`, `C₀`, `μ_B`. */
data class Symbol(val base: String, val subscript: String = "") {
    val text: String get() = base + subscript
}

/**
 * The 40 CONST entries (research 03 §14). Symbols VERIFIED from the guide; values are CODATA 2014
 * at their published digits (the guide names CODATA 2014 but prints no values), and three stored
 * negative. [value] is plain decimal text with an `E` exponent, parsed by the engine.
 */
object ScientificConstants {
    data class Entry(val number: Int, val symbol: Symbol, val name: String, val value: String)

    val all: List<Entry> = listOf(
        Entry(1, Symbol("m", "p"), "proton mass", "1.672621898E-27"),
        Entry(2, Symbol("m", "n"), "neutron mass", "1.674927471E-27"),
        Entry(3, Symbol("m", "e"), "electron mass", "9.10938356E-31"),
        Entry(4, Symbol("m", "μ"), "muon mass", "1.883531594E-28"),
        Entry(5, Symbol("a", "0"), "Bohr radius", "0.52917721067E-10"),
        Entry(6, Symbol("h"), "Planck constant", "6.626070040E-34"),
        Entry(7, Symbol("μ", "N"), "nuclear magneton", "5.050783699E-27"),
        Entry(8, Symbol("μ", "B"), "Bohr magneton", "927.4009994E-26"),
        Entry(9, Symbol("ℏ"), "rationalized Planck constant", "1.054571800E-34"),
        Entry(10, Symbol("α"), "fine-structure constant", "7.2973525664E-3"),
        Entry(11, Symbol("r", "e"), "classical electron radius", "2.8179403227E-15"),
        Entry(12, Symbol("λ", "c"), "Compton wavelength", "2.4263102367E-12"),
        Entry(13, Symbol("γ", "p"), "proton gyromagnetic ratio", "2.675221900E8"),
        Entry(14, Symbol("λ", "cp"), "proton Compton wavelength", "1.32140985396E-15"),
        Entry(15, Symbol("λ", "cn"), "neutron Compton wavelength", "1.31959090481E-15"),
        Entry(16, Symbol("R", "∞"), "Rydberg constant", "10973731.568508"),
        Entry(17, Symbol("u"), "atomic mass unit", "1.660539040E-27"),
        Entry(18, Symbol("μ", "p"), "proton magnetic moment", "1.4106067873E-26"),
        Entry(19, Symbol("μ", "e"), "electron magnetic moment", "-928.4764620E-26"),
        Entry(20, Symbol("μ", "n"), "neutron magnetic moment", "-0.96623650E-26"),
        Entry(21, Symbol("μ", "μ"), "muon magnetic moment", "-4.49044826E-26"),
        Entry(22, Symbol("F"), "Faraday constant", "96485.33289"),
        Entry(23, Symbol("e"), "elementary charge", "1.6021766208E-19"),
        Entry(24, Symbol("N", "A"), "Avogadro constant", "6.022140857E23"),
        Entry(25, Symbol("k"), "Boltzmann constant", "1.38064852E-23"),
        Entry(26, Symbol("V", "m"), "molar volume of ideal gas", "22.710947E-3"),
        Entry(27, Symbol("R"), "molar gas constant", "8.3144598"),
        Entry(28, Symbol("C", "0"), "speed of light in vacuum", "299792458"),
        Entry(29, Symbol("C", "1"), "first radiation constant", "3.741771790E-16"),
        Entry(30, Symbol("C", "2"), "second radiation constant", "1.43877736E-2"),
        Entry(31, Symbol("σ"), "Stefan-Boltzmann constant", "5.670367E-8"),
        Entry(32, Symbol("ε", "0"), "electric constant", "8.854187817E-12"),
        Entry(33, Symbol("μ", "0"), "magnetic constant", "1.2566370614E-6"),
        Entry(34, Symbol("φ", "0"), "magnetic flux quantum", "2.067833831E-15"),
        Entry(35, Symbol("g"), "standard acceleration of gravity", "9.80665"),
        Entry(36, Symbol("G", "0"), "conductance quantum", "7.7480917310E-5"),
        Entry(37, Symbol("Z", "0"), "characteristic impedance of vacuum", "376.730313461"),
        Entry(38, Symbol("t"), "Celsius temperature", "273.15"),
        Entry(39, Symbol("G"), "Newtonian constant of gravitation", "6.67408E-11"),
        Entry(40, Symbol("atm"), "standard atmosphere", "101325"),
    )

    fun get(number: Int): Entry? = all.getOrNull(number - 1)
}

/**
 * The 40 CONV commands (research 03 §15). Each pair shares one stored factor from the sibling
 * firmware's table; one direction multiplies and the other divides by the **same** factor. °F/°C
 * are affine.
 */
object MetricConversions {
    enum class Op { MULTIPLY, DIVIDE, FAHRENHEIT_TO_CELSIUS, CELSIUS_TO_FAHRENHEIT }

    data class Entry(val number: Int, val from: String, val to: String, val op: Op, val factor: String) {
        /** As drawn after the value: `cm▶in`. */
        val text: String get() = "$from▶$to"
    }

    val all: List<Entry> = listOf(
        Entry(1, "in", "cm", Op.MULTIPLY, "2.54"),
        Entry(2, "cm", "in", Op.DIVIDE, "2.54"),
        Entry(3, "ft", "m", Op.MULTIPLY, "0.3048"),
        Entry(4, "m", "ft", Op.DIVIDE, "0.3048"),
        Entry(5, "yd", "m", Op.MULTIPLY, "0.9144"),
        Entry(6, "m", "yd", Op.DIVIDE, "0.9144"),
        Entry(7, "mile", "km", Op.MULTIPLY, "1.609344"),
        Entry(8, "km", "mile", Op.DIVIDE, "1.609344"),
        Entry(9, "n mile", "m", Op.MULTIPLY, "1852"),
        Entry(10, "m", "n mile", Op.DIVIDE, "1852"),
        Entry(11, "acre", "m²", Op.MULTIPLY, "4046.856"),
        Entry(12, "m²", "acre", Op.DIVIDE, "4046.856"),
        Entry(13, "gal(US)", "ℓ", Op.MULTIPLY, "3.785412"),
        Entry(14, "ℓ", "gal(US)", Op.DIVIDE, "3.785412"),
        Entry(15, "gal(UK)", "ℓ", Op.MULTIPLY, "4.54609"),
        Entry(16, "ℓ", "gal(UK)", Op.DIVIDE, "4.54609"),
        Entry(17, "pc", "km", Op.MULTIPLY, "3.085678E13"),
        Entry(18, "km", "pc", Op.DIVIDE, "3.085678E13"),
        Entry(19, "km/h", "m/s", Op.DIVIDE, "3.6"),
        Entry(20, "m/s", "km/h", Op.MULTIPLY, "3.6"),
        Entry(21, "oz", "g", Op.MULTIPLY, "28.34952"),
        Entry(22, "g", "oz", Op.DIVIDE, "28.34952"),
        Entry(23, "lb", "kg", Op.MULTIPLY, "0.4535924"),
        Entry(24, "kg", "lb", Op.DIVIDE, "0.4535924"),
        Entry(25, "atm", "Pa", Op.MULTIPLY, "101325"),
        Entry(26, "Pa", "atm", Op.DIVIDE, "101325"),
        Entry(27, "mmHg", "Pa", Op.MULTIPLY, "133.3224"),
        Entry(28, "Pa", "mmHg", Op.DIVIDE, "133.3224"),
        Entry(29, "hp", "kW", Op.MULTIPLY, "0.7457"),
        Entry(30, "kW", "hp", Op.DIVIDE, "0.7457"),
        Entry(31, "kgf/cm²", "Pa", Op.MULTIPLY, "98066.5"),
        Entry(32, "Pa", "kgf/cm²", Op.DIVIDE, "98066.5"),
        Entry(33, "kgf•m", "J", Op.MULTIPLY, "9.80665"),
        Entry(34, "J", "kgf•m", Op.DIVIDE, "9.80665"),
        Entry(35, "lbf/in²", "kPa", Op.MULTIPLY, "6.894757"),
        Entry(36, "kPa", "lbf/in²", Op.DIVIDE, "6.894757"),
        Entry(37, "°F", "°C", Op.FAHRENHEIT_TO_CELSIUS, "1.8"),
        Entry(38, "°C", "°F", Op.CELSIUS_TO_FAHRENHEIT, "1.8"),
        Entry(39, "J", "cal", Op.DIVIDE, "4.1858"),
        Entry(40, "cal", "J", Op.MULTIPLY, "4.1858"),
    )

    fun get(number: Int): Entry? = all.getOrNull(number - 1)
}
