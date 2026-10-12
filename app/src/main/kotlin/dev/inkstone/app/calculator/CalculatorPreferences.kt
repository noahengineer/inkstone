package dev.inkstone.app.calculator

import dev.inkstone.core.database.settings.SettingStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * The calculator's remembered state, read once and written on a debounce — `EditorPreferences`,
 * copied for the `calc.*` keys (research 08 §4.9).
 *
 * ## ⚠️ [seed] is a SEED channel, not a state holder
 *
 * Written by [warm] and by nothing else. `CalculatorController` collects the first non-null value,
 * restores the engine and the window from it, and from then on every change flows the other way,
 * into [changed]. Publishing each change back into [seed] would make it a second writer of the very
 * state the user is pressing keys into — `EditorPreferences`' *"two mechanisms for one change"*.
 *
 * ## Why a debounce
 *
 * A sum typed quickly is a dozen key presses, each a new snapshot. Writing every one is wear for
 * nothing; the newest is the only one worth keeping. ⚠️ [writeNow] reads [pending] at **write**
 * time, so a [flush] racing the debounce still writes the newest value. Both activities call
 * [flush] from `onStop`, which bounds what a process kill can cost to nothing the user can see.
 *
 * ## Failure is a default, never a hang
 *
 * [warm] always ends in a value. An unreadable store costs the calculator its memories for that
 * session and nothing else; a controller waiting forever on a read that threw would be a keypad
 * that silently ignores every press.
 */
class CalculatorPreferences(private val store: SettingStore, private val scope: CoroutineScope) {

    private val _seed = MutableStateFlow<CalculatorPrefs?>(null)

    /** Null until [warm] completes. Collected once, by `CalculatorController`. */
    val seed: StateFlow<CalculatorPrefs?> = _seed.asStateFlow()

    @Volatile
    private var pending: CalculatorPrefs? = null

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
     * Read the store, once. Idempotent.
     *
     * Called by `CalculatorController` when it is first built — which is the first screen that
     * composes the `CALC` chip, in the default process. ⚠️ Never from `InkstoneApplication.onCreate`,
     * which runs in `:crashpoint` too (`EditorPreferences.warm`).
     */
    fun warm() {
        if (_seed.value != null) return
        scope.launch {
            val loaded = runCatching { store.all() }.map(CalculatorPrefsCodec::decode).getOrElse { CalculatorPrefs() }
            if (_seed.value == null) _seed.value = loaded
        }
    }

    /** Record [prefs] as the newest value, to be written when the keys stop. */
    fun changed(prefs: CalculatorPrefs) {
        pending = prefs
        writes.value = writes.value + 1
    }

    /** Write whatever is pending, now. Both activities call this from `onStop`. */
    fun flush() {
        scope.launch { writeNow() }
    }

    private suspend fun writeNow() {
        val prefs = pending ?: return
        runCatching { store.putAll(CalculatorPrefsCodec.encode(prefs)) }
    }

    internal companion object {
        /** `EditorPreferences`' value: a burst of keys writes once, a tap-and-leave lands before `onStop`. */
        const val WRITE_DEBOUNCE_MILLIS = 400L
    }
}
