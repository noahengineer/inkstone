package dev.inkstone.app.note

/**
 * ⭐ The decisions behind [PageAssetCache] — what to decode, at what size, and what may be dropped —
 * over any picture type, so that a JVM test can make them. `Bitmap` cannot exist in a unit test
 * (`BitmapFactory` answers null there), and a cache whose policy can only be checked on a device is
 * the M6 shape again: correct-looking and unverified.
 *
 * ## The bug this replaced
 *
 * The cache held **48 MB** of pictures, *"about a dozen images at M8c's 2,048 px cap"*. A 2,048 ×
 * 1,536 photograph decodes to 12 MiB, so 48 MiB is **four** of them, not twelve — and the physics
 * "Electrical energy" note has eleven. Every one past the fourth was evicted as the next was
 * decoded, so seven of eleven drew the crossed missing-picture box on the tablet; and because the
 * old cache re-read and re-decoded the whole `assets/` family on every revision, each edit decoded
 * the evicted ones again, evicting the rest, for as long as the note was open. That is both halves
 * of the report — the pictures, and some of the slowness.
 *
 * ## The policy
 *
 * - **Only what is asked for is decoded.** A name is *wanted* when the painter asks for it and it
 *   is not here ([get]), or when a page load names it ([beginPage]). A deleted picture's entry stays
 *   in the bundle forever (SPEC §4.9.4); nothing asks for it, so it is never decoded.
 * - **What the page on screen asked for is never evicted.** [trim] drops least-recently-used
 *   pictures from other pages first and stops rather than evict one this page is drawing: a budget
 *   smaller than the page is a thrash on every frame, which is the bug above.
 * - **A page that does not fit is decoded smaller, not partly.** [sampleSizeFor] picks the smallest
 *   power-of-two downsample that lets everything this page has asked for fit the budget. Softer
 *   when zoomed right in on a page of thirty photographs; never a crossed box.
 * - **"Being decoded" and "not there" are different answers** ([isPending]). A decode that found no
 *   bytes — an image merged from a peer one poll before its bytes — is parked in [absent] and retried
 *   only when [retryAbsent] is called, so a missing picture is not re-requested on every frame.
 *
 * Thread-safe: [get] is called from the UI thread while a decode on another reports in.
 */
internal class PictureCache<T : Any>(
    /** The most decoded pixels to hold, in bytes. */
    val budgetBytes: Long,
    private val sizeOf: (T) -> Long,
    /** False for a picture that can no longer be drawn — a recycled bitmap. */
    private val isUsable: (T) -> Boolean,
) {

    private class Entry<T>(val picture: T, val bytes: Long)

    /** Access-ordered, so iteration starts at the least recently drawn. */
    private val entries = LinkedHashMap<String, Entry<T>>(INITIAL_CAPACITY, LOAD_FACTOR, true)

    /** Asked for and not yet handed to a decode. */
    private val wanted = LinkedHashSet<String>()

    /** Handed to a decode that has not reported back. */
    private val inFlight = HashSet<String>()

    /** A decode found no bytes for these. Not asked for again until [retryAbsent]. */
    private val absent = HashSet<String>()

    /** Everything asked for since the page last changed — the pictures [trim] will not drop. */
    private val onPage = HashSet<String>()

    private var heldBytes = 0L

    /** Bytes currently held — for tests and the Measure panel's honesty, not for decisions. */
    val held: Long @Synchronized get() = heldBytes

    /**
     * The picture for [name], or null — in which case it is now wanted, unless a decode already has
     * it or found nothing for it.
     */
    @Synchronized
    fun get(name: String): T? {
        onPage += name
        val entry = entries[name]
        if (entry != null) {
            if (isUsable(entry.picture)) return entry.picture
            // Recycled under us. Drop it and decode again rather than draw nothing for ever.
            entries.remove(name)
            heldBytes -= entry.bytes
        }
        if (name !in absent && name !in inFlight) wanted += name
        return null
    }

    /** True when [name] is wanted or being decoded — a null from [get] that will not last. */
    @Synchronized
    fun isPending(name: String): Boolean = name in wanted || name in inFlight

    /**
     * A new page is on screen, and it draws [names].
     *
     * The previous page's pictures stay held — going back is common — but stop being protected from
     * [trim]. [names] are wanted at once rather than when the first frame asks.
     */
    @Synchronized
    fun beginPage(names: Collection<String>) {
        onPage.clear()
        for (name in names) {
            onPage += name
            if (name !in entries && name !in absent && name !in inFlight) wanted += name
        }
    }

    @Synchronized
    fun hasWanted(): Boolean = wanted.isNotEmpty()

    /** Everything wanted, now in flight. The caller must report each one to [decoded]. */
    @Synchronized
    fun takeWanted(): Set<String> {
        if (wanted.isEmpty()) return emptySet()
        val batch = LinkedHashSet(wanted)
        wanted.clear()
        inFlight += batch
        return batch
    }

    /**
     * The downsample to decode a batch at — 1, 2, 4 or [MAX_SAMPLE] — given each picture's size at
     * full resolution.
     *
     * The smallest that lets this page's pictures — those already held at whatever size they were
     * decoded at, plus this batch — fit [budgetBytes]. Pictures from other pages do not count: they
     * are what [trim] drops to make the room.
     */
    @Synchronized
    fun sampleSizeFor(fullBytes: Collection<Long>): Int {
        val heldOnPage = entries.entries.sumOf { (name, entry) -> if (name in onPage) entry.bytes else 0L }
        val batch = fullBytes.sum()
        var sample = 1
        while (sample < MAX_SAMPLE && heldOnPage + batch / (sample.toLong() * sample) > budgetBytes) sample *= 2
        return sample
    }

    /** A decode's answer for [name]: the picture, or null for *"no bytes, or not decodable"*. */
    @Synchronized
    fun decoded(name: String, picture: T?) {
        inFlight -= name
        if (picture == null) {
            absent += name
            return
        }
        entries.remove(name)?.let { heldBytes -= it.bytes }
        val bytes = sizeOf(picture)
        entries[name] = Entry(picture, bytes)
        heldBytes += bytes
        trim()
    }

    /**
     * Ask again for every picture a decode found nothing for — once per revision of the note, which
     * is when an image's bytes can have arrived.
     *
     * @return true when anything is wanted again, so the caller knows to start a decode.
     */
    @Synchronized
    fun retryAbsent(): Boolean {
        if (absent.isEmpty()) return false
        var any = false
        for (name in absent) {
            if (name in onPage) {
                wanted += name
                any = true
            }
        }
        absent.clear()
        return any
    }

    /**
     * Drop least-recently-drawn pictures that are **not** on this page until the budget holds.
     *
     * ⚠️ Stops rather than drop one this page draws. Over budget with only on-page pictures left is
     * a state [sampleSizeFor] makes rare and this tolerates: holding too much for one page is a
     * memory cost; evicting a picture on screen is a decode on every frame.
     */
    private fun trim() {
        if (heldBytes <= budgetBytes) return
        val iterator = entries.entries.iterator()
        while (heldBytes > budgetBytes && iterator.hasNext()) {
            val (name, entry) = iterator.next()
            if (name in onPage) continue
            iterator.remove()
            heldBytes -= entry.bytes
        }
    }

    internal companion object {
        /** Beyond a 16× downsample a 2,048-pixel picture is 128 pixels wide; past that, it is a smudge. */
        const val MAX_SAMPLE = 16

        private const val INITIAL_CAPACITY = 16
        private const val LOAD_FACTOR = 0.75f
    }
}
