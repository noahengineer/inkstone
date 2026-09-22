package dev.inkstone.android.ink

import androidx.ink.brush.InputToolType
import androidx.ink.geometry.ImmutableBox
import androidx.ink.geometry.ImmutableVec
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.inkstone.core.model.FractionalKey
import dev.inkstone.core.model.ImageObject
import dev.inkstone.core.model.InkObject
import dev.inkstone.core.model.LamportClock
import dev.inkstone.core.model.Lasso
import dev.inkstone.core.model.Matrix3
import dev.inkstone.core.model.ObjectId
import dev.inkstone.core.model.PageOp
import dev.inkstone.core.model.PagePoint
import dev.inkstone.core.model.TextBoxObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * ⭐ ROADMAP M8c — the store holds **objects**, not only strokes, and every kind now paints itself.
 *
 * ## What this file is the guard against
 *
 * `StrokeStore.addObject` opened with `as? StrokeObject ?: return false` for six milestones and
 * `InkSurfaceView.loadPage` filtered `filterIsInstance<StrokeObject>()`. So an image or a text box
 * arriving from a peer, from an undo's re-add, or from a page reload was **correct in the log and
 * absent from the screen** — SPEC §4.9.3's *"shows nothing at all"*.
 *
 * The half that is not merely invisible is [StrokeStore.allocateTopZ], and it is why the fix could
 * not be *"filter later"*. The store keeps the page's top z key because it is the one object that
 * sees every writer of one. An object that reaches the page without passing through it is a key the
 * store has not seen, so **the next stroke the user draws lands underneath it** — on screen, in the
 * published PDF's `/Annots` order and on every peer, with nothing connecting the two actions. That
 * is M8a's recorded regression (`docs/M8-SELECTION.md` §6, first bullet) arriving from a new
 * direction, and [aStrokeDrawnAfterAnImageIsInsertedLandsOnTopOfIt] is the assertion that closes it.
 *
 * ## ⚠️ Two assertions in here were reversed, deliberately
 *
 * The previous version of this file asserted that a lasso could not take an image and that an
 * eraser passed straight through one. Both were correct **while nothing could draw one**: the rule
 * was *"nothing the user cannot see may be selected or erased"*, since a selection frame round an
 * invisible object resizes something nobody can see and a bite out of one is stored in a register
 * SPEC §4.9.2 keeps deliberately separate from an image's crop.
 *
 * There is nothing the user cannot see any more. The rule is unchanged and its answer flipped, so
 * [aLassoRoundAnImageTakesIt] and [anEraserBitesAPictureAndTheStrokeUnderIt] are the same rule
 * asserted from the other side.
 *
 * ## Geometry is hand-built, and deliberately without orientation
 *
 * Same reasoning as [StrokeStoreSelectionTest]. The strokes here carry **no orientation and no
 * tilt**, so every expected coordinate is arithmetic a reader can check.
 *
 * ⚠️ This used to say the file was *"independent of the ten M1 failures on x86_64"*, caused by
 * `SyntheticInk` leaving `orientationRadians` outside `[0, 2π)`. That fixture bug was real but
 * never x86_64's doing — it was the seed — and it is fixed (`SyntheticInk.turn`). Hand-built
 * geometry is still preferred here, for legibility rather than for immunity.
 */
@RunWith(AndroidJUnit4::class)
class StrokeStoreObjectsTest {

    private lateinit var factory: InkBrushFactory
    private lateinit var store: StrokeStore

    private var ids = 0
    private var clocks = 0L

    @Before
    fun setUp() {
        val metrics = InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics
        factory = InkBrushFactory(metrics.density, metrics.densityDpi / POINTS_PER_INCH)
        store = StrokeStore()
        store.recorder = PageOpRecorder(
            nextObjectId = { ObjectId("obj-${ids++}") },
            nextOpId = { "op-${ids++}" },
            nextClock = { LamportClock(clocks++, "test-device") },
        )
    }

