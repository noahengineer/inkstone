package dev.inkstone.core.calc.machine

import dev.inkstone.core.calc.lcd.LcdFrame
import dev.inkstone.core.calc.lcd.MenuItem
import dev.inkstone.core.calc.lcd.MenuStyle
import dev.inkstone.core.calc.lcd.PageArrows
import dev.inkstone.core.calc.lcd.Screens

/** One page of a numbered menu: [title] lines first, then [items] (research 02 §4). */
data class MenuPage(val items: List<MenuItem>, val title: List<String> = emptyList()) {
    companion object {
        /** Items numbered 1, 2, 3, … in order. */
        fun of(vararg labels: String, title: List<String> = emptyList()): MenuPage = numbered(labels.toList(), title)

        /** Items numbered 1, 2, 3, … in order. */
        fun numbered(labels: List<String>, title: List<String> = emptyList()): MenuPage =
            MenuPage(labels.mapIndexed { i, label -> MenuItem(i + 1, label) }, title)
    }
}

/**
 * Any numbered menu — MODE, SETUP, CLR, hyp, DRG▶, CMPLX, BASE, and every menu a mode adds
 * (research 02 §4 "Menus in general", VERIFIED): a **number key** selects an item on the page
 * shown, `▼`/`▲` change page (the ▼/▲ icons say another page exists), `AC` closes the menu.
 * Every other key is ignored.
 *
 * A digit is taken only without SHIFT or ALPHA ([Press.digit]) — ⚠️ GUESS: the guide never
 * presses one in a menu, and the latched key's own legend (CONST, CLR…) is what the firmware is
 * likelier to run. The global ones (`SHIFT 9`, `SHIFT MODE`) never reach a menu anyway.
 *
 * @param onSelect called with the page index (0-based) and the item number pressed.
 * @param onUnlisted called for a digit that is not an item on the page (`MODE 0` is the one known
 *   use, research 02 §4); null ignores such digits.
 * @param onCancel `AC`; by default the menu closes itself.
 */
class MenuScreen(
    private val host: Host,
    private val pages: List<MenuPage>,
    private val style: MenuStyle = MenuStyle.COLON,
    private val columns: Int = 2,
    private val onCancel: () -> Unit = host::pop,
    private val onUnlisted: ((page: Int, digit: Int) -> Unit)? = null,
    private val onSelect: (page: Int, number: Int) -> Unit,
) : Screen {
    init {
        require(pages.isNotEmpty()) { "a menu has at least one page" }
    }

    /** The page shown, 0-based. */
    var page: Int = 0
        private set

    override fun handle(press: Press) {
        val digit = press.digit
        when {
            press.command == Command.AC -> onCancel()
            press.command == Command.DOWN -> page = (page + 1).coerceAtMost(pages.lastIndex)
            press.command == Command.UP -> page = (page - 1).coerceAtLeast(0)
            digit < 0 -> Unit
            pages[page].items.any { it.number == digit } -> onSelect(page, digit)
            else -> onUnlisted?.invoke(page, digit)
        }
    }

    override fun render(): LcdFrame {
        val shown = pages[page]
        return Screens.menu(host.indicators(), shown.items, style, shown.title, columns, arrows())
    }

    private fun arrows(): PageArrows {
        val more = page < pages.lastIndex
        return when {
            page > 0 && more -> PageArrows.BOTH
            page > 0 -> PageArrows.UP
            more -> PageArrows.DOWN
            else -> PageArrows.NONE
        }
    }
}
