package dev.inkstone.core.sync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** SPEC §7.8 and §7.9 — capabilities, and the filename rules read from them. */
class NextcloudCapabilitiesTest {

    private fun parse(json: String) = NextcloudCapabilities.parse(Json.parseToJsonElement(json) as JsonObject)

    @Test
    fun `a realistic capabilities document parses`() {
        val capabilities = parse(
            """
            {
              "version": { "string": "31.0.2", "major": 31 },
              "capabilities": {
                "core": { "pollinterval": 120, "webdav-root": "remote.php/webdav" },
                "bruteforce": { "delay": 0 },
                "files": {
                  "forbidden_filenames": [".htaccess"],
                  "forbidden_filename_basenames": ["con", "aux"],
                  "forbidden_filename_characters": ["\\", "/"],
                  "forbidden_filename_extensions": [".part", ".filepart"]
                }
              }
            }
            """.trimIndent(),
        )

        assertEquals("31.0.2", capabilities.serverVersion)
        assertEquals(120L, capabilities.pollIntervalSeconds)
        assertFalse(capabilities.alreadyThrottled)
        assertFalse(capabilities.pushAvailable)
        assertTrue("con" in capabilities.files.forbiddenBasenames)
        assertTrue('\\' in capabilities.files.forbiddenCharacters)
    }

    @Test
    fun `an absent poll interval falls back to sixty seconds`() {
        // SPEC §7.6 — "honour capabilities.core.pollinterval (default 60 s) as the floor".
        assertEquals(
            NextcloudCapabilities.DEFAULT_POLL_INTERVAL_SECONDS,
            parse("""{"capabilities":{}}""").pollIntervalSeconds,
        )
    }

    @Test
    fun `a nonzero bruteforce delay means already throttled`() {
        // SPEC §7.2 — not an error, a reason to back off before making it worse.
        assertTrue(parse("""{"capabilities":{"bruteforce":{"delay":25}}}""").alreadyThrottled)
    }

    @Test
    fun `notify_push is detected when present and absent by default`() {
        // SPEC §7.6 — "assume notify_push is absent". A default install does not have it.
        assertFalse(parse("""{"capabilities":{}}""").pushAvailable)
        assertTrue(
            parse("""{"capabilities":{"notify_push":{"endpoints":{"websocket":"wss://x/push"}}}}""").pushAvailable,
        )
    }

    @Test
    fun `an unknown document shape yields defaults rather than an exception`() {
        // A capabilities document that has moved a field must not stop the user logging in.
        val capabilities = parse("""{"something":"else"}""")

        assertNull(capabilities.serverVersion)
        assertEquals(NextcloudCapabilities.DEFAULT_POLL_INTERVAL_SECONDS, capabilities.pollIntervalSeconds)
        assertTrue(capabilities.files.forbiddenFilenames.isEmpty())
    }

    @Test
    fun `the server's rules can only forbid more than the static ones`() {
        // SPEC §7.8 — the static half (RemoteName) already applies the stricter Windows
        // set, so this catches the fussier instance rather than relaxing anything.
        val rules = NextcloudCapabilities.FileNameRules(
            forbiddenBasenames = setOf("readme"),
            forbiddenCharacters = setOf('$'),
            forbiddenExtensions = setOf(".bak"),
        )

        // ⚠️ Nextcloud returns these lowercased; the *comparison* is case-insensitive but
        // the user's capitalisation is kept. Lowercasing the name here would rename the
        // file every time the rules were applied.
        assertEquals("README_", rules.sanitise("README"), "server basenames compare case-insensitively")
        assertEquals("a_b", rules.sanitise("a\$b"))
        assertEquals("notes", rules.sanitise("notes.bak"))
        // Still stricter than a default Nextcloud, which allows a colon.
        assertEquals("Notes_ 2026", rules.sanitise("Notes: 2026"))
    }

    @Test
    fun `sanitising through the server's rules is stable`() {
        // A remote path is recomputed on every sync (SPEC §7.4); a sanitiser that drifted
        // would rename the user's file a little more each time.
        val rules = NextcloudCapabilities.FileNameRules(
            forbiddenBasenames = setOf("con"),
            forbiddenCharacters = setOf('%'),
            forbiddenExtensions = setOf(".bak"),
        )
        for (name in listOf("CON", "a%b", "x.bak", "Notes. ", "", "x".repeat(400))) {
            val once = rules.sanitise(name)
            assertEquals(once, rules.sanitise(once), "not stable for “$name”")
        }
    }

    @Test
    fun `capabilities survive a round trip through the account row`() {
        val original = parse(
            """
            {
              "version": {"string":"31.0.2"},
              "capabilities": {
                "core": {"pollinterval": 90},
                "notify_push": {"endpoints": {"websocket": "wss://x"}},
                "files": {"forbidden_filename_characters": ["\\", "/", ":"]}
              }
            }
            """.trimIndent(),
        )

        val restored = NextcloudCapabilities.decode(NextcloudCapabilities.encode(original))

        assertEquals(original, restored)
    }
}

