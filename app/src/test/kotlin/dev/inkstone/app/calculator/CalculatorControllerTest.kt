package dev.inkstone.app.calculator

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.inkstone.core.calc.Key
import dev.inkstone.core.calc.lcd.LcdFrame
import dev.inkstone.core.database.InkstoneDatabase
import dev.inkstone.core.database.settings.SettingStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The one calculator's key queue, its `AC` interrupt and its persistence, over the **real**
 * `SettingStore` and schema.
 *
 * ## ⚠️ What the engine here is
 *
 * A recording fake in front of the controller's `CalcEngine` seam: it notes each key and whether it
 * was being told to stop at that moment. That is the whole point — `Calculator` has no slow key yet,
 * and the interrupt rule is about a press that is *still running* when `AC` arrives. The keys are all
 * queued before the consumer runs, which is exactly the state a slow `∫` leaves the queue in.
 *
 * It says nothing about the window, the keypad or the device. `backgroundScope` stands in for the
 * process-wide scope, which in production is never cancelled (`RecordingControllerTest` explains why
 * a test must not hand such a class its own test scope).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CalculatorControllerTest {

    /** Remembers each press and what `interrupt()` said while it ran; throws on [throwOn], as a bug would. */
    private class FakeEngine : CalcEngine {
        val presses = mutableListOf<Pair<Key, Boolean>>()
        var throwOn: Key? = null
        var poweredOff = 0

        override val frame: LcdFrame = LcdFrame.BLANK

        override val autoPowerOffMinutes: Int = 10

        override fun press(key: Key, interrupt: () -> Boolean): LcdFrame {
            presses += key to interrupt()
            check(key != throwOn) { "a bug in the engine, on $key" }
            return LcdFrame(BooleanArray(LcdFrame.WIDTH * LcdFrame.HEIGHT), emptySet(), text = "after $key")
        }

        override fun autoPowerOff(): LcdFrame {
            poweredOff++
            return LcdFrame.OFF
        }

        override fun snapshot(): String = "presses=${presses.size}"
    }

    private class Fixture(scope: TestScope) {
        private val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        private val database = InkstoneDatabase(driver).also { InkstoneDatabase.Schema.create(driver) }
        val store = SettingStore(database, Dispatchers.Unconfined)
        val preferences = CalculatorPreferences(store, scope.backgroundScope)
        val engine = FakeEngine()
        val restoredFrom = mutableListOf<String?>()
        private val background = scope.backgroundScope

        fun controller() = CalculatorController(preferences, background) { snapshot ->
            restoredFrom += snapshot
            engine
        }
    }

    @Test
    fun `keys pressed before the store is read are pressed after the restore and in order`() = runTest {
        val fixture = Fixture(this)
        fixture.store.putAll(mapOf(CalculatorPrefsCodec.SNAPSHOT to "saved"))
        val controller = fixture.controller()
        listOf(Key.D1, Key.PLUS, Key.D2, Key.EQUALS).forEach(controller::press)
        runCurrent()

        assertEquals(listOf<String?>("saved"), fixture.restoredFrom)
        assertEquals(listOf(Key.D1, Key.PLUS, Key.D2, Key.EQUALS), fixture.engine.presses.map { it.first })
        assertEquals("after EQUALS", controller.frame.value.text)
    }

    @Test
    fun `ten idle minutes switch the calculator off and the glass goes blank`() = runTest {
        val fixture = Fixture(this)
        val controller = fixture.controller()
        controller.press(Key.D1)
        runCurrent()
        advanceTimeBy(TEN_MINUTES - 1)
        runCurrent()
        assertEquals(0, fixture.engine.poweredOff)

        advanceTimeBy(2)
        runCurrent()
        assertEquals(1, fixture.engine.poweredOff)
        assertTrue(controller.frame.value.off)
    }

    @Test
    fun `a key pressed before the ten minutes are up starts them again`() = runTest {
        val fixture = Fixture(this)
        val controller = fixture.controller()
        controller.press(Key.D1)
        runCurrent()
        advanceTimeBy(TEN_MINUTES - ONE_MINUTE)
        controller.press(Key.D2)
        runCurrent()
        advanceTimeBy(TEN_MINUTES - ONE_MINUTE)
        runCurrent()
        assertEquals(0, fixture.engine.poweredOff, "the first key's timer must not switch it off after the second")

        advanceTimeBy(ONE_MINUTE + 1)
        runCurrent()
        assertEquals(1, fixture.engine.poweredOff)
    }

    @Test
    fun `an AC queued behind a running press interrupts it`() = runTest {
        val fixture = Fixture(this)
        val controller = fixture.controller()
        controller.press(Key.INTEGRAL)
        controller.press(Key.AC)
        runCurrent()

        assertEquals(listOf(Key.INTEGRAL to true, Key.AC to false), fixture.engine.presses)
    }

    @Test
    fun `an AC that has already been handled does not interrupt the press after it`() = runTest {
        val fixture = Fixture(this)
        val controller = fixture.controller()
        controller.press(Key.AC)
        controller.press(Key.INTEGRAL)
        runCurrent()

        assertEquals(listOf(Key.AC to false, Key.INTEGRAL to false), fixture.engine.presses)
    }

    @Test
    fun `a second AC still waiting interrupts the press the first one came before`() = runTest {
        // The case a single boolean gets wrong: the first AC would clear it, and the integral would
        // run to its Time Out with an AC queued right behind it.
        val fixture = Fixture(this)
        val controller = fixture.controller()
        controller.press(Key.AC)
        controller.press(Key.INTEGRAL)
        controller.press(Key.AC)
        runCurrent()

        assertEquals(Key.INTEGRAL to true, fixture.engine.presses[1])
        assertEquals(Key.AC to false, fixture.engine.presses[2])
    }

    @Test
    fun `the snapshot is written once the keys stop and flush writes it at once`() = runTest {
        val fixture = Fixture(this)
        val controller = fixture.controller()
        controller.press(Key.D7)
        runCurrent()
        assertNull(fixture.store.all()[CalculatorPrefsCodec.SNAPSHOT], "written before the debounce")

        advanceTimeBy(CalculatorPreferences.WRITE_DEBOUNCE_MILLIS + 1)
        runCurrent()
        assertEquals("presses=1", fixture.store.all()[CalculatorPrefsCodec.SNAPSHOT])

        controller.press(Key.D8)
        runCurrent()
        fixture.preferences.flush()
        runCurrent()
        assertEquals("presses=2", fixture.store.all()[CalculatorPrefsCodec.SNAPSHOT])
    }

    @Test
    fun `the window comes back open where it was left`() = runTest {
        val fixture = Fixture(this)
        fixture.store.putAll(
            mapOf(
                CalculatorPrefsCodec.OPEN to "true",
                CalculatorPrefsCodec.WINDOW_X to "300.0",
                CalculatorPrefsCodec.WINDOW_Y to "120.5",
            ),
        )
        val controller = fixture.controller()
        runCurrent()

        assertTrue(controller.open.value)
        assertEquals(WindowPosition(300f, 120.5f), controller.position.value)
    }

    @Test
    fun `a toggle before the store is read is neither undone by the seed nor erases the memory`() = runTest {
        val fixture = Fixture(this)
        fixture.store.putAll(mapOf(CalculatorPrefsCodec.OPEN to "false", CalculatorPrefsCodec.SNAPSHOT to "saved"))
        val controller = fixture.controller()
        controller.toggle()
        runCurrent()
        advanceTimeBy(CalculatorPreferences.WRITE_DEBOUNCE_MILLIS + 1)
        runCurrent()

        assertTrue(controller.open.value, "the seed shut a window the user had just opened")
        assertEquals("saved", fixture.store.all()[CalculatorPrefsCodec.SNAPSHOT])
        assertEquals("true", fixture.store.all()[CalculatorPrefsCodec.OPEN])
    }

    @Test
    fun `opening the window before the store is read still puts it where it was left`() = runTest {
        // Opening and moving are two facts. One "touched" flag for both made a press of CALC during
        // the read throw away the remembered place, and the window hung from the chip instead.
        val fixture = Fixture(this)
        fixture.store.putAll(
            mapOf(CalculatorPrefsCodec.WINDOW_X to "300.0", CalculatorPrefsCodec.WINDOW_Y to "120.5"),
        )
        val controller = fixture.controller()
        controller.toggle()
        runCurrent()

        assertTrue(controller.open.value)
        assertEquals(WindowPosition(300f, 120.5f), controller.position.value)
    }

    @Test
    fun `a drag before the store is read is not undone by the seed`() = runTest {
        val fixture = Fixture(this)
        fixture.store.putAll(
            mapOf(CalculatorPrefsCodec.WINDOW_X to "300.0", CalculatorPrefsCodec.WINDOW_Y to "120.5"),
        )
        val controller = fixture.controller()
        controller.moveTo(WindowPosition(40f, 200f))
        runCurrent()

        assertEquals(WindowPosition(40f, 200f), controller.position.value)
    }

    @Test
    fun `an engine that throws on a key costs that key and not the keypad`() = runTest {
        // The engine is several hundred functions written from scratch; one of them will have a bug.
        // Uncaught, the throw ends the one coroutine that drains the keys — every later press is
        // queued for ever — and leaves this never-cancelled scope for the thread's handler, which on
        // Android is a crash with a note open. The key that hit it is lost; nothing after it is.
        val fixture = Fixture(this)
        fixture.engine.throwOn = Key.SIN
        val controller = fixture.controller()
        listOf(Key.D1, Key.SIN, Key.D2).forEach(controller::press)
        runCurrent()

        assertEquals(listOf(Key.D1, Key.SIN, Key.D2), fixture.engine.presses.map { it.first })
        // Rebuilt from the last snapshot that was good — the one taken after D1 — not from nothing.
        assertEquals(listOf<String?>(null, "presses=1"), fixture.restoredFrom)
        assertEquals("after D2", controller.frame.value.text)
    }

    private companion object {
        /** SETUP ▼6 APO's default (research 02 §5.2), which the fake engine reports. */
        const val TEN_MINUTES = 600_000L
        const val ONE_MINUTE = 60_000L
    }
}
