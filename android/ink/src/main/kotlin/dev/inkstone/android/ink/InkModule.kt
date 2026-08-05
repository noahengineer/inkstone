package dev.inkstone.android.ink

/**
 * ROADMAP M1 — the milestone that decides whether the project is worth
 * continuing. Empty in M0 on purpose; no app functionality belongs here yet.
 *
 * What M1 puts in this module, recorded now so none of it is rediscovered late:
 *
 *  - `InProgressStrokesView` in an `AndroidView` (SPEC §3.2), front-buffered.
 *  - Every row of SPEC §3.3. The two most commonly skipped and most damaging
 *    are historical point consumption (`getHistorySize()`) and
 *    `requestUnbufferedDispatch()` on `ACTION_DOWN` — miss either and the ink
 *    feels wrong with no obvious cause.
 *  - Stroke geometry encoded through `ink-storage` into an opaque `ByteArray`
 *    handed to `:core:storage` (SPEC §5.4). Never JSON: at ~70 bytes/point and
 *    200 Hz sampling that is megabytes per page against a 2-second open target.
 *  - Whole-stroke erase only (SPEC §3.5). Splitting a stroke is
 *    `DeleteObject + AddObject(fragments)` and lands in M8a — do not implement
 *    in-place point-list mutation, it would have to be torn out.
 *
 * And the rule that outranks all of them (CLAUDE.md rule 7): nothing heavy on
 * the ink path. No allocation in a loop, no I/O, no serialization, no
 * recognition between `MotionEvent` and the front buffer.
 */
internal object InkModule {
    const val IMPLEMENTED_IN: String = "M1"
}
