package dev.inkstone.app.debug

import android.app.Activity
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.util.TypedValue
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import dev.inkstone.android.ink.InkBrushFactory
import dev.inkstone.android.ink.InkInputModel
import dev.inkstone.android.ink.InkPageThumbnailRenderer
import dev.inkstone.app.handwriting.HandwritingWriter
import dev.inkstone.core.handwriting.ContactSheet
import dev.inkstone.core.handwriting.PhraseOps
import dev.inkstone.core.handwriting.ReliabilityGate
import dev.inkstone.core.handwriting.WordOutcome
import dev.inkstone.core.handwriting.quality.Backend
import dev.inkstone.core.model.DocumentId
import dev.inkstone.core.model.GeneratedInk
import dev.inkstone.core.model.InkReader
import dev.inkstone.core.model.LamportClock
import dev.inkstone.core.model.ObjectId
import dev.inkstone.core.model.PageCanvas
import dev.inkstone.core.model.PageId
import dev.inkstone.core.model.PageOp
import dev.inkstone.core.model.PageSize
import dev.inkstone.core.model.PenKind
import dev.inkstone.core.model.PenStyle
import dev.inkstone.core.model.PhraseRecord
import dev.inkstone.core.model.foldPage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * **Debug builds only.** docs/HANDWRITING-SYNTHESIS.md §11's visual harness, step 2 (H6): the contact
 * sheet — one sentence, twelve times, per backend and per neatness ([ContactSheet]) — drawn **through
 * the real ink engine**, from this device's own profile and the shipped model.
 *
 * ```
 * adb shell am start -n dev.inkstone/.app.debug.HandwritingSheetActivity \
 *   [--es sentence "it is not a cation"] [--ez trusting true] [--ei times 12]
 * adb logcat -s HandwritingSheet
 * adb exec-out run-as dev.inkstone cat files/debug/hw-sheet/<backend>-<neatness>.webp > sheet.webp
 * ```
 *
 * **The real engine, end to end.** Each row's strokes go through exactly what a drop goes through:
 * [HandwritingWriter.encode] (`TrajectoryInk`: a `StrokeInputBatch`, a `Stroke`, the geometry codec),
 * [PhraseOps.build] (the same `AddObject` + `hw` + record ops `stampHandwriting` applies), the fold,
 * and [InkPageThumbnailRenderer] — whose `PagePainter` is the one the editor's dry layer draws with.
 * Nothing here draws a line itself.
 *
 * **Gated or trusting.** By default every word is read back by the **real** gate — ML Kit, with this
 * profile's calibration — so the sheet is what the Write panel would put on a page, refusals shown as
 * gaps and named in each row's caption. `--ez trusting true` swaps in [ContactSheet.trusting], so the
 * sheet is what each backend *offers*: the same ink the JVM's `ContactSheetSvgTest` draws as SVG, given
 * the same profile. Each block's [ContactSheet.Block.fingerprint] is logged, so the two renderers can
 * be shown to have been handed the same points.
 *
 * Logged per block: rows, words written and refused, strokes, the time, the fingerprint and §11's
 * variation spread ([ContactSheet.Block.spread]) — on a gated sheet, the spread of what the gate let
 * through. The sentence is the experimenter's, not the user's, so it is logged; nothing from the
 * profile is (§9). Nothing here touches a note.
 */
class HandwritingSheetActivity : Activity() {

    private val scope: CoroutineScope = MainScope()
    private lateinit var column: LinearLayout

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    internal interface Deps {
        fun writer(): HandwritingWriter

        fun reader(): InkReader

        fun brushes(): InkBrushFactory
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            val pad = dp(PAD_DP)
            setPadding(pad, pad, pad, pad)
        }
        setContentView(ScrollView(this).apply { addView(column) })
        val sentence = intent.getStringExtra(EXTRA_SENTENCE) ?: DEFAULT_SENTENCE
        val trusting = intent.getBooleanExtra(EXTRA_TRUSTING, false)
        val times = intent.getIntExtra(EXTRA_TIMES, ContactSheet.TIMES)
        scope.launch { draw(sentence, trusting, times) }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun draw(sentence: String, trusting: Boolean, times: Int) {
        val deps = EntryPointAccessors.fromApplication(applicationContext, Deps::class.java)
        val profile = deps.writer().profile()
        // Trusting, the gate is the JVM sheet's — no calibration either — so the two are handed the same ink.
        val gate = if (trusting) {
            ReliabilityGate(ContactSheet.trusting(sentence))
        } else {
            ReliabilityGate(deps.reader(), profile.calibration)
        }
        val mode = if (trusting) "trusting (what each backend offers)" else "gated by ML Kit (what a drop would draw)"
        say("\"$sentence\" — ${profile.words} words, model ${profile.neural?.modelId ?: "none"}, $mode", TITLE_SP)
        Log.i(TAG, "sheet: words=${profile.words} model=${profile.neural?.modelId} trusting=$trusting times=$times")
        if (!trusting && !profile.modelReady) {
            say("No recognition model on this device, so nothing can be read back (D12).", BODY_SP)
            return
        }
        val dir = File(filesDir, OUT_DIR).apply { mkdirs() }
        val renderer = InkPageThumbnailRenderer(deps.brushes())
        for (backend in ContactSheet.BACKENDS) {
            for (neatness in ContactSheet.NEATNESSES) {
                val started = System.nanoTime()
                val block = withContext(Dispatchers.Default) {
                    ContactSheet.block(backend, neatness, sentence, profile.index, gate, profile.neural, times = times)
                }
                if (block == null) {
                    say("${backend.name.lowercase()}: no network on this device", BODY_SP)
                    continue
                }
                val writeMs = (System.nanoTime() - started) / NANOS_PER_MS
                val bytes = withContext(Dispatchers.Default) { render(block, deps.writer(), profile, renderer) }
                val name = "${backend.name.lowercase()}-$neatness.webp"
                bytes?.let { File(dir, name).writeBytes(it) }
                show(block, bytes)
                log(block, writeMs)
            }
        }
        say("Written to files/$OUT_DIR.", BODY_SP)
        Log.i(TAG, "sheet: done")
    }

