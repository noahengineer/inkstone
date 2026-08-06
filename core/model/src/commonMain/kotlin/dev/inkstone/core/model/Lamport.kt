package dev.inkstone.core.model

/**
 * SPEC §4.1 — the ordering primitive for every op in the log.
 *
 * Ops are ordered by Lamport clock and **never by wall clock**: device clocks
 * disagree, often by minutes, and a tablet that has been off for a week comes
 * back with a clock that would reorder a whole week of someone else's work
 * silently. [Instant]-shaped time exists in this app for display only.
 *
 * The comparator is exactly `compareBy({ counter }, { deviceId })`, with
 * [deviceId] compared as a plain lexicographic string — no locale collation,
 * because two devices in different locales must agree on the order and
 * `Collator` does not guarantee that.
 */
public data class LamportClock(val counter: Long, val deviceId: String) : Comparable<LamportClock> {

    override fun compareTo(other: LamportClock): Int {
        val byCounter = counter.compareTo(other.counter)
        // String.compareTo is UTF-16 code-unit order on every Kotlin target.
        return if (byCounter != 0) byCounter else deviceId.compareTo(other.deviceId)
    }
}

/**
 * Issues clocks for locally created ops.
 *
 * **The `max + 1` rule (SPEC §4.1) is the whole reason this is a class.** On
 * document open the counter starts at the highest counter seen in any loaded op,
 * so the next op issued sorts after all known history. Without it, a reinstall —
 * which generates a fresh [deviceId] and a counter of zero — writes ops that
 * sort *behind* everything already in the document, so every edit made after the
 * reinstall loses every conflict it takes part in. The failure is silent: the ops
 * are all present, they just fold in an order where the older values win.
 *
 * Not thread-safe, deliberately. Clocks are issued where edits originate — the
 * UI thread — and a mutex on the edit path is a cost with no benefit. Encoding
 * and I/O happen later, off-thread, from already-stamped ops.
 */
public class LamportClockGenerator(public val deviceId: String, highestSeen: Long = 0L) {

    private var counter: Long = highestSeen

    /** The highest counter this generator has issued or been shown. */
    public val highestSeen: Long get() = counter

    /**
     * Fold an observed clock in, from any device.
     *
     * Called for every op read from disk or received from a peer, so that a
     * locally issued op always sorts after everything this device has seen.
     */
    public fun observe(clock: LamportClock) {
        if (clock.counter > counter) counter = clock.counter
    }

    public fun observeAll(ops: Iterable<PageOp>) {
        for (op in ops) observe(op.clock)
    }

    /** The next clock for a locally created op. */
    public fun next(): LamportClock = LamportClock(++counter, deviceId)

    public companion object {
        /**
         * SPEC §4.1 — "on document open: counter = (max counter across all loaded
         * ops) + 1". [next] pre-increments, so seeding with the maximum yields
         * exactly that for the first op issued.
         */
        public fun onOpen(deviceId: String, ops: Iterable<PageOp>): LamportClockGenerator =
            LamportClockGenerator(deviceId).also { it.observeAll(ops) }
    }
}

/**
 * An LWW register: a value together with the clock that last wrote it.
 *
 * Every field an op can set is one of these (SPEC §4.2). [write] keeps the
 * later clock's value, which is what makes concurrent edits converge without a
 * merge dialog and without arithmetic on non-commuting transforms.
 */
public data class Register<T>(val value: T, val clock: LamportClock) {

    /**
     * @return the winning register — `this` unless [clock] is strictly later.
     *
     * Equal clocks are impossible between different devices (the [deviceId]
     * tiebreak) and impossible on one device (the counter only increases), so
     * "not later" resolving to the incumbent is a defensive choice with no
     * observable behaviour rather than a policy.
     */
    public fun write(value: T, clock: LamportClock): Register<T> =
        if (clock > this.clock) Register(value, clock) else this
}
