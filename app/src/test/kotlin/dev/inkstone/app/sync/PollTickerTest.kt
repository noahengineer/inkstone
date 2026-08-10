package dev.inkstone.app.sync

import dev.inkstone.core.model.Clock
import dev.inkstone.core.model.DocumentId
import dev.inkstone.core.model.WorkScheduler
import dev.inkstone.core.sync.AccountState
import dev.inkstone.core.sync.AccountStore
import dev.inkstone.core.sync.SyncAccount
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * ⭐ **SPEC §7.6's poll interval is a floor on requests, not a floor inside one run of a loop.**
 *
 * [PollTicker] polls immediately on [PollTicker.retain] and then on the interval, which is right:
 * somebody opening the app to find the note they wrote on the other tablet should not wait a minute
 * for it, and the cost when nothing has changed is one `PROPFIND Depth:0` per account (SPEC §7.4
 * rule 1).
 *
 * What that misses is that the loop **does not survive the screen count reaching zero**. A rotation
 * is `onStop` then `onStart` with nothing in front, so it cancelled the job and started a new one —
 * and the new one polls at once. Rotate the tablet ten times and that is ten passes inside a minute,
 * out of the class whose entire subject is not asking the server too often; the same for every
 * background-and-return, and for every trip to the library and back on a device where the two
 * activities do not overlap. Nothing fails, the server just gets hit at the rate the user fidgets —
 * which on a Nextcloud with brute-force protection on by default is the pattern that eventually
 * blocks the IP, and to the user the block looks like the app being broken.
 *
 * So the pacing is a persisted "when did I last poll" and a cooldown at the head of the loop. These
 * tests drive the virtual clock and the ticker's clock off the same `TestScope`, which is the only
 * way the two can be asserted against each other.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PollTickerTest {

    /** Counts what the loop asked for. The reconciler itself is a WorkManager job's business. */
    private class CountingScheduler : WorkScheduler {
        var passes = 0
            private set

        override fun enqueueOutboxDrain() = Unit

        override fun enqueueMaterialization(noteId: DocumentId) = Unit

        override fun enqueueReconcile() {
            passes++
        }

        override fun enqueueReconcile(noteId: DocumentId) = Unit
    }

    /**
     * No accounts, which is the shortest way to the default interval: `intervalMillis` takes the
     * `max` over usable accounts and falls back to `DEFAULT_POLL_INTERVAL_SECONDS` when there are
     * none, then coerces to the sixty-second floor. The ticker enqueues regardless — a pass with no
     * account makes no request, and that is the reconciler's decision to make, not this class's.
     */
    private class NoAccounts : AccountStore {
        override suspend fun all(): List<SyncAccount> = emptyList()

        override suspend fun find(accountId: String): SyncAccount? = null

        override suspend fun upsert(account: SyncAccount) = Unit

        override suspend fun setState(accountId: String, state: AccountState, lastError: String?) = Unit

        override suspend fun remove(accountId: String) = Unit
    }

    @Test
    fun `the first screen polls at once and then on the interval`() = runTest {
        val scheduler = CountingScheduler()
        // The ticker's clock *is* the test scheduler's virtual clock, so "a minute of `delay`" and "a
        // minute according to the ticker" are the same minute. Two independent clocks would let this
        // pass for the wrong reason.
        val ticker = PollTicker(NoAccounts(), scheduler, Clock { currentTime }, scope = this)

        ticker.retain()
        runCurrent()
        assertEquals(1, scheduler.passes, "opening the app did not poll")

        advanceTimeBy(INTERVAL_MILLIS - 1)
        assertEquals(1, scheduler.passes, "polled before the interval was up")

        advanceTimeBy(2)
        assertEquals(2, scheduler.passes, "never polled again")

        ticker.release()
    }

    @Test
    fun `a screen handover does not poll because the loop was never stopped`() = runTest {
        // Android runs `NoteActivity.onStart` before `MainActivity.onStop`, so the count goes
        // 1 → 2 → 1 and the job stays alive. This is the case the screen *counting* exists for, and
        // it is asserted here so that a change to the counting shows up as a request rate.
        val scheduler = CountingScheduler()
        val ticker = PollTicker(NoAccounts(), scheduler, Clock { currentTime }, scope = this)

        ticker.retain()
        runCurrent()
        ticker.retain()
        ticker.release()
        runCurrent()

        assertEquals(1, scheduler.passes, "a handover between two screens cost a poll")
        ticker.release()
    }

    @Test
    fun `a rotation that restarts the loop waits out the rest of the interval`() = runTest {
        // ⚠️ The one that failed before the cooldown existed. `release()` cancels the job; `retain()`
        // starts a new one; the new one polled immediately because "poll, then sleep" keeps its state
        // in the loop's own stack frame, which the cancellation threw away.
        val scheduler = CountingScheduler()
        val ticker = PollTicker(NoAccounts(), scheduler, Clock { currentTime }, scope = this)

        ticker.retain()
        runCurrent()
        assertEquals(1, scheduler.passes)

        repeat(TEN_ROTATIONS) {
            ticker.release()
            ticker.retain()
            runCurrent()
        }

        assertEquals(1, scheduler.passes, "each restart of the loop spent a pass the interval had not earned")

        // And the interval is still measured from the *first* poll, not from the last restart, so the
        // second pass lands when it always would have.
        advanceTimeBy(INTERVAL_MILLIS + 1)
        assertEquals(2, scheduler.passes, "the restarts pushed the next poll out instead of being absorbed")

        ticker.release()
    }

    private companion object {
        /** [PollTicker.FLOOR_SECONDS], which is also the default `pollinterval`. */
        const val INTERVAL_MILLIS = 60_000L

        const val TEN_ROTATIONS = 10
    }
}