/**
 * RFC 1321's own test suite (§A.5).
 *
 * MD5 is here for **one header** — SPEC §7.5's `OC-Checksum` — and Nextcloud *verifies*
 * it, so a wrong implementation turns every upload into a 400 rather than failing
 * quietly. The published vectors are the only way to know it is right.
 */
class Md5Test {

    @Test
    fun `the RFC 1321 test suite passes`() {
        val vectors = listOf(
            "" to "d41d8cd98f00b204e9800998ecf8427e",
            "a" to "0cc175b9c0f1b6a831c399e269772661",
            "abc" to "900150983cd24fb0d6963f7d28e17f72",
            "message digest" to "f96b697d7cb7938d525a2f31aaf161d0",
            "abcdefghijklmnopqrstuvwxyz" to "c3fcd3d76192e4007dfb496cca67e13b",
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789" to "d174ab98d277d9f5a5611c2c9f419d9f",
            ("1234567890".repeat(8)) to "57edf4a22be3c955ac49da2e2107b67a",
        )

        for ((input, expected) in vectors) {
            assertEquals(expected, Md5.hex(input.encodeToByteArray()), "MD5 of “$input”")
        }
    }

    @Test
    fun `a message that lands exactly on a block boundary is padded correctly`() {
        // 55, 56 and 64 bytes are where a hand-written padding routine goes wrong: 56 is
        // the length at which the length field no longer fits in the current block.
        for (length in listOf(55, 56, 63, 64, 65)) {
            val digest = Md5.hex(ByteArray(length) { 'x'.code.toByte() })
            assertEquals(32, digest.length, "length $length produced a malformed digest")
        }
        // Values from a reference implementation, at the two lengths a hand-written pad
        // gets wrong: 56 is where the 8-byte length field no longer fits in the block.
        assertEquals("04364420e25c512fd958a70738aa8f72", Md5.hex(ByteArray(55) { 'x'.code.toByte() }))
        assertEquals("668a72d5ba17f08e62dabcafad6db14b", Md5.hex(ByteArray(56) { 'x'.code.toByte() }))
        assertEquals("c1bb4f81d892b2d57947682aeb252456", Md5.hex(ByteArray(64) { 'x'.code.toByte() }))
    }
}

/** The hand-rolled XML reader (SPEC §0.1 #4 — no JVM-only parser may live in `:core:sync`). */
class XmlReaderTest {

    @Test
    fun `namespaces are inherited and resolved per element`() {
        val root = XmlReader.parse(
            """<a:root xmlns:a="urn:A"><a:child><b:leaf xmlns:b="urn:B">v</b:leaf></a:child></a:root>""",
        )

        assertEquals("urn:A", root.namespace)
        assertEquals("root", root.localName)
        val child = root.child("urn:A", "child")!!
        assertEquals("v", child.textOf("urn:B", "leaf"))
        assertNull(child.textOf("urn:A", "leaf"), "a prefix match must not stand in for a namespace match")
    }

    @Test
    fun `a default namespace applies to unprefixed elements`() {
        val root = XmlReader.parse("""<multistatus xmlns="DAV:"><response/></multistatus>""")

        assertEquals("DAV:", root.namespace)
        assertTrue(root.has("DAV:", "response"))
    }

    @Test
    fun `entities and character references and CDATA are decoded`() {
        val root = XmlReader.parse(
            """<r><a>a &amp; b &lt;c&gt;</a><b>&#65;&#x42;</b><c><![CDATA[<not markup> & co]]></c></r>""",
        )

        assertEquals("a & b <c>", root.textOf("", "a"))
        assertEquals("AB", root.textOf("", "b"))
        assertEquals("<not markup> & co", root.textOf("", "c"))
    }

    @Test
    fun `an unknown entity is left alone rather than resolved`() {
        // This parser implements no DTD, so there is no way to define one — which is what
        // makes an entity-expansion attack against it impossible rather than unlikely.
        assertEquals("a &nbsp; b", XmlReader.parse("<r>a &nbsp; b</r>").text)
    }

    @Test
    fun `a supplementary character reference becomes a surrogate pair`() {
        assertEquals("😀", XmlReader.parse("<r>&#x1F600;</r>").text)
    }

    @Test
    fun `declarations and comments and processing instructions are skipped`() {
        val root = XmlReader.parse(
            """<?xml version="1.0"?><!-- hello --><?pi data?><r><!-- inner --><a/></r>""",
        )

        assertEquals("r", root.localName)
        assertEquals(1, root.children.size)
    }

    @Test
    fun `a mismatched closing tag is reported rather than accepted`() {
        val failure = runCatching { XmlReader.parse("<a><b></a></b>") }.exceptionOrNull()

        assertTrue(failure is XmlParseException)
    }

    @Test
    fun `escaping round-trips`() {
        val awkward = """a & b < c > d " e ' f"""
        assertEquals(awkward, XmlReader.unescape(XmlReader.escape(awkward)))
    }
}
