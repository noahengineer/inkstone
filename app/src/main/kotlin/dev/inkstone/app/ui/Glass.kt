package dev.inkstone.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import kotlin.math.pow
import kotlin.math.roundToInt
import androidx.compose.ui.graphics.Canvas as GraphicsCanvas

/**
 * ⭐ ROADMAP M10 — a floating pill of controls.
 *
 * Every piece of chrome in the redesign is built from this file. The brief's architecture is *"four
 * non-intrusive floating zones"* made of two shapes — a pill of chips and a rounded panel — so those
 * are the two containers here and everything else is what goes inside them. Keeping the vocabulary
 * this small is what makes the dock, the page bar, the three contextual toolbars and the two side
 * panels look like one system rather than six. See [Ink] for why none of it is blurred.
 *
 * @param scrollable when true the row scrolls sideways rather than being clipped. On by default for
 *   the same reason the reference design carries a `scroll-hidden` class: the primary row is eight
 *   chips wide, which fits a Galaxy Tab in landscape and does not fit one held in portrait, and a
 *   toolbar whose last two tools are simply *gone* on the axis the user happens to be holding is
 *   CLAUDE.md's definition-of-done #6 caused by a layout decision.
 *
 * ## ⚠️ Scrolling is only half an answer, and the other half is [scrollEdgeFade]
 *
 * A bar that scrolls and shows no sign of it is a bar whose last two chips are *gone* to anyone who
 * does not already know they are there — which is the failure the paragraph above claims to have
 * designed out, arriving one step later. Measured in portrait: the dock's brush row carries `SNAP`
 * and `ARROW` past the right-hand edge and `uiautomator dump` cannot see them, and the library's
 * toolbar loses `TRASH` the same way. Worse, an overflowing bar fills the whole width it is given,
 * so both of its rounded ends are cut square against the screen — the one shape that says *"this
 * pill continues"* is the first thing an overflow removes, and what is left reads as broken chrome
 * rather than as a scroll. The fade puts that signal back at whichever end has more to show.
 */
@Composable
fun GlassBar(
    modifier: Modifier = Modifier,
    scrollable: Boolean = true,
    shape: Shape = InkDims.Pill,
    content: @Composable RowScope.() -> Unit,
) {
    val scroll = rememberScrollState()
    // ⚠️ The scroll and the fade are on **different** elements, and they have to be: a fade painted
    // inside the scrolling row would scroll away with the chips it is meant to be covering. The Box
    // wraps its content, so an under-full bar is still exactly as wide as its chips.
    Box(
        modifier = modifier
            .shadow(BarElevation, shape, clip = false)
            .background(Ink.Panel, shape)
            .border(HairlineWidth, Ink.PanelBorder, shape)
            .clip(shape)
            .then(if (scrollable) Modifier.scrollEdgeFade(scroll) else Modifier),
    ) {
        Row(
            modifier = Modifier
                .then(if (scrollable) Modifier.horizontalScroll(scroll) else Modifier)
                .padding(horizontal = InkDims.BarPadding + InkDims.ChipGap, vertical = InkDims.BarPadding),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(InkDims.ChipGap),
            content = content,
        )
    }
}

/**
 * ⭐ [GlassBar]'s material, for a bar laid out as a column or a grid rather than a row.
 *
 * Panel corners rather than a pill: a pill's ends are half its *height*, so a tall column drawn as one
 * would be a lozenge with semicircular caps half a toolbar tall. It scrolls vertically when it does not
 * fit, for the reason [GlassBar] scrolls sideways — a toolbar whose last chips are simply gone is
 * definition-of-done #6 caused by a layout decision.
 *
 * @param columns how many chips go across; 1 is a column.
 */
@Composable
fun GlassBlock(columns: Int, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val shape = InkDims.PanelCorner
    Box(
        modifier = modifier
            .shadow(BarElevation, shape, clip = false)
            .background(Ink.Panel, shape)
            .border(HairlineWidth, Ink.PanelBorder, shape)
            .clip(shape),
    ) {
        ChipGrid(
            columns = columns,
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = InkDims.BarPadding + InkDims.ChipGap, vertical = InkDims.BarPadding),
            content = content,
        )
    }
}

