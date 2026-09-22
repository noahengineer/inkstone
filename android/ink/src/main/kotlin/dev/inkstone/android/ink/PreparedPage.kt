package dev.inkstone.android.ink

import dev.inkstone.core.model.FoldedPage
import dev.inkstone.core.model.ImageObject
import dev.inkstone.core.model.InkObject
import dev.inkstone.core.model.LamportClock
import dev.inkstone.core.model.PageOp
import dev.inkstone.core.model.ShapeObject
import dev.inkstone.core.model.StrokeObject
import dev.inkstone.core.model.TextBoxObject

/**
 * ⭐ A page's render state, built **off the main thread**.
 *
 * ## Why this type exists
 *
 * `InkSurfaceView.loadPage` used to decode and natively tessellate every object on the page in one
 * unyielding loop on `Dispatchers.Main.immediate` — `NoteEditor.work` runs there deliberately, so
 * the whole open sat on the UI thread. Measured last session on a 1,187-object note:
 * `Skipped 190 frames!` and `Displayed +3s632ms`, halved to ~1.8 s by removing a duplicate load
 * and no further (docs/M8-PERF-RENDER-CACHE.md §5). The dominant term is `Stroke(brush, inputs)`;
 * every other stage of an open is 12–50 ms. So opening a note froze the app, and a two-finger
 * undo tapped in that window was not slow — the thread it needed was gone.
 *
 * Splitting the load in two is what fixes it: [of] is pure construction and runs anywhere,
 * [StrokeStore.install] is list appends and runs on the UI thread.
 *
 * ## Why building a `Stroke` off the main thread is safe
 *
 * ⭐ **Because this app has done it in production since M3.** `InkPageThumbnailRenderer.render`
 * builds a whole [StrokeStore] out of a folded page — the same [buildDryObject], the same
 * `StrokeGeometryCodec.decode`, the same native tessellation — on `Dispatchers.Default`, for every
 * thumbnail in the library. That was the open question the previous session left, and it was
 * already answered by code two milestones old.
 *
 * The narrower guarantee this relies on is the one `PageOpRecorder`'s KDoc states: `androidx.ink`'s
 * `Stroke` is **immutable**, its inputs and its mesh fixed once built. Nothing here touches a view,
 * a `Canvas`, or any store state — [of] is handed ops and a brush factory and returns objects.
 *
 * ⚠️ **What it is not safe to do off the main thread is `insert` them**, which is why that half is
 * [StrokeStore.install] and not part of this class. `StrokeStore` is single-threaded by its own
 * KDoc and is being drawn from while this is built.
 */
internal class PreparedPage private constructor(
    /** The page's objects, decoded and tessellated, in the order the ops named them. */
    val objects: List<DryObject>,
    /**
     * Everything that is not an `AddObject`, in order.
     *
     * ⚠️ Kept rather than applied here: a register writes onto an object, and the object it names
     * is not on the page until [StrokeStore.install] has put it there.
     */
    val registers: List<PageOp>,
) {

    companion object {
        /**
         * The clock on the synthetic `AddObject`s a page load builds. Never journaled — `record`
         * is false on that path — so its value is arbitrary, and it is a recognisable sentinel
         * rather than a plausible counter in case one ever escapes.
         */
        private val LOAD_CLOCK = LamportClock(0, "load")

        /**
         * Decode and tessellate a page — **the expensive half of a load, and the whole of it.**
         *
         * @param page the page to build, as the editor last folded it.
         */
        fun of(page: FoldedPage, brushes: InkBrushFactory): PreparedPage = of(loadOps(page), brushes)

        /**
         * A folded page as the ops that build it — synthetic, never journaled.
         *
         * `applyOps` is the one path into the render state, so loading goes through it rather than
         * around it: one decoder, one erase model, one `(z, id)` order.
         *
         * ⚠️ **The two record registers are side tables on the fold, so they need their own ops.**
         * `PageFolder.materialise` copies the winning transform, style, z and erased region onto each
         * payload, and — for a text box — the winning text. It does **not** copy `SetFormat`, and it
         * does not copy a *shape's* `SetTextContent` (SPEC §4.8.2's record lives there). Load the
         * payloads alone and a text box comes back in the default font on every page turn while the
         * log holds the right one: the fold is correct, the file is correct, and only the screen is
         * wrong — which is the failure `NoteEditor.reconcile` was extended twice to close.
         */
        private fun loadOps(page: FoldedPage): List<PageOp> {
            val ops = ArrayList<PageOp>(page.objects.size)
            for (obj in page.objects) {
                ops += PageOp.AddObject(opId = "", clock = LOAD_CLOCK, obj = obj)
                // Only when the register has been written. A text box that has never been edited has
                // its words on its payload and nothing in `texts`, and pushing an empty string here
                // would wipe them.
                page.textOf(obj.id)?.let { ops += PageOp.SetTextContent("", LOAD_CLOCK, obj.id, it) }
                page.formatOf(obj.id)?.let { ops += PageOp.SetFormat("", LOAD_CLOCK, obj.id, it) }
            }
            return ops
        }

        private fun of(ops: List<PageOp>, brushes: InkBrushFactory): PreparedPage {
            val objects = ArrayList<DryObject>(ops.size)
            val registers = ArrayList<PageOp>()
            for (op in ops) {
                if (op is PageOp.AddObject) {
                    // Null is a stroke whose blob this build cannot read — held out of the page
                    // exactly as `StrokeStore.addObject` holds it out, rather than crashing a load
                    // on a note written by a newer build (SPEC §5.8).
                    buildDryObject(op.obj, brushes)?.let { objects += it }
                } else {
                    registers += op
                }
            }
            return PreparedPage(objects, registers)
        }
    }
}

