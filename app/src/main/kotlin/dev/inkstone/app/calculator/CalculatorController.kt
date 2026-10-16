package dev.inkstone.app.calculator

import android.util.Log
import dev.inkstone.core.calc.Calculator
import dev.inkstone.core.calc.Key
import dev.inkstone.core.calc.lcd.LcdFrame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger

/**
 * The engine as the controller sees it — `:core:calc`'s `Calculator`, narrowed to the three things
 * it is asked for.
 *
 * A seam for one reason: `CalculatorControllerTest` has to put a press that is *still running* in
 * front of a queued `AC`, and the real engine has no slow key to put there yet.
 */
internal interface CalcEngine {
    val frame: LcdFrame

    /** SETUP ▼6 APO, read after every key so a change to it takes effect at once. */
    val autoPowerOffMinutes: Int

    fun press(key: Key, interrupt: () -> Boolean): LcdFrame

    /** The idle timer ran out: blank glass, memory kept, `ON` brings it back. */
    fun autoPowerOff(): LcdFrame

    fun snapshot(): String
}

/** The production engine: a `Calculator` restored from [snapshot], or a fresh one when there is none. */
internal fun calculatorEngine(snapshot: String?): CalcEngine {
    val calculator = snapshot?.let { Calculator.restore(it) } ?: Calculator()
    return object : CalcEngine {
        override val frame: LcdFrame get() = calculator.frame

        override val autoPowerOffMinutes: Int get() = calculator.autoPowerOffMinutes

        override fun press(key: Key, interrupt: () -> Boolean): LcdFrame = calculator.press(key, interrupt)

        override fun autoPowerOff(): LcdFrame = calculator.autoPowerOff()

        override fun snapshot(): String = calculator.snapshot()
    }
}

/** What the one consumer is asked to do, in the order it was asked. */
private sealed interface Request {
    data class Press(val key: Key) : Request

    /** The APO timer that was started after press number [after]; stale if a key has come since. */
    data class AutoPowerOff(val after: Long) : Request
}

/**
 * ⭐ The one calculator in the app (docs/CALCULATOR.md D3): its display, whether its window is up,
 * and where it was left.
 *
 * ## Why a process-wide singleton with its own scope
 *
 * It outlives a note — closing a note clears `NoteEditorViewModel` — and it is shared with the
 * library, so the Ans you computed beside the grid is the Ans in the note, as on a desk. And the
 * scope is its own because `ViewModel.onCleared` cannot suspend: `viewModelScope` is cancelled
 * before it runs (CLAUDE.md, M12). `RecordingController` is the same shape for the same reason.
 *
 * ## ⚠️ One consumer, in order
 *
 * Every press goes into [requests] and **one** coroutine drains it, calling the engine one key at a
 * time — never a `launch` per press. CLAUDE.md (M8 follow-up 6): *"an unbounded `launch` per press
 * turns one slow operation into a burst"*, and a calculator has slow operations by design (∫, SOLVE,
 * TABLE). Later keys wait behind them, exactly as on the real one (`Calculator`'s KDoc). The engine
 * never runs on the main thread (CLAUDE.md rule 7): the scope is `Dispatchers.Default`.
 *
 * ## ⚠️ `AC` breaks a running press — counted, not flagged
 *
 * [press] counts an `AC` into [clearsQueued] *as it is queued*, and the consumer counts it back out
 * *as it is dequeued*. A running press is told to stop while any `AC` is queued behind it. A plain
 * boolean, cleared at the first `AC` dequeued, gets `AC, ∫, AC` wrong: the second `AC` is still
 * waiting while `∫` runs, and a boolean the first `AC` already cleared would let `∫` run to its
 * Time Out. Research 07 §4.6 is the behaviour; `CalculatorControllerTest` pins the ordering.
 *
 * ## Seeding
 *
 * The engine is restored from [CalculatorPreferences.seed] before the first key is processed, so
 * keys pressed while the store is still being read are queued, not lost and not applied to a fresh
 * calculator that is then thrown away. The window's open flag and its position are adopted from the
 * seed **each on its own**, and only if the user has not already set that one — a seed that arrives
 * after a press of `CALC` must not shut the window the user just opened, and must still put it where
 * it was left. (One flag for both made that press throw the remembered place away.)
 *
 * ## ⚠️ An engine that throws costs one key, never the keypad
 *
 * `:core:calc` is several hundred functions written from scratch, and errors there are values — but a
 * *bug* is a throw. Uncaught, it would end the one coroutine that drains [requests], so every later press
 * queues for ever, and then leave this never-cancelled scope for the thread's handler: on Android, a
 * crash with a note open. So a press that throws is logged, that key is dropped, and the engine is
 * rebuilt from the last snapshot that was good — what a restart would give, losing only the input
 * line (docs/CALCULATOR.md D4). An Inkstone decision, not Casio behaviour.
 *
 * ## Auto power-off is a request in the same queue
 *
 * SETUP ▼6 APO (research 02 §5.2: 10 or 60 minutes after the last key) is a timer restarted after
 * every key. When it runs out it does not touch the engine itself — it queues a request behind
 * whatever keys are waiting, stamped with how many presses had been handled when it started, and the
 * consumer ignores it if a key has been handled since. So it can never switch the calculator off in
 * the middle of a press, nor just after one the timer had not seen.
 */
