package dev.inkstone.core.model

import kotlin.math.max

/**
 * ROADMAP M8c — what a **text box** gesture writes down, as a pure function of plain data.
 *
 * The sibling of [ShapeEdit], built the same way for the same two reasons. The *decision* is
 * arithmetic over plain data, so a JVM test, the iOS target and SPEC §13.2's merge matrix can all
 * reach it (CLAUDE.md definition-of-done #6 — *"hoist the decision into a pure function over plain
 * data"*). And **one gesture is one undo entry**, so a create that needs three ops emits all three
 * together rather than leaving each editor to remember the order.
 *
 * ## Two registers, and they must stay two (SPEC §4.9.2)
 *
 * A text box's **words** are [PageOp.SetTextContent]; its **presentation** is [PageOp.SetFormat].
 * Merging them would make an LWW register resolve a paragraph and a font size together, so
 * *changing the font on one device discards a paragraph typed on the other* — converged, with
 * every op present and no error. [setText] and [setFormat] are therefore two methods and never one
 * "update the box" call: a caller that can only write one register at a time cannot write the
 * cheap edit over the expensive one.
 *
 * ## ⚠️ The style is not optional, and this is where that rule is enforced
 *
 * [style] is non-null, unlike [InkObject.style]. A stroke may legitimately carry null — *"as
 * captured"*, because `androidx.ink` already put a colour and a width inside its geometry blob —
 * but a text box has no capture-time style to fall back to, and [PageOp.SetStyle] cannot express
 * *"unset"*. Ship one with a null style and the first recolour has no previous value to invert to,
 * `UndoStack.commit` drops the entry, and *"change the colour, press undo"* does nothing **while
 * eating the press** (SPEC §4.9.2, and `ObjectStyle`'s own KDoc anticipated it). A non-nullable
 * parameter makes that unrepresentable rather than remembered.
 */
public object TextBoxEdit {

    /**
     * A new text box, as the ops that make it — in this order, as one gesture.
     *
     * The order is not cosmetic, and it is [ShapeEdit.create]'s reasoning verbatim: a register
     * write that sorts *before* its `AddObject` is legal but pointless work for the fold, and one
     * gesture means one undo entry, so *"add a text box, press undo"* removes the box, its words
     * and its formatting together rather than leaving a font size on a tombstone.
     *
     * ⚠️ **The text register is seeded only when there is text, and that has a consequence worth
     * knowing before wiring an editor.** `Undo.inverseOf` reads a `SetTextContent`'s previous value
     * out of the fold, and `SetTextContent.text` is non-null and so cannot say *"unset"* — exactly
     * the trap [PageOp.SetStyle] has. So a box created **empty** and typed into as a *separate*
     * gesture has no inverse for that first [setText]: `UndoStack.commit` drops the entry and the
     * press falls through to the create, deleting the box the user was typing in. The editor's job
     * is therefore to make placing a box and typing its first words **one** gesture — call [create]
     * with the text already in it — which is also what a user means by "I added a label". The
     * alternative, writing an empty register here, would put a redundant op in every note that ever
     * places a text box, and [PageOp.SetFormat] does not need one because the empty record *is* the
     * default presentation (see `Undo.inverseOf`).
     *
     * @param widthPt clamped to [ShapeGeometry.MIN_EXTENT_PT] for [ShapeEdit.place]'s reason: a
     *   tap that produced a zero-height box would give the object a singular matrix under the
     *   first resize, and [Matrix3.inverse] answers null for one — which is what maps an eraser's
     *   page-space sweep into the object's own space. An object that arrives degenerate can never
     *   be moved, erased or attached to again.
     * @return the `AddObject`, then `SetTextContent` when [text] is non-empty, then `SetFormat`
     *   when [format] is not [ObjectFormat.Default]. An empty format is not written at all — the
     *   default presentation is the absence of a record, and writing one would put a string in
     *   every note that places a plain label.
     */
    @Suppress("LongParameterList") // The payload and both registers, spelled out; see the KDoc.
    public fun create(
        id: ObjectId,
        text: String,
        widthPt: Float,
        heightPt: Float,
        transform: Matrix3,
        z: FractionalKey,
        style: ObjectStyle,
        format: ObjectFormat,
        nextOpId: () -> String,
        nextClock: () -> LamportClock,
    ): List<PageOp> {
        val box = TextBoxObject(
            id = id,
            text = text,
            widthPt = max(widthPt, ShapeGeometry.MIN_EXTENT_PT),
            heightPt = max(heightPt, ShapeGeometry.MIN_EXTENT_PT),
            transform = transform,
            style = style,
            z = z,
        )
        val ops = ArrayList<PageOp>(OPS_PER_CREATE)
        ops += PageOp.AddObject(nextOpId(), nextClock(), box)
        if (text.isNotEmpty()) ops += setText(id, text, nextOpId, nextClock)
        if (!format.isDefault) ops += setFormat(id, format, nextOpId, nextClock)
        return ops
    }

    /**
     * The one op that changes what a text box **says**.
     *
     * Whole-string LWW, not a text CRDT — SPEC §12 puts real-time collaboration out of scope, and
     * the policy that does apply is never to discard bytes, which SPEC §7.7's conflicted copy
     * handles without a character-level merge.
     *
     * Nothing about presentation goes through here. That is [setFormat], and keeping the two apart
     * is what makes *"type on one device, restyle on the other"* commute.
     */
    public fun setText(target: ObjectId, text: String, nextOpId: () -> String, nextClock: () -> LamportClock): PageOp =
        PageOp.SetTextContent(nextOpId(), nextClock(), target, text)

    /**
     * The one op that changes how a text box or an image is **presented** — font, size, alignment,
     * list level and style, and an image's crop (SPEC §4.9.2).
     *
     * ⚠️ **[format] is the whole record, absolutely** (CLAUDE.md rule 8). A register resolves
     * whole, so a caller changing one property must pass the object's *current* format with that
     * one property changed — `ObjectFormat.decode(page.formatOf(id)).copy(align = …)` — and never a
     * fresh [ObjectFormat] carrying only the new value. Passing a fresh one silently resets every
     * other property, **including [ObjectFormat.extras]**, which is a later build's data and the
     * one thing this build cannot reconstruct.
     */
    public fun setFormat(
        target: ObjectId,
        format: ObjectFormat,
        nextOpId: () -> String,
        nextClock: () -> LamportClock,
    ): PageOp = PageOp.SetFormat(nextOpId(), nextClock(), target, format.encode())

    /** `AddObject`, the words, the formatting. */
    private const val OPS_PER_CREATE = 3
}

/**
 * The [ObjectFormat] on [id], or [ObjectFormat.Default] when the register is unset or unreadable.
 *
 * The sibling of `FoldedPage.recordOf`, and it answers a default for the same reason: a register
 * holding something this build cannot parse is a later build's record, a stranger's data, or
 * corruption, and presenting the object with the default font is the only reading of it that
 * cannot invent a layout. Use [FoldedPage.formatOf] directly when the difference matters — a
 * caller about to *write* the register needs the raw value, because writing a decoded default over
 * an unparseable record destroys whatever a newer build put there.
 */
public fun FoldedPage.formatting(id: ObjectId): ObjectFormat = ObjectFormat.decode(formatOf(id))