/**
 * The render state for one folded object, whatever kind it is — **pure, and therefore movable.**
 *
 * ⚠️ This was `StrokeStore.dryObjectFor`, a private method, and the only reason it was ever an
 * instance method is that the shape branch used to set `routesDirty` on the way past. It does not
 * need to: `StrokeStore.insert` sets that flag for *every* object it takes, which is the stronger
 * statement and was already true. Moving it out is what lets a page be built somewhere the store
 * is not.
 *
 * This opened with `as? StrokeObject ?: return false` for six milestones, which was the whole of
 * SPEC §4.9.3's *"shows nothing at all"*: an image or a text box arriving from a peer, from an
 * undo's re-add or from a page reload was correct in the log, correct in the fold, and gone from
 * the screen. Worse than gone — absent from `StrokeStore.topZ`, so the next stroke the user drew
 * landed underneath it.
 *
 * A stroke is the one kind whose geometry only this module can decode; every other kind is built
 * straight from its payload. The `when` is exhaustive over [InkObject] on purpose, so that a kind
 * added to `:core:model` is a compile error here rather than a silent drop. (Adding one is
 * separately a note older builds cannot open — SPEC §4.9.1's first row — so this is not the only
 * thing standing in the way of one.)
 *
 * @return null only when the object has geometry that could not be decoded, which is a stroke
 *   whose blob this build cannot read. Everything else is held.
 */
internal fun buildDryObject(obj: InkObject, brushes: InkBrushFactory): DryObject? = when (obj) {
    is StrokeObject -> buildDryStroke(obj, brushes)

    // ⭐ M8b — a shape paints itself now. It arrives with no record: the `SetTextContent` that
    // carries one is a separate op and may sort either side of this `AddObject` (SPEC §4.2), so
    // the record is applied by `applyRegister` whenever it turns up and the shape is drawn plain
    // until it does. A connector therefore appears as its own authored segment first and routes on
    // the next op — which is exactly what it does for a detached end anyway.
    is ShapeObject -> DryShape(obj)

    // ⭐ M8c. Both arrive without their `SetFormat`, for the same reason a shape arrives without
    // its record — it is a separate op that may sort either side of this one — so both are drawn
    // in the default presentation until it turns up.
    is TextBoxObject -> DryTextBox(obj)

    is ImageObject -> DryImage(obj)
}

private fun buildDryStroke(obj: StrokeObject, brushes: InkBrushFactory): DryStroke? {
    // The style goes INTO the decode, so a restyled stroke tessellates once. Decoding, then
    // restyling — which rebuilds the mesh — tessellated every styled stroke twice, on the main
    // thread, on every page turn.
    val decoded = StrokeGeometryCodec.decode(obj.geometry, brushes, obj.style) ?: return null
    return DryStroke(
        id = obj.id,
        stroke = decoded.stroke,
        kind = decoded.kind,
        inputModel = decoded.inputModel,
        transform = obj.transform,
        style = obj.style,
        z = obj.z,
    ).also { it.setErased(obj.erased.toRegion()) }
}