    /** See [StrokeStoreSelectionTest]'s copy: JUnit's `assertNotNull` cannot be used as an expression. */
    private fun <T : Any> required(value: T?, what: String): T = requireNotNull(value) { "$what was null" }

    private fun clock() = LamportClock(clocks++, "test-device")

    /** A horizontal line from ([x], [y]). No orientation, no tilt — see the class KDoc. */
    private fun line(x: Float, y: Float): Stroke {
        val batch = MutableStrokeInputBatch()
        for (sample in 0 until SAMPLES) {
            val t = sample.toFloat() / (SAMPLES - 1)
            batch.add(
                type = InputToolType.STYLUS,
                x = x + LINE_LENGTH_PT * t,
                y = y,
                elapsedTimeMillis = (t * STROKE_MILLIS).toLong(),
            )
        }
        return Stroke(factory.brushFor(PenConfig(InkBrushKind.FOUNTAIN, BLACK, PEN_WIDTH_DP)), batch)
    }

    private fun addLine(x: Float, y: Float): ObjectId {
        store.beginEdit()
        val id = required(store.add(line(x, y), InkBrushKind.FOUNTAIN), "the id the store assigned")
        store.endEdit()
        return id
    }

    /**
     * Insert a folded object the way everything but a live gesture does — through `applyOps`.
     *
     * A peer's edit, an undo's re-add and a page load all arrive as an `AddObject`, so driving the
     * tests through the same door is what makes them evidence about those three paths rather than
     * about a helper. `record = false`: these are not local edits.
     */
    private fun addObject(obj: InkObject) {
        store.applyOps(listOf(PageOp.AddObject("op-add-${ids++}", clock(), obj)), factory)
    }

    private fun image(
        id: String,
        x: Float,
        y: Float,
        z: FractionalKey,
        width: Float = IMAGE_WIDTH_PT,
        height: Float = IMAGE_HEIGHT_PT,
    ) = ImageObject(
        id = ObjectId(id),
        asset = "assets/$id.png",
        widthPt = width,
        heightPt = height,
        transform = Matrix3.translate(x, y),
        z = z,
    )

    private fun imageFor(id: ObjectId): DryImage = required(store.objectOf(id) as? DryImage, "the image for $id")

    private fun textBoxFor(id: ObjectId): DryTextBox =
        required(store.objectOf(id) as? DryTextBox, "the text box for $id")

    // -----------------------------------------------------------------------
    // The z key — the acceptance argument
    // -----------------------------------------------------------------------

    /**
     * ⚠️ **The whole reason this is one store and not two.**
     *
     * Insert a picture, draw over it, and the writing must be on top. If the picture's key never
     * reaches [StrokeStore]'s maximum then the next `allocateTopZ` answers from below it, and the
     * stroke the user just drew is filed *underneath* the picture — silently, everywhere the page
     * is ordered: on the glass, in the published PDF's `/Annots` order, and on every peer.
     *
     * The key here is deliberately far above anything the store has handed out, because that is
     * what a real image insert looks like — it takes the top of the page.
     */
    @Test
    fun aStrokeDrawnAfterAnImageIsInsertedLandsOnTopOfIt() {
        addLine(LINE_X, LINE_Y)
        addObject(image("photo", IMAGE_X, IMAGE_Y, HIGH_KEY))

        val drawn = addLine(LINE_X, LINE_Y + FAR_PT)

        val picture = imageFor(ObjectId("photo")).z
        val stroke = required(store.strokeOf(drawn), "the new stroke").z
        assertTrue("the new stroke ($stroke) landed under the picture ($picture)", stroke > picture)
    }

    /**
     * The same claim for the bulk allocator, which is what a duplicate and an insert-many use.
     *
     * `allocateTopZ(count)` is the only other route to a key, so a picture invisible to *it* has
     * the same consequence one gesture later.
     */
    @Test
    fun allocateTopZAnswersAboveAnImageAndNotOnlyAboveTheStrokes() {
        addLine(LINE_X, LINE_Y)
        addObject(image("photo", IMAGE_X, IMAGE_Y, HIGH_KEY))

        val keys = store.allocateTopZ(2)

        assertEquals(2, keys.size)
        assertTrue("the first key ($keys) is not above the picture ($HIGH_KEY)", keys[0] > HIGH_KEY)
        assertTrue("the keys are not ascending: $keys", keys[1] > keys[0])
    }

