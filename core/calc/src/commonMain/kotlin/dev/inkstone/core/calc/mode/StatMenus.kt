package dev.inkstone.core.calc.mode

import dev.inkstone.core.calc.input.Tok
import dev.inkstone.core.calc.input.TokenItem
import dev.inkstone.core.calc.machine.Host
import dev.inkstone.core.calc.machine.LineMenus
import dev.inkstone.core.calc.machine.MenuPage
import dev.inkstone.core.calc.machine.MenuScreen
import dev.inkstone.core.calc.machine.TypingTarget

/** The items of the `SHIFT 1` (STAT/DIST) menu, by their on-screen names (research 03 §4.1–4.2). */
internal enum class StatMenu(val label: String) {
    TYPE("Type"),
    DATA("Data"),
    EDIT("Edit"),
    SUM("Sum"),
    VAR("Var"),
    DISTR("Distr"),
    REG("Reg"),
    MIN_MAX("MinMax"),
}

/**
 * STAT's `SHIFT 1` menus (research 03 §4.2, 06 §6.4). Two lines of two items each, the sibling
 * firmware's layout (research 00 §4); the calculation screen's 1-VAR menu and its MinMax sub-menu
 * are VERIFIED from the SA captures (research 05 EX-SA-STAT-01), every item VERIFIED from the
 * guide's key sequences, and the sub-menus' layouts SECONDARY ([GYX] strings, research 03 §4.2).
 *
 * The **editor's** menu is the calculation screen's with `Edit` as item 3 — "So when the editor is
 * on screen, item 3 of the STAT/DIST menu is `Edit`" (research 03 §4.1: `SHIFT 1 3 1` inserts a
 * row, `SHIFT 1 3 2` deletes all). Everything after it moving down one place is ⚠️ GUESS: the
 * guide shows neither the editor's menu nor its items 4 onwards (research 03 §17 #3).
 */
internal object StatMenus {
    /** The type menu, two columns as screen `img36` shows (research 03 §4, VERIFIED). */
    val TYPES: List<MenuPage> = listOf(MenuPage.numbered(StatType.entries.map { it.label }))

    /** `1:Ins  2:Del-A` (research 03 §4.1, items VERIFIED from the key sequences, layout SECONDARY). */
    val EDIT: List<MenuPage> = listOf(MenuPage.of("Ins", "Del-A"))

    /** Edit's item 1, Ins; item 2 is Del-A. */
    const val INSERT = 1

    /** The `SHIFT 1` items for [type], on the editor ([editing]) or on the calculation screen. */
    fun entries(type: StatType, editing: Boolean): List<StatMenu> = buildList {
        add(StatMenu.TYPE)
        add(StatMenu.DATA)
        if (editing) add(StatMenu.EDIT)
        add(StatMenu.SUM)
        add(StatMenu.VAR)
        add(if (type.paired) StatMenu.REG else StatMenu.DISTR)
        add(StatMenu.MIN_MAX)
    }

    fun main(entries: List<StatMenu>): List<MenuPage> = listOf(MenuPage.numbered(entries.map { it.label }))

    /** A sub-menu whose item n types its token into [target], as `LineMenus.typing` does for every mode. */
    fun typing(host: Host, type: StatType, menu: StatMenu, target: TypingTarget): MenuScreen {
        val tokens = tokens(type, menu)
        val page = MenuPage.numbered(tokens.map { it.text })
        return LineMenus.typing(host, target, listOf(page), listOf(tokens.map(::TokenItem)))
    }

    /**
     * What each typing sub-menu holds (research 03 §4.2–4.3, VERIFIED pp.53–56). The paired Sum
     * items 6–8 are listed under linear regression only; that every paired type has them is
     * INFERRED (research 06 §6.4), as is the linear `Reg` for ln X … 1/X (VERIFIED for ln X).
     */
    fun tokens(type: StatType, menu: StatMenu): List<Tok> = when (menu) {
        StatMenu.SUM -> if (type.paired) PAIRED_SUMS else SINGLE_SUMS
        StatMenu.VAR -> if (type.paired) PAIRED_VARIABLES else SINGLE_VARIABLES
        StatMenu.DISTR -> DISTRIBUTION
        StatMenu.REG -> if (type == StatType.QUADRATIC) QUADRATIC_REGRESSION else REGRESSION
        StatMenu.MIN_MAX -> if (type.paired) PAIRED_EXTREMES else SINGLE_EXTREMES
        else -> emptyList()
    }

    private val SINGLE_SUMS = listOf(Tok.SUM_X2, Tok.SUM_X)
    private val PAIRED_SUMS = listOf(
        Tok.SUM_X2, Tok.SUM_X, Tok.SUM_Y2, Tok.SUM_Y, Tok.SUM_XY, Tok.SUM_X3, Tok.SUM_X2Y, Tok.SUM_X4,
    )
    private val SINGLE_VARIABLES = listOf(Tok.STAT_N, Tok.MEAN_X, Tok.SIGMA_X, Tok.SAMPLE_SX)
    private val PAIRED_VARIABLES = SINGLE_VARIABLES + listOf(Tok.MEAN_Y, Tok.SIGMA_Y, Tok.SAMPLE_SY)
    private val DISTRIBUTION = listOf(Tok.DIST_P, Tok.DIST_Q, Tok.DIST_R, Tok.TO_T)
    private val REGRESSION = listOf(Tok.REG_A, Tok.REG_B, Tok.REG_R, Tok.EST_X, Tok.EST_Y)

    /** "`5 Reg` = 1 `A`, 2 `B`, 3 `C`, 4 `x̂₁`, 5 `x̂₂`, 6 `ŷ`. There is no `r`" (research 03 §4.3). */
    private val QUADRATIC_REGRESSION = listOf(Tok.REG_A, Tok.REG_B, Tok.REG_C, Tok.EST_X1, Tok.EST_X2, Tok.EST_Y)
    private val SINGLE_EXTREMES = listOf(Tok.MIN_X, Tok.MAX_X, Tok.Q1, Tok.MEDIAN, Tok.Q3)
    private val PAIRED_EXTREMES = listOf(Tok.MIN_X, Tok.MAX_X, Tok.MIN_Y, Tok.MAX_Y)
}
