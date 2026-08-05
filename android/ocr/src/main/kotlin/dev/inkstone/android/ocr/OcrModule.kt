package dev.inkstone.android.ocr

/**
 * ROADMAP M7 — handwriting recognition and search. Empty in M0.
 *
 * The detail most implementations get wrong, recorded here so it is not
 * rediscovered by shipping garbage: **ML Kit's recogniser assumes a single line
 * of text.** Passing a whole page as one `Ink` object produces nonsense.
 * Segment strokes into lines first and size each `WritingArea` to its line
 * (SPEC §8.2) — that is the biggest accuracy lever in the milestone, and M7's
 * acceptance requires showing the segmented and unsegmented numbers side by side.
 */
internal object OcrModule {
    const val IMPLEMENTED_IN: String = "M7"
}