    /**
     * One list, one `(z, id)` order — which is what lets a picture sit *between* two strokes.
     *
     * Two stores, or a filter at the painter, can only ever draw all the pictures before or all of
     * them after the writing. Highlighting a diagram and then writing a note beside it produces
     * exactly this interleave, and there is no order-of-passes that gets it right.
     */
    @Test
    fun renderOrderInterleavesAPictureBetweenTwoStrokes() {
        val under = addLine(LINE_X, LINE_Y)
        addObject(image("photo", IMAGE_X, IMAGE_Y, HIGH_KEY))
        val over = addLine(LINE_X, LINE_Y + FAR_PT)

        val order = ArrayList<ObjectId>()
        store.forEachInRenderOrder(visibleBounds = null) { order += it.id }

        assertEquals(listOf(under, ObjectId("photo"), over), order)
        assertEquals("a picture is not counted as an object on the page", 3, store.size)
    }

    /**
     * The narrowed walk still yields ink only — the PDF outliner and the recogniser depend on it.
     *
     * Both used to call `forEachInRenderOrder` and read `dry.stroke`, which is a compile error now
     * rather than a cast that would have thrown on the first page containing a picture.
     */
    @Test
    fun theStrokeOnlyWalkSkipsAPicture() {
        val first = addLine(LINE_X, LINE_Y)
        addObject(image("photo", IMAGE_X, IMAGE_Y, HIGH_KEY))
        val second = addLine(LINE_X, LINE_Y + FAR_PT)

        val strokes = ArrayList<ObjectId>()
        store.forEachStrokeInRenderOrder(visibleBounds = null) { strokes += it.id }

        assertEquals(listOf(first, second), strokes)
    }

    // -----------------------------------------------------------------------
    // The registers — a peer's edit must reach the render state
    // -----------------------------------------------------------------------

    /**
     * ⚠️ **The two boxes, on an object that is not a stroke.**
     *
     * `DryObject.bounds` is in the object's **own** space and `pageBounds` is in **page** space,
     * and they are the same four numbers for anything nobody has moved — which is what makes
     * asking the wrong one invisible until something moves. A `SetTransform` that updated only the
     * own-space box would leave the picture culled where it used to be and drawn where it is not.
     *
     * The picture is created at the identity here precisely so the move is the only thing that can
     * separate the two.
     */
    @Test
    fun aPeersMoveOfAPictureUpdatesItsPageBoxAndLeavesItsOwnBoxAlone() {
        val id = ObjectId("photo")
        addObject(
            ImageObject(id = id, asset = "assets/photo.png", widthPt = IMAGE_WIDTH_PT, heightPt = IMAGE_HEIGHT_PT),
        )

        store.applyOps(
            listOf(PageOp.SetTransform("op-move", clock(), id, Matrix3.translate(MOVE_DX, MOVE_DY))),
            factory,
        )

        val dry = imageFor(id)
        val own = required(dry.bounds, "the own-space box")
        val page = required(dry.pageBounds, "the page-space box")
        assertEquals("the own-space box moved with the object", 0f, own.xMin, TOLERANCE)
        assertEquals("the own-space box moved with the object", IMAGE_WIDTH_PT, own.xMax, TOLERANCE)
        assertEquals("the page-space box did not follow the move", MOVE_DX, page.xMin, TOLERANCE)
        assertEquals("the page-space box did not follow the move", MOVE_DY, page.yMin, TOLERANCE)
    }

