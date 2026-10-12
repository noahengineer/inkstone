package dev.inkstone.app.ink

import dev.inkstone.core.database.settings.SettingStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * ⭐ ROADMAP M10 — the editor's remembered toolbar, read once and written on a debounce.
 *
 * ## ⚠️ The flow is a SEED channel, not a state holder
 *
 * [snapshot] is written by [warm] and **by nothing else**. `InkScreen` collects it exactly once, to
 * build its `InkUiState`, and every later change flows the other way — into [changed], which writes
 * a plain field that nothing composes.
 *
 * The version that publishes each change back into the collected flow is the obvious one and it is
 * a performance disaster: `InkScreen`'s root scope reads it, and that scope builds `actions`
 * (unremembered, seventeen lambdas), `objectActions` and `OpenPanels`, so every emission
 * reallocates the editor's whole chrome. A colour wheel drag emits ~200 values. It also creates a
 * second writer of state the user is currently dragging — the *"two mechanisms for one change"*
 * trap `setTemplate` records — where a debounced write echoing back fights the next frame of the
 * gesture.
 *
 * So the rule is: **seeding is one-way.** The store is read at startup and never re-adopted
 * *mid-gesture*.
 *
 * ## ⛔ …but a seed is the newest value, not the startup one
 *
 * *"the favorite colors does not stay like that and the pencil size"*. This class is a process-wide
 * singleton and [snapshot] is the store as it was at **launch**. Every editor seeded from it, so the
 * second note opened in a process — or the Element editor, which is a second `NoteActivity` — started
 * from the launch-time palette, and `InkScreen`'s `snapshotFlow` then emitted that stale seed straight
 * back into [changed], which wrote it over the user's real choices 400 ms later. Nothing threw, every
 * codec test passed, and a force-stop was the only route that ever showed the saved value, because it
 * is the only one that re-reads the store.
 *
 * [snapshot] is now only the *"the store has been read"* signal. What an editor seeds from is
 * [current]: the last value any editor reported, or the store's if none has. [changed] keeps it up to
 * date synchronously, so it never trails the debounce.
 *
 * ## Why a debounce and not a gesture-end callback
 *
 * There is no gesture-end signal to hang this on, and manufacturing one means widening
 * `ColorPanel`, `HueSaturationWheel`, `ChannelSlider`, `HexField` and `SlimSlider` — five
 * components, several call sites, where one forgotten wiring is a silently unsaved preference,
 * which is M6's inert-feature shape. It would not cover the hex field at all, which has no gesture.
 * And persisting every emission means a crash mid-drag persists a colour that was on screen for one
 * frame.
 *
 * ⚠️ [writeNow] reads [pending] **at write time**, not at schedule time, so a [flush] racing the
 * debounce still writes the newest value rather than the one that was current when the timer
 * started.
 *
 * ## Failure is a default, never a blank screen
 *
 * [warm] always terminates in a value. A read that throws — corrupt database, I/O error — costs the
 * user their saved palette for that session and nothing else. The alternative, letting the editor's
 * composition wait on a read that never completes, is a permanently blank note editor with no
 * message and no way out; that is a far worse bug than a reset palette, and it is the one the naive
 * `?: return` produces.
 */
class EditorPreferences(private val store: SettingStore, private val scope: CoroutineScope) {

    private val _snapshot = MutableStateFlow<EditorPrefs?>(null)

    /**
     * Null until [warm] completes, and then the store **as it was at launch** — a readiness signal.
     * ⚠️ Seed from [current], never from this value; see the class KDoc.
     */
    val snapshot: StateFlow<EditorPrefs?> = _snapshot.asStateFlow()

    @Volatile
    private var pending: EditorPrefs? = null

    /** The last value [changed] was given — written there synchronously, never by the debounce. */
    @Volatile
    private var latest: EditorPrefs? = null

    /**
     * What an editor opening **now** should start from: the newest value any editor has reported, or
     * the stored one if none has. Null exactly when [snapshot] is.
     */
    fun current(): EditorPrefs? = latest ?: _snapshot.value

    private val writes = MutableStateFlow(0L)

    init {
        scope.launch {
            writes.collectLatest { ticket ->
                if (ticket == 0L) return@collectLatest
                delay(WRITE_DEBOUNCE_MILLIS)
                writeNow()
            }
        }
    }

    /**
     * Read the store, once. Idempotent, and safe to call from every activity's `onCreate`.
     *
     * Called **before `setContent`** so a deep link from a search hit starts the read before the
     * first composition rather than after it. ⚠️ Never from `InkstoneApplication.onCreate`, which
     * runs in every process — including `:crashpoint`, which has no business reading settings.
     */
    fun warm() {
        if (_snapshot.value != null) return
        scope.launch {
            val loaded = runCatching { store.all() }.map(EditorPrefsCodec::decode).getOrElse { EditorPrefs() }
            // ⚠️ Only if still unseeded. Two activities warming concurrently must not have the
            // second one's read overwrite state the user has already started changing.
            if (_snapshot.value == null) _snapshot.value = loaded
        }
    }

    /** Record [prefs] as the newest value, to be written when the user stops moving. */
    fun changed(prefs: EditorPrefs) {
        latest = prefs
        pending = prefs
        writes.value = writes.value + 1
    }

    /**
     * Write whatever is pending, now.
     *
     * Both activities call this from `onStop`. The palette is only editable from the editor today,
     * but a flush that exists on one screen is a flush somebody has to remember to add to the next.
     */
    fun flush() {
        scope.launch { writeNow() }
    }

    private suspend fun writeNow() {
        val prefs = pending ?: return
        runCatching { store.putAll(EditorPrefsCodec.encode(prefs)) }
    }

    private companion object {
        /**
         * Long enough that a wheel drag writes once, short enough that a tap-and-leave lands before
         * `onStop` — which flushes anyway, so this bound is about wear, not correctness.
         */
        const val WRITE_DEBOUNCE_MILLIS = 400L
    }
}