/**
 * Chips in [columns] columns, in reading order: each column as wide as its widest chip, each row as
 * tall as its tallest, every chip centred in its cell, [InkDims.ChipGap] between them.
 *
 * Hand-written rather than `FlowRow`, because a grid's cells have to line up down the page and a flow
 * row does not — a `Fit width` chip would push the next row's second chip out of line with the first's.
 */
@Composable
fun ChipGrid(columns: Int, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Layout(content = content, modifier = modifier) { measurables, constraints ->
        val gap = InkDims.ChipGap.roundToPx()
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val placeables = measurables.map { it.measure(loose) }
        if (placeables.isEmpty()) return@Layout layout(constraints.minWidth, constraints.minHeight) {}
        val across = columns.coerceIn(1, placeables.size)
        val rows = placeables.chunked(across)
        val widths = IntArray(across) { col -> rows.maxOf { row -> row.getOrNull(col)?.width ?: 0 } }
        val heights = rows.map { row -> row.maxOf { it.height } }
        val width = widths.sum() + gap * (across - 1)
        val height = heights.sum() + gap * (rows.size - 1).coerceAtLeast(0)
        layout(width.coerceAtLeast(constraints.minWidth), height.coerceAtLeast(constraints.minHeight)) {
            var y = 0
            rows.forEachIndexed { r, row ->
                var x = 0
                row.forEachIndexed { c, placeable ->
                    placeable.place(x + (widths[c] - placeable.width) / 2, y + (heights[r] - placeable.height) / 2)
                    x += widths[c] + gap
                }
                y += heights[r] + gap
            }
        }
    }
}

/** A divider between groups of chips stacked down a column — [ChipDivider] turned on its side. */
@Composable
fun ChipDividerAcross(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .padding(vertical = InkDims.ChipGap)
            .width(DividerHeight)
            .height(HairlineWidth)
            .background(Ink.Hairline),
    )
}

/**
 * Fades whichever end of a scrolling bar has content beyond it, and neither end when it does not.
 *
 * The gradient runs to [Ink.Panel] — the bar's own translucent fill — rather than to an opaque
 * colour, so a chip slides *under* the bar's edge instead of behind a lighter patch of a different
 * material. At 90 % alpha the chip under the far end is all but gone, which is the signal; at the
 * inner end of the ramp it is untouched, which keeps the eight chips that *are* on screen legible.
 *
 * ⚠️ `drawWithCache` rather than `drawWithContent`, so the two gradients are built once per size
 * change and not once per frame. The `if`s read `scroll.value`, so a bar that fits — which is every
 * bar in landscape — draws nothing extra at all. This is chrome and not the ink path, but it sits
 * one composable away from it and CLAUDE.md rule 7 is cheap to honour here.
 */
private fun Modifier.scrollEdgeFade(scroll: ScrollState): Modifier = drawWithCache {
    val width = ScrollFade.toPx()
    val start = Brush.horizontalGradient(listOf(Ink.Panel, Color.Transparent), startX = 0f, endX = width)
    val end = Brush.horizontalGradient(
        listOf(Color.Transparent, Ink.Panel),
        startX = size.width - width,
        endX = size.width,
    )
    onDrawWithContent {
        drawContent()
        if (scroll.value > 0) drawRect(start, size = Size(width, size.height))
        if (scroll.value < scroll.maxValue) {
            drawRect(end, topLeft = Offset(size.width - width, 0f), size = Size(width, size.height))
        }
    }
}

/**
 * A floating panel: the colour picker, the Elements library, the diagnostics readouts.
 *
 * Opaque rather than translucent ([Ink.PanelSolid]), because a panel holds *content* — a hex field,
 * a list of saved elements, a page of numbers — and translucency over handwriting makes small text
 * unreadable in exactly the case the panel exists for.
 */