    /**
     * Culling reads the page-space box, so a moved picture is drawn where it now is.
     *
     * The viewport here contains the picture's *new* position and not its old one. Getting this
     * wrong is the symptom a user reports as "the photo disappears when I scroll to it".
     */
    @Test
    fun aMovedPictureIsEmittedForTheViewportItIsNowIn() {
        val id = ObjectId("photo")
        addObject(
            ImageObject(id = id, asset = "assets/photo.png", widthPt = IMAGE_WIDTH_PT, heightPt = IMAGE_HEIGHT_PT),
        )
        store.applyOps(
            listOf(PageOp.SetTransform("op-move", clock(), id, Matrix3.translate(MOVE_DX, MOVE_DY))),
            factory,
        )

        val atNewPlace = ArrayList<ObjectId>()
        store.forEachInRenderOrder(boxAround(MOVE_DX, MOVE_DY)) { atNewPlace += it.id }
        val atOldPlace = ArrayList<ObjectId>()
        store.forEachInRenderOrder(boxAround(-FAR_PT, -FAR_PT)) { atOldPlace += it.id }

        assertEquals("the picture was culled at its new position", listOf(id), atNewPlace)
        assertTrue("the picture is still drawn where it used to be", atOldPlace.isEmpty())
    }

    /**
     * ⭐ SPEC §4.9.2's two record registers reach the render state.
     *
     * `SetTextContent` and `SetFormat` fold into **side tables**, not onto the payload, so they are
     * the two a diff of two `InkObject`s cannot see — which is `NoteEditor.changedRecords`' whole
     * reason to exist. This is the other end of that path: an op that arrives here must land on the
     * object rather than fall through the `when` into `else -> false`, which is how M8a's registers
     * were lost for a milestone.
     *
     * The seeded value is asserted first because a text box created with words and never edited has
     * **no** `SetTextContent` op at all: its text is on the payload, and reading only the register
     * would show an empty box for every text box nobody has retyped.
     */
    @Test
    fun aPeersTextAndFormatRegistersLandOnTheObject() {
        val id = ObjectId("caption")
        addObject(
            TextBoxObject(
                id = id,
                text = SEEDED_TEXT,
                widthPt = IMAGE_WIDTH_PT,
                heightPt = IMAGE_HEIGHT_PT,
                z = HIGH_KEY,
            ),
        )
        assertEquals("the payload's own words were dropped", SEEDED_TEXT, textBoxFor(id).content)

        store.applyOps(
            listOf(
                PageOp.SetTextContent("op-text", clock(), id, RETYPED_TEXT),
                PageOp.SetFormat("op-format", clock(), id, FORMAT_RECORD),
            ),
            factory,
        )

        val dry = textBoxFor(id)
        assertEquals("SetTextContent did not reach the object", RETYPED_TEXT, dry.content)
        assertEquals("SetFormat did not reach the object", FORMAT_RECORD, dry.format)
        assertEquals("the record was not decoded", SERIF_SIZE_PT, dry.decodedFormat.sizePt, TOLERANCE)
    }

    /**
     * An unknown key in a format record survives the render state untouched.
     *
     * The register is kept as the encoded string rather than a decoded `ObjectFormat` for exactly
     * this: a key written by a later build round-trips through the screen as it does through the
     * log, so re-saving the page cannot drop it (CLAUDE.md rule 1).
     */
    @Test
    fun aFormatRecordFromALaterBuildIsKeptVerbatim() {
        val id = ObjectId("caption")
        addObject(TextBoxObject(id, SEEDED_TEXT, IMAGE_WIDTH_PT, IMAGE_HEIGHT_PT, z = HIGH_KEY))

        store.applyOps(listOf(PageOp.SetFormat("op-format", clock(), id, FUTURE_RECORD)), factory)

        assertEquals(FUTURE_RECORD, textBoxFor(id).format)
    }

