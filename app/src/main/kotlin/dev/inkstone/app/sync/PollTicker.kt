package dev.inkstone.app.sync

import dev.inkstone.core.model.Clock
import dev.inkstone.core.model.WorkScheduler
import dev.inkstone.core.sync.AccountStore
import dev.inkstone.core.sync.NextcloudCapabilities
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.seconds

/**
 * ⭐ **SPEC §7.6's foreground poll, paced by what the server asked for.**
 *
 * WorkManager's periodic floor is fifteen minutes and a shorter period is silently clamped, so the
 * background job ([ReconcileWorker]) cannot be the thing that makes a peer's edit show up while
 * somebody is looking at the library. This is: a plain coroutine that nudges the reconciler on an
 * interval, alive only while a screen is.
 *
 * ## Why the interval is read from capabilities and not chosen here
 *
 * SPEC §7.6: *"honour `capabilities.core.pollinterval` (default 60 s) **as the floor**"*. It has
 * been parsed and cached since M5 ([NextcloudCapabilities.pollIntervalSeconds]) and until M6
 * nothing paced against it — which is the kind of gap that only becomes visible when an
 * administrator sets it to 300 and the client keeps asking every minute. A client that polls faster
 * than the server asks is a client the administrator eventually blocks, and the block looks to the
 * user like the app being broken.
 *
 * Two adjustments on top, both upwards only:
 *
 * - **`bruteforce.delay`.** Non-zero means this client is *already* in the server's penalty box
 *   (SPEC §7.2). Not an error, and not a reason to stop — a reason to back off before making it
 *   worse, so the delay is added to the interval rather than compared against it.
 * - **The slowest account wins.** One ticker for the whole app, so the interval is the `max` across
 *   accounts. Taking the `min` would let an instance with a permissive setting set the pace for one
 *   that asked to be left alone.
 *
 * ## What it does *not* do
 *
 * It does not talk to Nextcloud. It calls [WorkScheduler.enqueueReconcile], so the request is made
 * by a job with `NetworkType.CONNECTED` on it and survives the screen going away mid-pass — SPEC
 * §2.4's *"sync runs under WorkManager only"* is not weakened by having a timer in front of it. It
 * also means there is no "am I online" boolean anywhere: an offline device enqueues work that simply
 * does not run, which is what keeps airplane-mode editing free of requests.
 *
 * `notify_push` is deliberately **not** implemented. It is not bundled with Nextcloud and not
 * commonly available (a separate app, a Rust daemon, Redis and a reverse-proxy route), so polling is
 * the mechanism and push would only ever be an optimisation on top of it.
 */
