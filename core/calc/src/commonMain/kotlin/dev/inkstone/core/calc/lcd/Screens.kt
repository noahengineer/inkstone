package dev.inkstone.core.calc.lcd

import dev.inkstone.core.calc.CalcError
import dev.inkstone.core.calc.input.Cursor
import dev.inkstone.core.calc.input.Expr

/**
 * Every screen the key machine can show, as a finished [LcdFrame]. The machine decides *what* is
 * on screen (and which status icons are lit — pass them as `indicators`); this decides where each
 * dot goes. Each frame also carries [LcdFrame.text], the plain text of what is shown, line by
 * line, for TalkBack and for tests.
 *
 * Positions follow research 01 §3.6 as re-measured in [LcdGeometry] (one column right of §3.6's
 * figures; that file says why).
 */
object Screens {
    /**
     * The calculation screen of COMP and every mode that has one: the input line at the top and
     * the result, right-aligned, on the bottom line (research 02 §8.1).
     *
     * @param cursor where the cursor stands in [input]; null hides it (a result is showing).
     * @param natural Natural (MthIO) layout of the input; false draws LineIO spellings.
     * @param result the formatted result — a `FRACTION` template for a stacked fraction,
     *   `SCI_EXPONENT` for exponential form — or null for none.
     * @param naturalResult MathO layout of [result]; false for LineO/LineIO (linear text).
     * @param inputScroll from [MathLayout.inputScroll]; [resultScroll] up to [MathLayout.resultScrollLimit].
     * @param prefix fixed text drawn in front of the input, which the cursor cannot enter (`f(X)=`).
     * @param baseLabel BASE-N's `Dec`/`Hex`/`Bin`/`Oct`, small, right-aligned above the result (03 §5.1).
     */
    fun calculation(
        indicators: Set<Indicator>,
        input: Expr,
        cursor: Cursor?,
        natural: Boolean,
        result: Expr? = null,
        naturalResult: Boolean = natural,
        inputScroll: Int = 0,
        resultScroll: Int = 0,
        cursorShape: CursorShape = CursorShape.INSERT,
        prefix: String? = null,
        baseLabel: String? = null,
    ): LcdFrame {
        val canvas = LcdCanvas()
        val resultLine = result?.let { LaidOutLine(it, naturalResult) }
        val resultTop = resultLine?.let { LcdGeometry.RESULT_BOTTOM - it.box.height + 1 } ?: LcdGeometry.HEIGHT
        val inputLine = LaidOutLine(input, natural, prefix)
        val lcdCursor = LineRenderer.input(
            canvas,
            inputLine,
            cursor,
            inputScroll,
            LcdGeometry.INPUT_TOP,
            resultTop - 1,
            cursorShape,
        )
        resultLine?.let { LineRenderer.result(canvas, it, resultScroll, LcdGeometry.RESULT_BOTTOM) }
        baseLabel?.let {
            val font = LcdFont.SMALL
            canvas.text(font, it, LcdGeometry.WIDTH - font.width(it), resultTop - font.height - BASE_LABEL_GAP)
        }
        val text = listOfNotNull(
            (prefix ?: "") + MathLayout.plainText(input),
            baseLabel,
            result?.let { MathLayout.plainText(it) },
        )
        return canvas.freeze(indicators, lcdCursor, text.joinToString("\n"))
    }

    /**
     * Three lines: the input (or a fixed [title] such as INEQ's `X≤A;B≤X`) at the top, then up to
     * two label-and-value lines on rows 12–20 and 22–30 — the LineIO two-part results (`r=`/`θ=`,
     * the ÷R quotient over `R=`, research 02 §8.1), SOLVE's `X=`/`L-R=`, INEQ's `A=`/`B=`. Labels
     * are left, values right. One line goes on the bottom row; pass only the visible two.
     *
     * The row above the first value line stays blank, so an input taller than rows 1–10 rises to
     * row 0: SOLVE's `X²−6X=0` sits on rows 0–10 (SA slide 14). Hyphens in labels and the title are
     * drawn as the firmware's five-dot bar ([firmwareText]), so `L-R=` may be passed as typed.
     */
    fun stacked(
        indicators: Set<Indicator>,
        input: Expr?,
        lines: List<LabelledValue>,
        natural: Boolean = false,
        title: String? = null,
        cursor: Cursor? = null,
        inputScroll: Int = 0,
    ): LcdFrame {
        val canvas = LcdCanvas()
        val shown = lines.takeLast(2)
        val bottoms = listOf(LcdGeometry.MIDDLE_BOTTOM, LcdGeometry.RESULT_BOTTOM).takeLast(shown.size)
        // One blank row stays between the input and the first value line: SOLVE's `X²−6X=0` rises
        // to rows 0–10 to keep row 11 clear above `X=` (SA slide 14).
        val inputBottom = bottoms.firstOrNull()?.let { it - LcdFont.LARGE.height - 1 } ?: LcdGeometry.LAST_ROW
        var lcdCursor: LcdCursor? = null
        if (title != null) {
            canvas.text(LcdFont.LARGE, firmwareText(title), 0, LcdGeometry.INPUT_TOP)
        } else if (input != null) {
            val line = LaidOutLine(input, natural)
            lcdCursor = LineRenderer.input(canvas, line, cursor, inputScroll, LcdGeometry.INPUT_TOP, inputBottom)
        }
        shown.forEachIndexed { i, line -> labelled(canvas, line, bottoms[i], natural) }
        val text = listOfNotNull(title ?: input?.let { MathLayout.plainText(it) }) +
            shown.map { it.label + MathLayout.plainText(it.value) }
        return canvas.freeze(indicators, lcdCursor, text.joinToString("\n"))
    }