class CalculatorController internal constructor(
    private val preferences: CalculatorPreferences,
    private val scope: CoroutineScope,
    private val engineFor: (String?) -> CalcEngine,
) {
    constructor(preferences: CalculatorPreferences, scope: CoroutineScope) :
        this(preferences, scope, ::calculatorEngine)

    private val requests = Channel<Request>(Channel.UNLIMITED)
    private val clearsQueued = AtomicInteger(0)

    /** Presses the consumer has handled; read and written only by the consumer. */
    private var handled = 0L

    /** The running APO timer; replaced after every key. Touched only by the consumer. */
    private var idleTimer: Job? = null

    private val _frame = MutableStateFlow(LcdFrame.BLANK)

    /** What the LCD shows. Blank glass until the stored calculator has been restored. */
    val frame: StateFlow<LcdFrame> = _frame.asStateFlow()

    private val _open = MutableStateFlow(false)

    /** Whether the window is up — on every screen at once (D3). */
    val open: StateFlow<Boolean> = _open.asStateFlow()

    private val _position = MutableStateFlow<WindowPosition?>(null)

    /** Where the user left the window, unclamped; null until the first drag, and after a dock. */
    val position: StateFlow<WindowPosition?> = _position.asStateFlow()

    private val _face = MutableStateFlow<Float?>(null)

    /** The face's width in dp as the user sized it; null for the default. */
    val face: StateFlow<Float?> = _face.asStateFlow()

    /** The user resized the window before the seed arrived; the seed's size is stale. */
    @Volatile
    private var faceTouched = false

    /** True after a dock, false after a drag, null until either — what is written as `calc.window.docked`. */
    @Volatile
    private var dockedChoice: Boolean? = null

    /** The newest engine snapshot. Null until the seed is read, so a write before then omits it. */
    @Volatile
    private var snapshot: String? = null

    /** The user opened or closed the window before the seed arrived; the seed's flag is stale. */
    @Volatile
    private var openTouched = false

    /** The user dragged the window before the seed arrived; the seed's position is stale. */
    @Volatile
    private var positionTouched = false

    init {
        preferences.warm()
        scope.launch { run() }
    }

    /** Queue one physical key. Never blocks and never drops: the channel is unbounded. */
    fun press(key: Key) {
        if (key == Key.AC) clearsQueued.incrementAndGet()
        requests.trySend(Request.Press(key))
    }

    fun toggle() = setOpen(!_open.value)

    fun close() = setOpen(false)

    /** Remember where the user dropped the window. The caller has already clamped it to the screen. */
    fun moveTo(position: WindowPosition) {
        positionTouched = true
        dockedChoice = false
        _position.value = position
        persist()
    }

    /** Put the window back in the panel column: it forgets where it was dragged to. */
    fun dock() {
        positionTouched = true
        dockedChoice = true
        _position.value = null
        persist()
    }

    /** Remember the face's width, in dp, from a drag of the window's corner. */
    fun resizeTo(face: Float) {
        faceTouched = true
        _face.value = face
        persist()
    }

    private fun setOpen(open: Boolean) {
        openTouched = true
        _open.value = open
        persist()
    }

    private suspend fun run() {
        val seed = preferences.seed.filterNotNull().first()
        if (!openTouched) _open.value = seed.open
        if (!positionTouched) _position.value = seed.window
        if (!faceTouched) _face.value = seed.face
        var engine = engineFor(seed.snapshot)
        snapshot = seed.snapshot
        _frame.value = engine.frame
        restartIdleTimer(engine)
        for (request in requests) {
            when (request) {
                is Request.Press -> {
                    if (request.key == Key.AC) clearsQueued.decrementAndGet()
                    engine = pressOrRecover(engine, request.key)
                    handled++
                    restartIdleTimer(engine)
                    persist()
                }

                is Request.AutoPowerOff -> if (request.after == handled) _frame.value = engine.autoPowerOff()
            }
        }
    }

    /** Counts APO's minutes from now; a stale timer's request is dropped by its stamp, not by a race. */
    private fun restartIdleTimer(engine: CalcEngine) {
        idleTimer?.cancel()
        val stamp = handled
        val millis = engine.autoPowerOffMinutes * MILLIS_PER_MINUTE
        idleTimer = scope.launch {
            delay(millis)
            requests.trySend(Request.AutoPowerOff(stamp))
        }
    }

    /** One key into [engine]; the engine to use next — itself, or a rebuild if the press threw. */
    @Suppress("TooGenericExceptionCaught")
    // Catching broadly is the point: the engine's errors are values, so anything that escapes it is a
    // bug of unknown type, and every one of them must cost one key rather than the keypad (class KDoc).
    // `press` does not suspend, so there is no cancellation here to swallow.
    private fun pressOrRecover(engine: CalcEngine, key: Key): CalcEngine = try {
        _frame.value = engine.press(key) { clearsQueued.get() > 0 }
        snapshot = engine.snapshot()
        engine
    } catch (failure: Exception) {
        Log.e(TAG, "the calculator engine threw on $key; rebuilt from its last good snapshot", failure)
        val rebuilt = runCatching { engineFor(snapshot) }.getOrElse { engineFor(null) }
        _frame.value = rebuilt.frame
        rebuilt
    }

    /**
     * Records the newest state for the debounced write. ⚠️ `@Synchronized` because it runs on two
     * threads — the main thread for the window, the engine's for a key — and reads three fields: two
     * unsynchronised calls could each read before the other wrote, and the last to store `pending`
     * would put back a stale `open` or position for the debounce to save.
     */
    @Synchronized
    private fun persist() {
        preferences.changed(
            CalculatorPrefs(
                snapshot = snapshot,
                open = _open.value,
                window = _position.value,
                face = _face.value,
                docked = dockedChoice,
            ),
        )
    }

    private companion object {
        const val TAG = "CalculatorController"
        const val MILLIS_PER_MINUTE = 60_000L
    }
}
