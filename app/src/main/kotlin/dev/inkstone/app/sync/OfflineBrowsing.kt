package dev.inkstone.app.sync

import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

/**
 * What the folder browser says when it is drawing folder names it remembers rather than folder
 * names it has just been told.
 *
 * ⭐ A pure function over plain data, deliberately, for the reason CLAUDE.md gives: the screen it
 * belongs to cannot be constructed in a JVM test — `SyncViewModel` needs Hilt, a database and a
 * transport — so the *decision* is hoisted somewhere a test can reach it, and the composable is
 * left with nothing but a `Text`.
 *
 * The distinction the three cases exist for: **"there are no folders in here" and "I have never
 * looked in here" are different sentences**, and a picker that draws them the same way tells a
 * user their Nextcloud is empty. That is the alarming direction, and it is the one an offline
 * browser falls into by default.
 */
internal object OfflineBrowsing {

    /**
     * @param listedAtEpochMillis when this folder was last listed successfully. 0 means never.
     * @param folderCount how many folders are about to be drawn — including any the reconciler
     *   has walked, which is why a listing that has never happened can still have folders in it.
     */
    fun notice(listedAtEpochMillis: Long, folderCount: Int, nowEpochMillis: Long): String = when {
        listedAtEpochMillis > 0L ->
            "Can't reach the server — these are the folders Inkstone saw here " +
                "${ago(listedAtEpochMillis, nowEpochMillis)}. Choosing one still works: the note " +
                "uploads when you are back online."

        folderCount > 0 ->
            "Can't reach the server. These are the folders your notes already sync to. " +
                "Choosing one still works: the note uploads when you are back online."

        else ->
            "Can't reach the server, and Inkstone has never listed this folder. You can still " +
                "save here, or make a new folder — it is created when you are back online."
    }

    /**
     * How long ago, in words.
     *
     * Coarse on purpose, and no calendar: this is the difference between *"a minute ago"* and
     * *"before the holidays"*, which is all a user needs in order to decide whether to trust a
     * folder list. Anything finer would need a date format, a locale and a time zone to say
     * something nobody reads off this line.
     *
     * ⚠️ A negative age is clamped to "just now" rather than rendered. A device whose clock
     * moved backwards would otherwise be told its folder list was seen *in the future*, and the
     * repo's rule about wall clocks (they disagree; never order anything by them) applies to
     * what is *shown* as much as to what is decided.
     */
    fun ago(thenEpochMillis: Long, nowEpochMillis: Long): String {
        val age = (nowEpochMillis - thenEpochMillis).coerceAtLeast(0L).milliseconds
        return when {
            age < 1.minutes -> "just now"
            age < 1.hours -> plural(age.inWholeMinutes, "minute")
            age < 1.days -> plural(age.inWholeHours, "hour")
            age < 30.days -> plural(age.inWholeDays, "day")
            else -> "over a month ago"
        }
    }

    private fun plural(count: Long, unit: String) = "$count $unit${if (count == 1L) "" else "s"} ago"
}