    /**
     * A numbered menu. [MenuStyle.COLON] puts [title] lines first, then the items, two per line
     * with the second at cell 8 (`1:Type  2:Data`), or one per line with [columns] = 1 (the EQN
     * and DIST type menus). [MenuStyle.MODE] is the MODE menu's look and ignores [title].
     * [arrows] lights the ▼/▲ status icons that say another page exists.
     */
    fun menu(
        indicators: Set<Indicator>,
        items: List<MenuItem>,
        style: MenuStyle = MenuStyle.COLON,
        title: List<String> = emptyList(),
        columns: Int = 2,
        arrows: PageArrows = PageArrows.NONE,
    ): LcdFrame {
        val canvas = LcdCanvas()
        val text = MenuRenderer.draw(canvas, items, style, title, columns.coerceIn(1, 2))
        return canvas.freeze(indicators + arrows.indicators, null, text)
    }

    /**
     * Prompts and messages: `Fix 0~9?`, `CONSTANT` / `Number 01~40?` / `[__]` (the field on
     * [TextRow.LARGE_FIELD], right-aligned), `Reset All?`, CONTRAST. Text is drawn as given: a `-`
     * here is the short `(−)` sign, because a message line may carry a value; write a firmware
     * hyphen as `−`.
     */
    fun text(indicators: Set<Indicator>, lines: List<TextLine>, arrows: PageArrows = PageArrows.NONE): LcdFrame {
        val canvas = LcdCanvas()
        lines.forEach { line -> textLine(canvas, line) }
        return canvas.freeze(indicators + arrows.indicators, null, lines.joinToString("\n") { it.text })
    }

    /**
     * An error, as research 02 §11 shows `Math ERROR`: the message on line 1, line 2 empty,
     * `[AC]  :Cancel` and `[◀][▶]:Goto` on lines 3 and 4, all small font. [goto] = false drops the
     * last line, for an error the machine cannot return to (whether any such error exists is
     * UNKNOWN; research 02 §11).
     */
    fun error(indicators: Set<Indicator>, error: CalcError, goto: Boolean = true): LcdFrame = text(
        indicators,
        listOfNotNull(
            TextLine(error.message, TextRow.SMALL_1),
            TextLine(CANCEL, TextRow.SMALL_3),
            if (goto) TextLine(GOTO, TextRow.SMALL_4) else null,
        ),
    )

    /**
     * A label top-left in the large font and a value bottom-right: the EQN solutions (`X₁=` … `-6`,
     * research 03 §6.2, measured), `X-Value Minimum=`, RATIO's `X=`, DIST's `p=` and its prompts
     * (`Normal PD:x?`), TABLE's `Start?`. With [cursor] the value is being typed: it is drawn
     * left-aligned on the bottom line with the cursor instead. Subscripts are glyphs (`X₁=`), and a
     * `-` in the label is the firmware's five-dot hyphen (`X-Value Minimum=`, SA slide 16).
     */
    fun labelValue(
        indicators: Set<Indicator>,
        label: String,
        value: Expr?,
        natural: Boolean = true,
        valueScroll: Int = 0,
        cursor: Cursor? = null,
        cursorShape: CursorShape = CursorShape.INSERT,
    ): LcdFrame {
        val canvas = LcdCanvas()
        canvas.text(LcdFont.LARGE, firmwareText(label), 0, LcdGeometry.INPUT_TOP)
        var lcdCursor: LcdCursor? = null
        if (value != null) {
            val line = LaidOutLine(value, natural)
            if (cursor == null) {
                LineRenderer.result(canvas, line, valueScroll, LcdGeometry.RESULT_BOTTOM)
            } else {
                val top = LcdGeometry.RESULT_BOTTOM - line.box.height + 1
                lcdCursor =
                    LineRenderer.input(canvas, line, cursor, valueScroll, top, LcdGeometry.LAST_ROW, cursorShape)
            }
        }
        val text = listOfNotNull(label, value?.let { MathLayout.plainText(it) }).joinToString("\n")
        return canvas.freeze(indicators, lcdCursor, text)
    }

    /** An editor or table: see [GridSpec]. */
    fun grid(indicators: Set<Indicator>, spec: GridSpec): LcdFrame {
        val canvas = LcdCanvas()
        val cursor = GridRenderer.draw(canvas, spec)
        return canvas.freeze(indicators, cursor, GridRenderer.text(spec))
    }

    /** Nothing in the matrix, only status icons: a long calculation running ([G p.9]). */
    fun blank(indicators: Set<Indicator>): LcdFrame = LcdCanvas().freeze(indicators, null, "")

    private fun labelled(canvas: LcdCanvas, line: LabelledValue, bottom: Int, natural: Boolean) {
        val font = LcdFont.LARGE
        val labelEnd = canvas.text(font, firmwareText(line.label), 0, bottom - font.height + 1)
        LineRenderer.result(canvas, LaidOutLine(line.value, natural), 0, bottom, left = labelEnd)
    }

    private fun textLine(canvas: LcdCanvas, line: TextLine) {
        val font = line.row.font
        val width = font.width(line.text)
        val x = when (line.align) {
            TextAlign.LEFT -> 0
            TextAlign.RIGHT -> LcdGeometry.WIDTH - width
            TextAlign.CENTER -> (LcdGeometry.WIDTH - width) / 2 / LcdFont.CELL * LcdFont.CELL
        }
        canvas.text(font, line.text, x, line.row.top)
    }

    private const val CANCEL = "[AC]  :Cancel"
    private const val GOTO = "[◀][▶]:Goto"

    /** Blank rows between a BASE-N label and the result under it: a guess, unmeasured. */
    private const val BASE_LABEL_GAP = 2
}
