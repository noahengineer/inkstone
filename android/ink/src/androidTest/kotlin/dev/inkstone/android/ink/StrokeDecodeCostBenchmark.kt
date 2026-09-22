package dev.inkstone.android.ink

import android.util.Log
import androidx.ink.brush.InputToolType
import androidx.ink.storage.decode
import androidx.ink.strokes.ImmutableStrokeInputBatch
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import androidx.ink.strokes.StrokeInputBatch
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.inkstone.core.model.LamportClock
import dev.inkstone.core.model.ObjectId
import dev.inkstone.core.model.PageOp
import dev.inkstone.core.model.StrokeGeometry
import dev.inkstone.core.model.StrokeObject
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.util.zip.GZIPInputStream
import kotlin.math.sin

/**
 * ⚠️ **Not an assertion of correctness — a measurement.** It exists to answer *where* the time
 * goes when a note with thousands of strokes is opened, split into the things
 * [StrokeGeometryCodec.decode] actually does, so a fix targets the dominant term rather than the
 * first one anybody names.
 *
 * Geometry is built by hand rather than through `SyntheticInk`. No
 * orientation, no tilt: this measures cost, and those fields change the byte count, not the shape
 * of the answer.
 *
 * ⚠️ **Every figure is the minimum of [PASSES] passes.** A single pass on an emulator is mostly
 * measuring whichever GC happened to land in it — the first draft reported gzip inflate as
 * *slower* for 40-point strokes than for 400-point ones, which is a garbage collection, not a fact
 * about gzip. A minimum is the closest a JVM gets to the cost without the noise.
 *
 * Read the numbers out of logcat under the tag `InkDecodeCost`.
 *
 * ⚠️ **[parallelism] has never produced a number.** It was added last and the emulator was
 * contended by other work at the time, which is the one condition under which a scaling benchmark
 * lies in the *reassuring* direction: a saturated host reports no speed-up from more threads
 * whether or not the tessellator scales, so a false negative is indistinguishable from a real one.
 * Run it on a quiet machine, or on the tablet, before quoting anything from it.
 */
@RunWith(AndroidJUnit4::class)
class StrokeDecodeCostBenchmark {

    private val display = InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics
    private val factory = InkBrushFactory(display.density, display.densityDpi / POINTS_PER_INCH)

    private fun batch(points: Int, seed: Int): ImmutableStrokeInputBatch {
        val out = MutableStrokeInputBatch()
        for (index in 0 until points) {
            val t = index.toFloat()
            out.add(
                type = InputToolType.STYLUS,
                x = 40f + seed % 400 + t * 0.7f,
                y = 60f + seed % 600 + sin(t / 6f) * 14f,
                elapsedTimeMillis = index.toLong() * 4L,
                // Clamped explicitly: ink rejects anything outside [0, 1] and a benchmark that
                // dies on its fixture measures nothing.
                pressure = (0.5f + 0.35f * sin(t / 9f + seed)).coerceIn(0.05f, 0.95f),
            )
        }
        return out.toImmutable()
    }

    @Test
    fun whereTheOpenTimeGoes() {
        // Warm the native library, the JIT and the gzip machinery once for the whole test.
        val warm = List(WARMUP) { Stroke(factory.brushFor(PenConfig.Default), batch(60, it)) }
            .map { StrokeGeometryCodec.encode(it, InkBrushKind.FOUNTAIN, InkInputModel.SPRING) }
        repeat(4) { for (blob in warm) StrokeGeometryCodec.decode(blob, factory) }

        for (points in intArrayOf(40, 120, 400)) measure(points)
        parallelism(pointsPerStroke = 120)
    }

    /**
     * Does decoding scale across threads, or is `androidx.ink`'s native geometry serialised inside?
     *
     * This is the question a "decode on a worker" fix actually turns on, and it is not answerable
     * by reading: the tessellator is native code with no documented threading contract. Each
     * worker gets **its own [InkBrushFactory]**, because the shared one caches families in a plain
     * `HashMap` and this is measuring the tessellator, not that hazard.
     */
    private fun parallelism(pointsPerStroke: Int) {
        val n = STROKES
        val brush = factory.brushFor(PenConfig.Default)
        val blobs = ArrayList<StrokeGeometry>(n)
        for (index in 0 until n) {
            blobs += StrokeGeometryCodec.encode(
                Stroke(brush, batch(pointsPerStroke, index)),
                InkBrushKind.FOUNTAIN,
                InkInputModel.SPRING,
            )
        }

        for (threads in intArrayOf(1, 2, 4, 8)) {
            val factories = List(threads) { InkBrushFactory(display.density, display.densityDpi / POINTS_PER_INCH) }
            val took = best {
                val slice = (n + threads - 1) / threads
                val workers = List(threads) { worker ->
                    Thread {
                        val own = factories[worker]
                        var index = worker * slice
                        val end = minOf(index + slice, n)
                        while (index < end) {
                            StrokeGeometryCodec.decode(blobs[index], own)
                            index++
                        }
                    }.also { it.start() }
                }
                for (thread in workers) thread.join()
            }
            Log.i(TAG, "parallel pts=$pointsPerStroke n=$n threads=$threads decode=${ms(took)}ms")
        }
        Log.i(TAG, "cpus=${Runtime.getRuntime().availableProcessors()}")
    }

