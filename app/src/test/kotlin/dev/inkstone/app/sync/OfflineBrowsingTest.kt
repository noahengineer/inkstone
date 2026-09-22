package dev.inkstone.app.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What the folder browser says when it is offline.
 *
 * The reason this is a test at all: **"there are no folders in here" and "I have never looked in
 * here" are different sentences**, and the offline picker falls into saying the first when it
 * means the second — which tells a user their Nextcloud is empty. That is the alarming direction,
 * and it is the one nothing else in the suite can catch, because it is a string.
 */
class OfflineBrowsingTest {

    @Test
    fun `a folder listed before is described by when it was listed`() {
        val notice = OfflineBrowsing.notice(
            listedAtEpochMillis = NOW - 3 * HOUR,
            folderCount = 4,
            nowEpochMillis = NOW,
        )
        assertTrue(notice.contains("3 hours ago"), notice)
        assertTrue(notice.contains("uploads when you are back online"), "and that the choice still works: $notice")
    }

    @Test
    fun `a folder never listed does not claim to be empty`() {
        val notice = OfflineBrowsing.notice(listedAtEpochMillis = 0L, folderCount = 0, nowEpochMillis = NOW)
        assertTrue(notice.contains("never listed"), notice)
        assertTrue(notice.contains("save here"), "there is still something to do here: $notice")
    }

    /**
     * The middle case exists because [SqlBrowsedFolderStore] shows folders the *reconciler* has
     * walked even where the browser has never been. Saying "never listed" over a list of four
     * folders would be a contradiction on screen.
     */
    @Test
    fun `folders known from syncing are not described as never listed`() {
        val notice = OfflineBrowsing.notice(listedAtEpochMillis = 0L, folderCount = 3, nowEpochMillis = NOW)
        assertTrue(notice.contains("already sync to"), notice)
        assertTrue(!notice.contains("never listed"), notice)
    }

    @Test
    fun `ages read as words`() {
        assertEquals("just now", OfflineBrowsing.ago(NOW - 20_000L, NOW))
        assertEquals("1 minute ago", OfflineBrowsing.ago(NOW - MINUTE, NOW))
        assertEquals("59 minutes ago", OfflineBrowsing.ago(NOW - 59 * MINUTE, NOW))
        assertEquals("1 hour ago", OfflineBrowsing.ago(NOW - HOUR, NOW))
        assertEquals("2 days ago", OfflineBrowsing.ago(NOW - 2 * DAY, NOW))
        assertEquals("over a month ago", OfflineBrowsing.ago(NOW - 40 * DAY, NOW))
    }

    /**
     * ⚠️ A device whose clock moved backwards must not be told its folder list was seen in the
     * future. Wall clocks disagree — this repo orders nothing by them, and that applies to what
     * is shown as much as to what is decided.
     */
    @Test
    fun `a timestamp in the future reads as just now`() {
        assertEquals("just now", OfflineBrowsing.ago(NOW + DAY, NOW))
    }

    private companion object {
        const val NOW = 1_700_000_000_000L
        const val MINUTE = 60_000L
        const val HOUR = 60 * MINUTE
        const val DAY = 24 * HOUR
    }
}