@Singleton
class PollTicker internal constructor(
    private val accounts: AccountStore,
    private val scheduler: WorkScheduler,
    /**
     * Only for [lastPollAtEpochMillis] — pacing, never ordering.
     *
     * CLAUDE.md forbids a wall clock for ordering sync operations because device clocks disagree.
     * Two readings of *this* device's clock a minute apart is the same legitimate use
     * `Compaction.isAllowed`'s `nowEpochMillis` is documented as, and the failure mode of a clock
     * that jumps is one poll too early or one interval too long — never a wrong merge.
     */
    private val clock: Clock,
    /**
     * ⚠️ **The ticker's own scope, not an Activity's.**
     *
     * A screen's `lifecycleScope` cannot own this, and the reason is Android's *overlapping* activity
     * lifecycle: opening a note runs `NoteActivity.onStart` **before** `MainActivity.onStop`, so a
     * ticker owned by whichever screen started it would be cancelled by the one it replaced a moment
     * after the replacement asked for it — and the editor, which is exactly where SPEC §13.2 row 10
     * needs a live poll, would be the screen that never polls.
     *
     * So the scope belongs to the singleton and [retain]/[release] count screens. `Dispatchers.Default`
     * in production, because the loop does nothing but read a few rows and enqueue work; the request
     * is a WorkManager job's.
     *
     * A parameter for `NoteEditor`'s reason — the pacing is this class's whole subject and a real
     * dispatcher cannot be driven by a test — and a *secondary* `@Inject` constructor below rather
     * than a default value, because Hilt requires every parameter of an injected constructor to be
     * bound and would fail the build asking for a `CoroutineScope`.
     */
    private val scope: CoroutineScope,
) {

    @Inject
    constructor(accounts: AccountStore, scheduler: WorkScheduler, clock: Clock) :
        this(accounts, scheduler, clock, CoroutineScope(SupervisorJob() + Dispatchers.Default))

    private var job: Job? = null

    /** How many screens are in front. Only ever touched from the main thread (`onStart`/`onStop`). */
    private var screens = 0

    /**
     * ⭐ **When the last poll was asked for — the state that survives the loop being cancelled.**
     *
     * Without it the interval is only honoured *within* one run of the loop, and the loop does not
     * survive the screen count reaching zero. A rotation is `onStop` then `onStart` with nothing in
     * front, so it cancelled the job and restarted it — and the restart polls immediately, by
     * design, because somebody opening the app should not wait a minute for the note they wrote on
     * the other tablet. Rotate the tablet ten times and that is ten passes inside a minute, from
     * a class whose entire subject is not asking the server too often. Same for every
     * background-and-return.
     *
     * Null means "never", which polls at once — the intended behaviour on a cold start. Null rather
     * than zero on purpose: zero *happens* to poll immediately only because a real epoch is eleven
     * orders of magnitude larger than the interval, which is an implicit assumption about the value
     * of a clock, and the kind that holds until something sets the clock.
     */
    private var lastPollAtEpochMillis: Long? = null

    /**
     * A screen came to the front. Idempotent in effect: the loop starts on the first screen and the
     * count keeps it alive across an activity handover.
     */
    fun retain() {
        screens++
        if (job?.isActive == true) return
        job = scope.launch {
            // One rule, at the head of the loop: never sooner than `interval` after the last poll.
            // Expressing it as a cooldown rather than as "poll, then sleep" is what makes it hold
            // across a cancellation — see [lastPollAtEpochMillis]. The interval is re-read after
            // every wait, so signing in or a capabilities refresh changes the pace on the next tick.
            while (isActive) {
                val interval = intervalMillis()
                val last = lastPollAtEpochMillis
                // Coerced into the interval so a clock that has gone *backwards* since the last poll
                // cannot park the loop for however far back it went. Forwards costs one early poll.
                val wait = last?.let { (it + interval - clock.nowEpochMillis()).coerceIn(0L, interval) } ?: 0L
                if (wait > 0L) {
                    delay(wait)
                    continue
                }
                lastPollAtEpochMillis = clock.nowEpochMillis()
                // It costs one `PROPFIND Depth:0` per account when nothing has changed (SPEC §7.4
                // rule 1), which is what makes a sixty-second poll affordable at all.
                scheduler.enqueueReconcile()
            }
        }
    }

    /** A screen went away. The loop stops when the last one does. */
    fun release() {
        screens = (screens - 1).coerceAtLeast(0)
        if (screens > 0) return
        job?.cancel()
        job = null
    }

    /**
     * The interval the slowest account asks for, never below [FLOOR_SECONDS].
     *
     * Re-read every tick rather than cached, so signing in, signing out or a capabilities refresh
     * changes the pace on the next tick instead of on the next launch. It is one indexed query per
     * minute against a table with a handful of rows.
     *
     * A signed-out or halted account is excluded: it will get zero requests from the reconciler
     * anyway (CLAUDE.md rule 5), so letting it set the interval would mean an account nothing talks
     * to slowing down every account that works.
     */
    private suspend fun intervalMillis(): Long {
        val usable = accounts.all().filter { it.isUsable }
        val seconds = usable
            .map { account ->
                val capabilities = account.capabilities ?: NextcloudCapabilities()
                capabilities.pollIntervalSeconds + capabilities.bruteforceDelaySeconds
            }
            .maxOrNull()
            ?: NextcloudCapabilities.DEFAULT_POLL_INTERVAL_SECONDS

        return seconds.coerceAtLeast(FLOOR_SECONDS).seconds.inWholeMilliseconds
    }

    private companion object {
        /**
         * The fastest this will ever poll, whatever a server says.
         *
         * A floor on the floor. `pollinterval` is a value from a remote instance, and a
         * misconfigured or hostile one returning 1 would otherwise have this app making a request a
         * second — from inside a loop with no backoff, against the same server's brute-force
         * protection.
         */
        const val FLOOR_SECONDS = 60L
    }
}