@Composable
fun GlassPanel(
    modifier: Modifier = Modifier,
    shape: Shape = InkDims.PanelCorner,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .shadow(PanelElevation, shape, clip = false)
            .background(Ink.PanelSolid, shape)
            .border(HairlineWidth, Ink.PanelBorder, shape)
            .clip(shape)
            .padding(PanelPadding),
        verticalArrangement = Arrangement.spacedBy(InkDims.GroupGap),
        content = content,
    )
}

/**
 * What a chip's label *is*, which decides how it is set.
 *
 * ⚠️ One value rather than two booleans, and that is not tidiness — it was two booleans for an
 * afternoon and the mistake it allowed shipped: the page arrows were marked as [VALUE], which is the
 * monospaced measurement face, so `‹` came out as a hairline centred in an empty 13 sp cell and was
 * reported as *"very small and hard to see"*. A symbol and a measurement want opposite things from a
 * font, and *"neither"* and *"both"* are not answers.
 */
enum class ChipKind {
    /** A control's name. Uppercased and tracked out — the design's default and the common case. */
    WORD,

    /**
     * A measurement or a piece of the user's own text: `4 dp`, `45°`, `3 of 12`, a folder's name.
     * Monospaced so a changing digit does not re-flow its neighbours, and never uppercased, because
     * uppercasing a measurement changes what it says.
     */
    VALUE,

    /**
     * A symbol standing alone: `‹`, `›`, `◀`, `▶`, `✕`, `⋯`. Set larger, in the normal face, and a
     * shade brighter than a word — a few strokes have to carry what six letters otherwise would.
     */
    GLYPH,
}

/**
 * ⭐ The chip the whole design is made of — *"explicit text chips over ambiguous icons"*.
 *
 * @param kind how the label is set. See [ChipKind]; [ChipKind.WORD] is the design's default voice.
 * @param danger paints the label red. For a chip that *does* the destructive thing — never for one
 *   that merely opens a confirmation.
 * @param contentDescription ⚠️ **required for every [ChipKind.GLYPH]**. The design's whole premise is
 *   words rather than icons, so most chips need nothing here; a symbol would otherwise reach the
 *   accessibility tree as a bare codepoint, which TalkBack cannot say and `uiautomator dump` cannot
 *   tell apart from any other glyph — and driving the app over `adb` is CLAUDE.md's
 *   definition-of-done #6 check.
 */
@Composable
fun TextChip(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    enabled: Boolean = true,
    danger: Boolean = false,
    kind: ChipKind = ChipKind.WORD,
    contentDescription: String? = null,
) {
    val content = when {
        !enabled -> Ink.TextMuted
        selected -> Ink.OnAccent
        danger -> Ink.Danger
        kind == ChipKind.GLYPH -> Ink.TextStrong
        else -> Ink.TextNormal
    }
    Box(
        modifier = modifier
            .then(
                contentDescription
                    ?.let { name -> Modifier.semantics { this.contentDescription = name } }
                    ?: Modifier,
            )
            .clip(InkDims.ChipCorner)
            // ⚠️ **`selected && enabled`, not `selected`.** A chip that is both — "Sign in" while a
            // sign-in is already in flight, "Create" before a folder has been named — keeps the
            // inverted near-white fill while refusing the press, which is the most emphatic way a
            // UI has of saying *this is the thing to do* attached to a control that does nothing.
            // Found by the M10 review pass in two places in `SyncScreen` on the day the component
            // was written; it is a property of the component, so it is fixed here.
            .background(if (selected && enabled) Ink.Accent else Color.Transparent)
            .clickable(enabled = enabled, onClick = onClick)
            .defaultMinSize(minHeight = InkDims.ChipMinHeight)
            .padding(horizontal = InkDims.ChipPaddingH, vertical = InkDims.ChipPaddingV),
        contentAlignment = Alignment.Center,
    ) {
        ChipLabel(label, kind, content)
    }
}

