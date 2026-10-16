package dev.inkstone.app.calculator

import dev.inkstone.core.model.PageCanvas
import dev.inkstone.core.model.PagePoint
import dev.inkstone.core.model.PageRect
import kotlin.math.ceil

/**
 * ⭐ Where a dropped answer's text box goes, and what it says — the decisions of docs/CALCULATOR.md
 * §6, hoisted out of Compose so a JVM test can reach them (CLAUDE.md: *"hoist the decision into a pure
 * function over plain data"*).
 */
internal object AnswerPlacement {
    /**
     * The text box for an answer whose text measures [textWidthPt] × [textHeightPt], let go with the
     * finger at [centre] — centred on the finger, as the ghost was, so it lands where it was seen.
     *
     * - **Off a fixed sheet, nothing** (null): a box beside the paper is drawn by the app and by no PDF
     *   reader (SPEC §4.5; CLAUDE.md's *"you could write beside the page"*). An infinite page grows to
     *   hold it, exactly as it does for ink, so there every drop lands.
     * - **On a fixed sheet, slid inside it** when the text would overhang an edge: the user aimed at
     *   the paper, and the overhang would be cut from the published page.
     * - **The frame hugs the text, plus [SLACK_PT]**, rather than taking the Text tool's 220-pt default:
     *   the frame is what a tap and a lasso hit (`DryTextBox`), and a number should be grabbed where it
     *   is. The slack is there because the frame is also the wrap width, and the ghost is measured by
     *   Compose while the box is laid out by `StaticLayout` — a hair's disagreement must not wrap
     *   `55,55555556` onto two lines.
     */
    fun boxFor(centre: PagePoint, textWidthPt: Float, textHeightPt: Float, canvas: PageCanvas): PageRect? {
        if (!canvas.infinite && !canvas.contains(centre.x, centre.y)) return null
        val width = ceil(textWidthPt + SLACK_PT)
        val height = ceil(textHeightPt + SLACK_PT / 2f)
        var left = centre.x - textWidthPt / 2f
        var top = centre.y - textHeightPt / 2f
        if (!canvas.infinite) {
            left = left.coerceIn(canvas.left, maxOf(canvas.left, canvas.right - width))
            top = top.coerceIn(canvas.top, maxOf(canvas.top, canvas.bottom - height))
        }
        return PageRect(left, top, left + width, top + height)
    }

    /**
     * [answer] as it is written on the page — by hand or in a text box, the same words either way.
     *
     * - **A decimal point, not the LCD's comma** (`55,5` → `55.5`): the user's choice, 2026-10-16. The
     *   ZA PLUS II has no Dot/Comma setting (research 02 Z3/Z4), so the comma is the display's and the
     *   point is the note's. Nothing else an answer can show contains a comma: the engine separates two
     *   values with `;` and two parts with a new line.
     * - **CMPLX's polar `∠` is written ` ang `**: the published PDF embeds `LiberationSans-Regular` and
     *   **drops** what that face cannot represent, so a character can be on screen and missing from the
     *   file (research 08 §5.2, measured against the pinned PdfBox-Android). Of what the engine's plain
     *   spelling can show (`1,25×10^-3`, `1/200`, `√(3)`), `∠` is the one outside the face.
     */
    fun forPage(answer: String): String = answer.replace(',', '.').replace("∠", " ang ")

    /**
     * Where handwriting starts for an answer that would sit in [box]: its first baseline's left end
     * (`Placement.origin`), [baselinePt] below the box's top — the ghost's baseline, so the hand writes
     * on the line the ghost's text sat on.
     */
    fun baselineStart(box: PageRect, baselinePt: Float): PagePoint = PagePoint(box.left, box.top + baselinePt)

    /**
     * The characters `LiberationSans-Regular` carries that an answer can contain: printable ASCII plus
     * research 08 §5.2's "present" column. ⚠️ Pinned here because `PdfCidFont.canRepresent` is
     * `internal` to `:android:pdf`; `AnswerPlacementTest` holds answers typed on the real engine to it.
     */
    val PDF_ALPHABET: Set<Char> = (' '..'~').toSet() + "×÷−√π²³¹°∫Σ∑∞≈θμ·ⁿ⁄ˉ".toSet()

    /** One em of the text box's default 14 pt, past the end of the text — see [boxFor]. */
    const val SLACK_PT = 14f
}