    /**
     * A `SetFormat` aimed at an **image** lands on the image, and not merely on text.
     *
     * The two kinds share one register and one op, and the branch that routes it is a single
     * `is DryBox`. That is exactly the shape of thing that gets narrowed to `is DryTextBox` by
     * somebody reading the op's name — after which cropping a picture would converge, journal,
     * upload and change nothing on the glass.
     */
    @Test
    fun aFormatRecordAimedAtAnImageReachesTheImage() {
        val id = ObjectId("photo")
        addObject(image("photo", IMAGE_X, IMAGE_Y, HIGH_KEY))

        store.applyOps(listOf(PageOp.SetFormat("op-crop", clock(), id, CROP_RECORD)), factory)

        assertEquals(CROP_RECORD, imageFor(id).format)
        assertNotNull("the crop was not decoded", imageFor(id).decodedFormat.crop)
    }

    // -----------------------------------------------------------------------
    // Selecting and erasing — the same rule, answered from the other side
    // -----------------------------------------------------------------------

    /**
     * ⚠️ **Reversed in M8c, and the rule behind it did not change.**
     *
     * This test used to assert that the eraser passed *through* a picture, because a bite out of an
     * object nothing could draw would be stored in a register the user could not see the effect of.
     * A picture is drawn now, so an eraser dragged across it takes a bite out of it — and the
     * stroke underneath as well, because both are under the tip.
     *
     * The picture is placed over the line so that a coverage test which merely used the bounding
     * box would not be distinguishable from one that did the work; the assertion is on *both*
     * objects having a region, which only a real per-object test produces.
     */
    @Test
    fun anEraserBitesAPictureAndTheStrokeUnderIt() {
        val stroke = addLine(LINE_X, LINE_Y)
        val id = ObjectId("photo")
        addObject(image("photo", LINE_X, LINE_Y - IMAGE_HEIGHT_PT / 2f, HIGH_KEY))

        val middle = PagePointF(LINE_X + LINE_LENGTH_PT / 2f, LINE_Y)
        store.beginEdit()
        val touched = store.eraseAlong(middle, middle, ERASER_RADIUS_PT, EraseMode.PARTIAL)
        store.endEdit()

        assertEquals("the eraser did not reach both objects", 2, touched)
        assertTrue(
            "the picture was not bitten",
            imageFor(id).erased?.capsules?.isNotEmpty() == true,
        )
        assertTrue(
            "the stroke under the picture was not erased",
            required(store.strokeOf(stroke), "the stroke").erased?.capsules?.isNotEmpty() == true,
        )
    }

    /**
     * ⚠️ **Also reversed.** A lasso round a picture takes it, and a tap on one selects it.
     *
     * `docs/M8-SELECTION.md` §7 said hit-testing an image would be *"a few lines of rectangle
     * containment when the objects exist"*. They exist. The tap is in the **middle** of the
     * rectangle rather than on its edge, which is the assertion that the predicate is a filled
     * containment test and not an outline one — the difference between selecting a photo by
     * touching it and having to find its border.
     */
    @Test
    fun aLassoRoundAnImageTakesIt() {
        val id = ObjectId("photo")
        addObject(image("photo", IMAGE_X, IMAGE_Y, HIGH_KEY))

        val hits = store.objectsIn(
            Lasso.rectangle(
                PagePoint(IMAGE_X - BOX_HALF_PT, IMAGE_Y - BOX_HALF_PT),
                PagePoint(IMAGE_X + IMAGE_WIDTH_PT + BOX_HALF_PT, IMAGE_Y + IMAGE_HEIGHT_PT + BOX_HALF_PT),
            ),
        )

        assertEquals("a lasso round a picture did not take it", listOf(id), hits)
        assertEquals(
            "a tap in the middle of a picture selected nothing",
            id,
            store.objectAt(IMAGE_X + IMAGE_WIDTH_PT / 2f, IMAGE_Y + IMAGE_HEIGHT_PT / 2f, TAP_SLOP_PT),
        )
    }