    @Suppress("LongMethod")
    private fun measure(pointsPerStroke: Int) {
        val n = STROKES
        val brush = factory.brushFor(PenConfig.Default)

        val blobs = ArrayList<StrokeGeometry>(n)
        for (index in 0 until n) {
            blobs += StrokeGeometryCodec.encode(
                Stroke(brush, batch(pointsPerStroke, index)),
                InkBrushKind.FOUNTAIN,
                InkInputModel.SPRING,
            )
        }
        val blobKb = blobs.sumOf { it.bytes.size } / KB

        // --- gzip inflate alone, exactly what `DecompressedBytes` does --------
        val tInflate = best {
            for (blob in blobs) {
                val bytes = blob.bytes
                GZIPInputStream(ByteArrayInputStream(bytes, HEADER_SIZE, bytes.size - HEADER_SIZE))
                    .use { it.readBytes() }
            }
        }

        // --- inflate + the native proto parse ---------------------------------
        var batches = ArrayList<ImmutableStrokeInputBatch>(n)
        val tInputs = best {
            batches = ArrayList(n)
            for (blob in blobs) {
                val bytes = blob.bytes
                batches += StrokeInputBatch.decode(
                    ByteArrayInputStream(bytes, HEADER_SIZE, bytes.size - HEADER_SIZE),
                )
            }
        }

        // --- brush construction alone -----------------------------------------
        val tBrush = best {
            repeat(n) {
                factory.brushFor(
                    kind = InkBrushKind.FOUNTAIN,
                    colorArgb = BLACK,
                    sizePt = 3f,
                    epsilonPt = factory.epsilonPt,
                    inputModel = InkInputModel.SPRING,
                )
            }
        }

        // --- tessellation alone, inputs already decoded ------------------------
        var strokes = ArrayList<Stroke>(n)
        val tTessellate = best {
            strokes = ArrayList(n)
            for (inputs in batches) strokes += Stroke(brush, inputs)
        }

        // --- the bounding box every DryStroke takes in its init ----------------
        val tBounds = best { for (stroke in strokes) stroke.shape.computeBoundingBox() }

        // --- the whole codec call, as loadPage makes it ------------------------
        val tWhole = best { for (blob in blobs) StrokeGeometryCodec.decode(blob, factory) }

        // --- the production entry point, StrokeStore.applyOps ------------------
        val ops = ArrayList<PageOp>(n)
        for (index in 0 until n) {
            ops += PageOp.AddObject(
                opId = "",
                clock = LamportClock(1L, "bench"),
                obj = StrokeObject(id = ObjectId("bench-$index"), geometry = blobs[index]),
            )
        }
        val tApplyOps = best { StrokeStore().applyOps(ops, factory, record = false) }

        Log.i(
            TAG,
            "pts=$pointsPerStroke n=$n blob=${blobKb}KB ms(min of $PASSES): " +
                "gzip=${ms(tInflate)} gzip+proto=${ms(tInputs)} brush=${ms(tBrush)} " +
                "tessellate=${ms(tTessellate)} bbox=${ms(tBounds)} " +
                "|| codec.decode=${ms(tWhole)} StrokeStore.applyOps=${ms(tApplyOps)}",
        )
    }

    /** The minimum of [PASSES] runs — see the class KDoc on why a single pass is noise. */
    private inline fun best(block: () -> Unit): Long {
        var min = Long.MAX_VALUE
        repeat(PASSES) {
            val start = System.nanoTime()
            block()
            val took = System.nanoTime() - start
            if (took < min) min = took
        }
        return min
    }

    private fun ms(nanos: Long) = "${nanos / 1_000_000}"

    private companion object {
        const val TAG = "InkDecodeCost"
        const val POINTS_PER_INCH = 72f
        const val STROKES = 1000
        const val WARMUP = 200
        const val PASSES = 5
        const val HEADER_SIZE = 20
        const val BLACK = 0xFF1A1A1A.toInt()
        const val KB = 1024
    }
}