    /**
     * [block] as a page: each row stamped with [PhraseOps] exactly as a drop is, folded, and drawn by
     * the thumbnail renderer's `PagePainter`. A row's strokes keep the phrase's own `hw` tags.
     */
    private suspend fun render(
        block: ContactSheet.Block,
        writer: HandwritingWriter,
        profile: HandwritingWriter.Profile,
        renderer: InkPageThumbnailRenderer,
    ): ByteArray? {
        val ops = ArrayList<PageOp>()
        var counter = 0L
        for (row in block.rows) {
            val tagged = row.phrase.taggedStrokes(row.origin, group = "sheet-${row.seed}")
            val geometries = if (tagged.isEmpty()) {
                emptyList()
            } else {
                writer.encode(tagged.map { it.first }, PEN, InkInputModel.SPRING, profile)
            }
            if (tagged.isEmpty() || geometries.size != tagged.size) {
                if (geometries.size != tagged.size) {
                    Log.w(TAG, "seed ${row.seed}: ${tagged.size - geometries.size} strokes refused by the batch")
                }
                continue
            }
            ops += PhraseOps.build(
                strokes = geometries,
                tags = tagged.map { it.second },
                record = PhraseRecord(row.phrase.text, null),
                topZ = ops.filterIsInstance<PageOp.AddObject>().maxOfOrNull { it.obj.z },
                replacing = emptyList(),
                newObjectId = { ObjectId("sheet-${counter++}") },
                nextOpId = { "op-${counter++}" },
                nextClock = { LamportClock(counter++, CLOCK_DEVICE) },
            )
        }
        val size = PageSize(ContactSheet.PAGE_WIDTH_PT, block.bottom - block.top + ContactSheet.MARGIN_PT)
        val page = foldPage(PageId("sheet"), ops, defaultCanvas = PageCanvas.fixed(size))
        return renderer.render(DocumentId("sheet"), page, RENDER_WIDTH_PX)
    }

    private fun show(block: ContactSheet.Block, bytes: ByteArray?) {
        say("${block.title} — ${block.fingerprint()}", TITLE_SP)
        val bitmap = bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
        if (bitmap == null) {
            say("(the renderer produced nothing)", BODY_SP)
        } else {
            column.addView(
                ImageView(this).apply {
                    setImageBitmap(bitmap)
                    adjustViewBounds = true
                },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
            )
        }
        for (row in block.rows) say("${row.seed}: ${row.caption}", CAPTION_SP)
    }

    private fun log(block: ContactSheet.Block, writeMs: Long) {
        val words = block.rows.flatMap { it.phrase.words }
        val written = words.filterIsInstance<WordOutcome.Written>()
        val strokes = block.rows.sumOf { it.phrase.strokes(it.origin).size }
        val network = written.count { it.backend == GeneratedInk.NEURAL }
        Log.i(
            TAG,
            "block ${block.backend.name.lowercase()} n=${block.neatness}: rows=${block.rows.size} " +
                "complete=${block.rows.count { it.phrase.complete }} written=${written.size} " +
                "refused=${words.size - written.size} network=$network strokes=$strokes " +
                "attempts=${written.sumOf { it.attempts }} ms=$writeMs fingerprint=${block.fingerprint()} " +
                "spread=${block.spread()}",
        )
        for (row in block.rows) {
            if (!row.phrase.complete) {
                Log.i(
                    TAG,
                    "  ${block.backend.name.lowercase()} n=${block.neatness} ${row.seed}: ${row.caption}",
                )
            }
        }
    }

    private fun say(text: String, sp: Float) {
        column.addView(
            TextView(this).apply {
                this.text = text
                setTextColor(Color.DKGRAY)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
            },
        )
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val TAG = "HandwritingSheet"
        const val EXTRA_SENTENCE = "sentence"
        const val EXTRA_TRUSTING = "trusting"
        const val EXTRA_TIMES = "times"

        /** What the emulator's 19-word profile can write with assembly alone — and `c`/`t` from the model. */
        const val DEFAULT_SENTENCE = "it is not a cation"
        const val OUT_DIR = "debug/hw-sheet"
        const val CLOCK_DEVICE = "sheet"

        /** Two pixels a point: the sheet's 900 pt at 1,800 px. */
        const val RENDER_WIDTH_PX = 1800
        const val NANOS_PER_MS = 1_000_000L
        const val PAD_DP = 12
        const val TITLE_SP = 14f
        const val BODY_SP = 12f
        const val CAPTION_SP = 9f

        /** A ballpoint at the SVG sheet's width — 2.2 pt at a 9 pt x-height, the app's own proportion. */
        val PEN = PenStyle(PenKind.BALLPOINT, 0xFF111111.toInt(), 2.2f)
    }
}