    /**
     * A tap in the white space **between** a text box's words still selects the text box.
     *
     * The alternative — hit-testing the glyphs — would make a caption something you have to aim at
     * a letter of, and would make the tap disagree with the lasso, which tests the four corners.
     * One predicate for both is what `DryBox` exists to keep.
     */
    @Test
    fun aTapInABlankPartOfATextBoxSelectsIt() {
        val id = ObjectId("caption")
        addObject(
            TextBoxObject(
                id = id,
                text = SHORT_TEXT,
                widthPt = IMAGE_WIDTH_PT,
                heightPt = IMAGE_HEIGHT_PT,
                transform = Matrix3.translate(IMAGE_X, IMAGE_Y),
                z = HIGH_KEY,
            ),
        )

        assertEquals(
            "a tap below the one line of text selected nothing",
            id,
            store.objectAt(IMAGE_X + IMAGE_WIDTH_PT / 2f, IMAGE_Y + IMAGE_HEIGHT_PT - 1f, TAP_SLOP_PT),
        )
    }

    /**
     * Clearing and deleting reach a picture like anything else.
     *
     * `DeleteObject` is the op an undo of an insert produces, and `clear()` walks the store's own
     * index — both of which used to see only the objects the store had accepted, which was the bug
     * one level up.
     */
    @Test
    fun deletingAndClearingReachAPicture() {
        addLine(LINE_X, LINE_Y)
        addObject(image("photo", IMAGE_X, IMAGE_Y, HIGH_KEY))
        addObject(image("second", IMAGE_X, IMAGE_Y + FAR_PT, HIGHER_KEY))

        store.applyOps(listOf(PageOp.DeleteObject("op-del", clock(), ObjectId("photo"))), factory)
        assertEquals(2, store.size)
        assertNull("the deleted picture is still in the store", store.objectOf(ObjectId("photo")))

        store.clear()
        assertEquals("clear left something behind", 0, store.size)
    }

    private fun boxAround(x: Float, y: Float) = ImmutableBox.fromTwoPoints(
        ImmutableVec(x - BOX_HALF_PT, y - BOX_HALF_PT),
        ImmutableVec(x + IMAGE_WIDTH_PT + BOX_HALF_PT, y + IMAGE_HEIGHT_PT + BOX_HALF_PT),
    )

    private companion object {
        const val POINTS_PER_INCH = 72f
        const val SAMPLES = 24
        const val STROKE_MILLIS = 120f
        const val LINE_LENGTH_PT = 80f
        const val LINE_X = 120f
        const val LINE_Y = 200f
        const val IMAGE_X = 400f
        const val IMAGE_Y = 500f
        const val IMAGE_WIDTH_PT = 120f
        const val IMAGE_HEIGHT_PT = 90f
        const val MOVE_DX = 220f
        const val MOVE_DY = 90f
        const val FAR_PT = 300f
        const val BOX_HALF_PT = 12f
        const val TAP_SLOP_PT = 6f
        const val ERASER_RADIUS_PT = 6f
        const val PEN_WIDTH_DP = 3f
        const val BLACK = 0xFF1A1A1A.toInt()
        const val TOLERANCE = 0.01f

        const val SEEDED_TEXT = "as created"
        const val RETYPED_TEXT = "as retyped by a peer"
        const val SHORT_TEXT = "one line"

        /**
         * A real `ObjectFormatCodec` record — serif, 18 pt, centred.
         *
         * Spelled in the wire form rather than in something invented, even though this layer holds
         * the register as an opaque string and would pass either. A fixture that does not look like
         * production is the thing CLAUDE.md warns about in the one direction that also misinforms
         * the next reader: someone extending this file would copy the shape of what is here.
         */
        const val FORMAT_RECORD = "if1;f=se;sz=18;a=c"
        const val SERIF_SIZE_PT = 18f

        /** A rectangular crop, the middle four fifths — what an image's format record carries. */
        const val CROP_RECORD = "if1;c=0.1:0.1:0.9:0.9"

        /** A key from a build that knows a format field this one does not — see SPEC §4.9.2. */
        const val FUTURE_RECORD = "if1;f=se;sz=18;vertical=rl"

        /** Far above anything the store has handed out — what an insert-on-top really looks like. */
        val HIGH_KEY = FractionalKey("zz")
        val HIGHER_KEY = FractionalKey("zzz")
    }
}