/** How a [ChipKind] is actually set. Split out so [TextChip] stays a layout function. */
@Composable
private fun ChipLabel(label: String, kind: ChipKind, color: Color) {
    Text(
        text = if (kind == ChipKind.WORD) label.uppercase() else label,
        color = color,
        fontSize = if (kind == ChipKind.GLYPH) InkType.GlyphSize else InkType.ChipSize,
        fontWeight = FontWeight.Medium,
        letterSpacing = when (kind) {
            ChipKind.WORD -> InkType.ChipTracking
            ChipKind.VALUE -> InkType.MicroTracking
            ChipKind.GLYPH -> TextUnit.Unspecified
        },
        fontFamily = if (kind == ChipKind.VALUE) ReadoutFamily else null,
        maxLines = 1,
    )
}

/**
 * `.segmented` — a recessed track holding mutually exclusive options.
 *
 * Used where the choice is *between* things (partial vs whole erase, lasso vs marquee, left vs
 * centre vs right) and a plain chip row would leave the un-chosen options looking merely unpressed.
 */
@Composable
fun Segmented(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = modifier
            .clip(InkDims.SegmentCorner)
            .background(Ink.SegmentTrack)
            .border(HairlineWidth, Ink.PanelBorder, InkDims.SegmentCorner)
            .padding(SegmentTrackPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SegmentTrackPadding),
        content = content,
    )
}

/** One option inside a [Segmented]. Its selected fill is lighter than the track, never inverted. */
@Composable
fun SegmentChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Box(
        modifier = modifier
            .clip(InkDims.SegmentInnerCorner)
            .background(if (selected) Ink.SegmentActive else Color.Transparent)
            .clickable(enabled = enabled, onClick = onClick)
            .defaultMinSize(minHeight = SegmentChipHeight)
            .padding(horizontal = SegmentChipPaddingH, vertical = SegmentChipPaddingV),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label.uppercase(),
            color = when {
                !enabled -> Ink.TextMuted
                selected -> Color.White
                else -> Ink.TextNormal
            },
            fontSize = InkType.ChipSize,
            fontWeight = FontWeight.Medium,
            letterSpacing = InkType.ChipTracking,
            maxLines = 1,
        )
    }
}

/**
 * `.micro-label` — a static caption. Uppercase, tracked, muted.
 *
 * ⚠️ **One line, and it ellipsises rather than clipping.** A caption in this face is monospaced *and*
 * tracked out, so it is roughly 40 % wider than the same words in the body font — which makes
 * overflow the normal case in a 280 dp side panel rather than the exceptional one. Clipping ends a
 * caption mid-glyph with nothing to say it was cut; an ellipsis at least tells the reader there is
 * more.
 */
