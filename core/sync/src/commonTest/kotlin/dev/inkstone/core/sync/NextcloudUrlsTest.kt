package dev.inkstone.core.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** SPEC §7.3's base path, and SPEC §0.1 #3's uid. */
class NextcloudUrlsTest {

    @Test
    fun `the dav base uses the uid`() {
        // SPEC §7.3 — "<server>/remote.php/dav/files/<uid>/. No v2 exists. Don't use
        // legacy /remote.php/webdav."
        assertEquals(
            "https://cloud.example.com/remote.php/dav/files/noah/",
            NextcloudUrls.davBase("https://cloud.example.com", "noah"),
        )
        assertFalse("webdav" in NextcloudUrls.davBase("https://c", "u"))
    }

    @Test
    fun `a uid that needs encoding is encoded`() {
        // A uid is usually a plain username, but Nextcloud does not require it to be, and
        // an unencoded one would silently change the path.
        assertTrue("noah%20b" in NextcloudUrls.davBase("https://cloud.example.com", "noah b"))
    }

    @Test
    fun `each path segment is encoded separately`() {
        // Separately, so a `#` in a folder name becomes part of the path rather than a
        // fragment — and so a `/` the caller left in a segment stays visible as an extra
        // level instead of being silently encoded away.
        assertEquals("Notes/Term%20one/a%23b.pdf", NextcloudUrls.encodePath("Notes/Term one/a#b.pdf"))
        assertEquals("a/b", NextcloudUrls.encodePath("/a//b/"))
    }

    @Test
    fun `an href round-trips back to a path`() {
        assertEquals(
            "Inkstone/Lecture one.pdf",
            NextcloudUrls.pathFromHref("/remote.php/dav/files/noah/Inkstone/Lecture%20one.pdf", "noah"),
        )
        assertEquals(
            "Inkstone",
            NextcloudUrls.pathFromHref("https://cloud.example.com/remote.php/dav/files/noah/Inkstone/", "noah"),
        )
        assertEquals("", NextcloudUrls.pathFromHref("/remote.php/dav/files/noah/", "noah"))
    }

    @Test
    fun `an href for another user is not mistaken for one of ours`() {
        assertNull(NextcloudUrls.pathFromHref("/remote.php/dav/files/someoneelse/x.pdf", "noah"))
    }

    @Test
    fun `a literal plus in a filename is not decoded to a space`() {
        // `+` → space is *form* encoding. A plus is a legal Nextcloud filename character,
        // so decoding it would rename the user's file.
        assertEquals("C++ notes.pdf", NextcloudUrls.decodePath("C++%20notes.pdf"))
    }

    @Test
    fun `percent decoding is UTF-8 aware`() {
        assertEquals("Übung", NextcloudUrls.decodePath("%C3%9Cbung"))
        assertEquals("😀", NextcloudUrls.decodePath("%F0%9F%98%80"))
    }

    @Test
    fun `a server url is normalised without being silently upgraded`() {
        assertEquals("https://cloud.example.com", NextcloudUrls.normaliseServer(" cloud.example.com/ "))
        assertEquals("https://cloud.example.com", NextcloudUrls.normaliseServer("https://cloud.example.com/"))
        // Kept as http so [isSecure] can refuse it with an explanation, which is more
        // useful than an upgrade that then fails to connect (SPEC §14).
        assertEquals("http://cloud.example.com", NextcloudUrls.normaliseServer("http://cloud.example.com"))
        assertFalse(NextcloudUrls.isSecure("http://cloud.example.com"))
        assertTrue(NextcloudUrls.isSecure("https://cloud.example.com"))
    }

    @Test
    fun `a server in a subdirectory joins correctly`() {
        assertEquals(
            "https://example.com/nextcloud/ocs/v2.php/cloud/user",
            NextcloudUrls.ocsUser("https://example.com/nextcloud"),
        )
    }
}
