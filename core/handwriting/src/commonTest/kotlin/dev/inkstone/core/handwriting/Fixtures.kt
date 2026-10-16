package dev.inkstone.core.handwriting

import dev.inkstone.core.model.DocumentId
import dev.inkstone.core.model.ObjectId
import dev.inkstone.core.model.PageId
import dev.inkstone.core.model.PenKind
import dev.inkstone.core.model.PenStyle
import dev.inkstone.core.model.PenTrajectory
import kotlin.test.assertNotNull

/** Small, valid things to store and read back. */
internal object Fixtures {

    fun trajectory(offset: Float = 0f): PenTrajectory = assertNotNull(
        PenTrajectory.of(
            x = floatArrayOf(offset, offset + 2f, offset + 4f, offset + 6f, offset + 7f),
            y = floatArrayOf(10f, 4f, 10f, 6f, 6.5f),
            tMs = intArrayOf(0, 8, 16, 90, 98),
            // On the codec's 1/1023 grid, so a stored-and-read sample compares exactly.
            pressure = floatArrayOf(205f, 614f, 409f, 307f, 512f).map {
                it / TrajectoryCodec.CHANNEL_STEPS
            }.toFloatArray(),
            tilt = null,
            strokeEnds = intArrayOf(3, 5),
        ),
    )

    fun sample(
        id: String,
        sourceKey: String = "key-$id",
        noteId: String = "note",
        label: String = "hi",
        glyphs: List<GlyphSpan> = listOf(GlyphSpan("h", listOf(0)), GlyphSpan("i", listOf(1))),
        split: SampleSplit = SampleSplit.TRAIN,
    ): WordSample = WordSample(
        id = id,
        noteId = DocumentId(noteId),
        pageId = PageId("page"),
        sourceStrokes = listOf(ObjectId("$id-a"), ObjectId("$id-b")),
        sourceKey = sourceKey,
        epochDay = 20_000L,
        label = label,
        labelSource = LabelSource.AGREED,
        alternatives = listOf("hl", "ni"),
        frame = NormalFrame(originX = 1f, originY = 10f, angleRad = 0.01f, slantRad = 0.2f, xHeightPt = 6f),
        pen = PenStyle(PenKind.FOUNTAIN, colorArgb = 0xFF102030.toInt(), widthPt = 1.4f),
        unitLengthCm = 0.035f,
        split = split,
        trajectory = trajectory(),
        glyphs = glyphs,
    )
}