@Composable
fun MicroLabel(text: String, modifier: Modifier = Modifier, color: Color = Ink.TextMuted) {
    Text(
        text = text.uppercase(),
        modifier = modifier,
        color = color,
        fontSize = InkType.MicroSize,
        fontFamily = ReadoutFamily,
        letterSpacing = InkType.MicroTracking,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/**
 * A live number — zoom, revision, angle, elapsed time.
 *
 * Monospaced and **not** uppercased: this is a value, and a value that re-flows its neighbours every
 * time a digit changes is the reason a whole bar appears to twitch while a recording counts.
 *
 * ⚠️ **It ellipsises.** Not every readout is a short number — the sync browser puts a WebDAV path
 * through this — and a monospaced line clipped at `maxLines` with the default `TextOverflow.Clip`
 * ends mid-glyph with nothing on screen to say it was cut. The path is the one thing on that screen
 * a user has to read exactly.
 */
@Composable
fun Readout(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Ink.TextNormal,
    fontSize: TextUnit = InkType.ReadoutSize,
    maxLines: Int = 1,
    textAlign: TextAlign? = null,
) {
    Text(
        text = text,
        modifier = modifier,
        color = color,
        fontSize = fontSize,
        fontFamily = ReadoutFamily,
        maxLines = maxLines,
        // ⚠️ Only meaningful when a caller has imposed a **width**, which several do so the bar does
        // not re-flow as a digit changes. Left at the default, "94%" sat against the left edge of a
        // 54 dp box and the 20 dp of slack read as a random gap before the divider beside it.
        textAlign = textAlign,
        overflow = TextOverflow.Ellipsis,
    )
}

/** `w-px h-4 bg-zinc-700` — the rule that groups chips inside one pill. */
@Composable
fun ChipDivider(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .padding(horizontal = InkDims.ChipGap)
            .width(HairlineWidth)
            .height(DividerHeight)
            .background(Ink.Hairline),
    )
}

/**
 * One of the design's inline colour slots.
 *
 * ⚠️ The active ring is [Ink.Focus] and the swatch grows, because a swatch cannot indicate itself
 * with its own fill — a chosen white slot and an unchosen one are otherwise the same pixels.
 *
 * ## Dragging one colour onto another
 *
 * Pass [drag] and a [slot], and the swatch becomes both ends of a drag-and-drop: it reports itself
 * as a landing place, and a press-and-hold on it picks its colour up. Both, deliberately — every
 * swatch in the app is the same twelve-slot palette seen from a different place, so *"drag this
 * one onto that one"* has to work in whichever direction the user tries it.
 *
 * ⚠️ **`detectDragGesturesAfterLongPress`, not `detectDragGestures`.** A swatch's whole job is to be
 * tapped, and a plain drag detector consumes the pointer from the first pixel of movement — so a tap
 * with any tremor in it would pick the colour up instead of choosing it. The long press is what
 * keeps the common gesture free.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ColorSwatch(
    argb: Int,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = InkDims.SwatchSize,
    onLongClick: (() -> Unit)? = null,
    drag: ColorDragState? = null,
    slot: Int = -1,
    dragKey: String = "",
    onDropped: ((from: Int, to: Int) -> Unit)? = null,
) {
    var origin by remember { mutableStateOf(Offset.Zero) }
    val landing = drag?.colorArgb != null && drag.slotUnder() == slot && slot >= 0
    val ringed = selected || landing

    if (drag != null && slot >= 0) {
        DisposableEffect(dragKey) { onDispose { drag.forgetTarget(dragKey) } }
    }

    Box(
        modifier = modifier
            .size(if (ringed) size + SwatchGrowth else size)
            .then(
                if (drag != null && slot >= 0) {
                    Modifier.onGloballyPositioned {
                        origin = it.positionInRoot()
                        drag.reportTarget(dragKey, slot, it.boundsInRoot())
                    }
                } else {
                    Modifier
                },
            )
            .background(Color(argb), CircleShape)
            .border(
                width = if (ringed) SwatchRingWidth else HairlineWidth,
                // A landing swatch is near-white rather than sky, so "you are about to drop here"
                // never looks like "this is the colour in use".
                color = if (landing) {
                    Ink.Accent
                } else if (selected) {
                    Ink.Focus
                } else {
                    Ink.Hairline
                },
                shape = CircleShape,
            )
            .then(pickUp(drag, slot, argb, { origin }, onDropped))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .semantics { contentDescription = "Colour %06X".format(argb and RGB_MASK) },
    )
}

/**
 * Press-and-hold a swatch to carry its colour to another one.
 *
 * ⚠️ `detectDragGesturesAfterLongPress`, not `detectDragGestures`. A swatch's whole job is to be
 * tapped, and a plain drag detector claims the pointer on the first pixel of movement — so a tap
 * with any tremor in it would pick the colour up instead of choosing it.
 *
 * [origin] is read through a lambda rather than captured, because it is only known after the first
 * layout pass and a captured `Offset.Zero` would put every drag's coordinates in the wrong space.
 */
private fun pickUp(
    drag: ColorDragState?,
    slot: Int,
    argb: Int,
    origin: () -> Offset,
    onDropped: ((Int, Int) -> Unit)?,
): Modifier {
    if (drag == null || slot < 0 || onDropped == null) return Modifier
    return Modifier.pointerInput(slot, argb) {
        detectDragGesturesAfterLongPress(
            onDragStart = { at -> drag.begin(slot, argb, origin() + at) },
            onDrag = { change, _ -> drag.moveTo(origin() + change.position) },
            onDragEnd = {
                val to = drag.release()
                if (to >= 0 && to != slot) onDropped(slot, to)
            },
            onDragCancel = { drag.cancel() },
        )
    }
}

/**
 * The design's *"3–4 quick-access circular color slots"* plus the `+` that summons the full picker.
 *
 * @param onMore null hides the `+`. It is handed the `+`'s own bounds **in root coordinates**, so the
 *   panel it opens can be placed under the control that opened it rather than parked in a corner —
 *   which is what the design asks for and what a user pressing a `+` expects. The strip cannot host
 *   the panel itself: it lives inside a [GlassBar], which clips and scrolls, so anything drawn from
 *   here would be cut off at the pill's edge.
 */
@Composable
fun SwatchStrip(
    colors: List<Int>,
    selectedArgb: Int,
    onPick: (Int) -> Unit,
    modifier: Modifier = Modifier,
    onMore: ((Rect) -> Unit)? = null,
    drag: ColorDragState? = null,
    onDropped: ((from: Int, to: Int) -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .clip(InkDims.Pill)
            .background(Ink.SegmentTrack)
            .border(HairlineWidth, Ink.PanelBorder, InkDims.Pill)
            .padding(SwatchStripPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(InkDims.ChipGap),
    ) {
        // ⚠️ Indexed, because these *are* slots 0..n of the palette — the strip is the first few of
        // the same twelve the panel shows, not a copy — and a drop has to know which one it landed
        // on. The `quick:` prefix is what keeps its slot 0 apart from the panel's slot 0 when both
        // are on screen; see [ColorDragState].
        colors.forEachIndexed { index, argb ->
            ColorSwatch(
                argb = argb,
                selected = argb == selectedArgb,
                onClick = { onPick(argb) },
                drag = drag,
                slot = index,
                dragKey = "quick:$index",
                onDropped = onDropped,
            )
        }
        onMore?.let { more ->
            // Its own bounds, recorded every time it is laid out. The bar it sits in scrolls and the
            // page under it pans, so a position captured once would open the panel where the `+`
            // used to be.
            var bounds by remember { mutableStateOf(Rect.Zero) }
            Box(
                modifier = Modifier
                    .size(InkDims.SwatchSize)
                    .onGloballyPositioned { bounds = it.boundsInRoot() }
                    .background(Ink.PanelRaised, CircleShape)
                    .border(HairlineWidth, Ink.Hairline, CircleShape)
                    .clip(CircleShape)
                    .clickable { more(bounds) }
                    .semantics { contentDescription = MORE_COLOURS },
                contentAlignment = Alignment.Center,
            ) {
                Text("+", color = Ink.TextNormal, fontSize = InkType.ChipSize)
            }
        }
    }
}

/**
 * The design's *"draggable numerical readout"* — a 3 dp track and a 14 dp thumb.
 *
 * ⚠️ Hand-drawn rather than a themed `Slider`, and that is not decoration. Material's slider is a
 * 20 dp thumb inside a 48 dp touch row with its own ripple; dropped into a pill beside six chips it
 * is half the bar's height and *sets* the bar's height. The tap target is preserved by the
 * transparent [SliderRowHeight] box around the 3 dp line, which is the part that actually matters.
 */
@Composable
fun SlimSlider(
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    label: String = "",
    width: Dp = SliderWidth,
    ticks: List<Float> = emptyList(),
    gamma: Float = 1f,
) {
    val density = LocalDensity.current
    val thumbRadiusPx = with(density) { SliderThumb.toPx() } / 2f
    val trackPx = with(density) { SliderTrack.toPx() }
    val hairlinePx = with(density) { HairlineWidth.toPx() }
    val tickRadiusPx = with(density) { SliderTickRadius.toPx() }
    val span = (valueRange.endInclusive - valueRange.start).takeIf { it > 0f } ?: 1f
    // ⚠️ [gamma] bends the *position*, never the value. `at` maps a value to 0..1 along the track
    // and `valueAt` is its exact inverse, so a thumb dropped on a tick reads back as that tick's
    // value to the last float — which is what lets the magnet and the marks agree.
    val at: (Float) -> Float = { v -> ((v - valueRange.start) / span).coerceIn(0f, 1f).pow(gamma) }
    val valueAt: (Float) -> Float = { f -> valueRange.start + f.coerceIn(0f, 1f).pow(1f / gamma) * span }
    val emit: (Float, Int) -> Unit = { x, widthPx ->
        val usable = (widthPx - 2f * thumbRadiusPx).coerceAtLeast(1f)
        onValueChange(valueAt((x - thumbRadiusPx) / usable))
    }
    val fraction = at(value)

    Canvas(
        modifier = modifier
            .width(width)
            .height(SliderRowHeight)
            .semantics { contentDescription = label }
            .pointerInput(valueRange) {
                detectTapGestures { position -> emit(position.x, size.width) }
            }
            .pointerInput(valueRange) {
                detectHorizontalDragGestures { change, _ -> emit(change.position.x, size.width) }
            },
    ) {
        val trackY = size.height / 2f
        val thumbX = thumbRadiusPx + (size.width - 2f * thumbRadiusPx) * fraction
        drawRoundRect(
            color = Ink.Hairline,
            topLeft = Offset(0f, trackY - trackPx / 2f),
            size = Size(size.width, trackPx),
            cornerRadius = CornerRadius(trackPx),
        )
        drawRoundRect(
            color = Ink.TextStrong,
            topLeft = Offset(0f, trackY - trackPx / 2f),
            size = Size(thumbX, trackPx),
            cornerRadius = CornerRadius(trackPx),
        )
        // ⭐ M10 — the saved sizes, marked on the track the slider snaps them onto.
        //
        // ⚠️ A snap the user cannot see is indistinguishable from a slider that sticks. The marks
        // are what turn "it jumped" into "it landed on 4 dp", and they are drawn *under* the thumb
        // so the one you are currently on is covered rather than fighting it.
        ticks.forEach { tick ->
            val x = thumbRadiusPx + (size.width - 2f * thumbRadiusPx) * at(tick)
            drawCircle(color = Ink.TextMuted, radius = tickRadiusPx, center = Offset(x, trackY))
        }
        drawCircle(color = Ink.TextStrong, radius = thumbRadiusPx, center = Offset(thumbX, trackY))
        drawCircle(
            color = Ink.Canvas,
            radius = thumbRadiusPx,
            center = Offset(thumbX, trackY),
            style = Stroke(width = hairlinePx),
        )
    }
}

/**
 * The canvas's dot grid, as a single repeating shader.
 *
 * ⚠️ **One draw call, not seven thousand.** A `drawBehind` that loops `drawCircle` over a
 * 2560 × 1600 tablet at 24 dp spacing records ~7,000 operations into the display list and replays
 * every one of them each frame — behind the ink surface, which is precisely where CLAUDE.md rule 7
 * says nothing heavy may go. A tiled `ImageShader` is one textured rectangle.
 */
@Composable
fun rememberDotGridBrush(
    color: Color = Ink.CanvasDot,
    spacing: Dp = InkDims.DotSpacing,
    radius: Dp = InkDims.DotRadius,
): Brush {
    val density = LocalDensity.current
    return remember(color, spacing, radius, density) {
        val side = with(density) { spacing.toPx() }.roundToInt().coerceAtLeast(MIN_TILE_SIDE)
        val dot = with(density) { radius.toPx() }.coerceAtLeast(1f)
        val tile = ImageBitmap(side, side)
        val paint = Paint()
        paint.color = color
        paint.isAntiAlias = true
        GraphicsCanvas(tile).drawCircle(Offset(dot, dot), dot, paint)
        ShaderBrush(ImageShader(tile, TileMode.Repeated, TileMode.Repeated))
    }
}

/** How far a scrolling bar fades at an end that has content beyond it. See `scrollEdgeFade`. */
private val ScrollFade = 22.dp

private val BarElevation = 8.dp
private val PanelElevation = 14.dp

/** `internal` so the calculator window can size its face to fit inside a [GlassPanel] exactly. */
internal val PanelPadding = 14.dp
private val HairlineWidth = 1.dp
private val DividerHeight = 16.dp
private val SegmentTrackPadding = 3.dp
private val SegmentChipHeight = 28.dp
private val SegmentChipPaddingH = 12.dp
private val SegmentChipPaddingV = 4.dp
private val SwatchGrowth = 2.dp
private val SwatchRingWidth = 2.dp
private val SwatchStripPadding = 4.dp
private val SliderWidth = 104.dp

/** Small enough to read as a mark on the track rather than as a second thumb. */
private val SliderTickRadius = 2.dp
private val SliderRowHeight = 28.dp
private val SliderThumb = 14.dp
private val SliderTrack = 3.dp
private const val MIN_TILE_SIDE = 2
private const val RGB_MASK = 0xFFFFFF
private const val MORE_COLOURS = "More colours"

/**
 * ⭐ ROADMAP M10 — a [Readout] you can press, and hold.
 *
 * *"i want you to be able to change thickness of lines by clicking the number instead of having to
 * use the slider ... also make it so that you can hold down on the number to bring up a menu"*.
 *
 * ## Why not a [TextChip]
 *
 * A chip is uppercase, tracked-out and in the UI face; this has to stay a **value** — monospace,
 * mixed case, fixed width — or the number stops reading as a number and the row's digits jitter as
 * the slider moves. So it keeps [Readout]'s face and borrows only the chip's *shape*: the same
 * corner, the same minimum height, a hairline and a raised fill so it reads as a key rather than a
 * caption. It is the treatment `SwatchStrip`'s `+` already uses, applied to a number.
 *
 * ⚠️ **`combinedClickable` inside a scrolling `GlassBar`.** The dock's bar is a `horizontalScroll`,
 * and a scroll container claims a pointer once it has moved past touch slop — but a long press is
 * decided on *time*, not distance, so a still finger is never claimed. Holding still is exactly
 * what this gesture asks for.
 *
 * @param onLongClick null for a readout that is only pressable, which is how the text-size one in
 *   the format bar is used — it already has `A−`/`A+` either side and needs no third gesture.
 */
@Composable
fun ReadoutChip(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    contentDescription: String? = null,
    onBounds: ((Rect) -> Unit)? = null,
    contentWidth: Dp? = null,
) {
    Box(
        modifier = modifier
            .then(
                contentDescription
                    ?.let { name -> Modifier.semantics { this.contentDescription = name } }
                    ?: Modifier,
            )
            .then(onBounds?.let { report -> Modifier.onGloballyPositioned { report(it.boundsInRoot()) } } ?: Modifier)
            .clip(InkDims.ChipCorner)
            .background(if (enabled) Ink.PanelRaised else Color.Transparent)
            .border(HairlineWidth, if (enabled) Ink.Hairline else Color.Transparent, InkDims.ChipCorner)
            .combinedClickable(enabled = enabled, onClick = onClick, onLongClick = onLongClick)
            .defaultMinSize(minHeight = InkDims.ChipMinHeight)
            .padding(horizontal = ReadoutChipPaddingH, vertical = InkDims.ChipPaddingV),
        contentAlignment = Alignment.Center,
    ) {
        Readout(
            text,
            // ⚠️ The width goes on the **text**, not on the chip. A caller that fixes the chip's
            // width so the row does not re-flow as a digit changes is then also paying for the
            // chip's padding out of the same budget, and "16.0 dp" ellipsised to "16.0…" — a
            // truncation that reads as a value, which is worse than one that reads as an error.
            modifier = contentWidth?.let { Modifier.width(it) } ?: Modifier,
            color = if (enabled) Ink.TextStrong else Ink.TextMuted,
            textAlign = TextAlign.Center,
        )
    }
}

/** Tighter than a chip's: the readout inside already carries a fixed width from its caller. */
private val ReadoutChipPaddingH = 8.dp
