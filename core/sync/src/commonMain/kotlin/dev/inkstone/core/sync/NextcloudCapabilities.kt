package dev.inkstone.core.sync

import dev.inkstone.core.model.RemoteName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/**
 * SPEC §7.9 — `GET /ocs/v2.php/cloud/capabilities`, once at login and again on a
 * server version change. Cached.
 *
 * Only the fields this app acts on are kept. Everything is optional and everything has
 * a defensible default, because the alternative is an app that refuses to talk to a
 * server whose administrator disabled an app it has never heard of.
 *
 * @property serverVersion `ocs.data.version.string`. The cache key: refetch when it changes.
 * @property pollIntervalSeconds SPEC §7.6 — *"honour `capabilities.core.pollinterval`
 *   (default 60 s) **as the floor**"*. A client that polls faster than the server asks is
 *   a client the administrator will eventually block.
 * @property bruteforceDelaySeconds SPEC §7.2 — *"non-zero means you're already
 *   throttled"*. Not an error; a reason to back off before making it worse.
 * @property pushWebsocketUrl SPEC §7.6 — `notify_push` is **not** bundled and **not**
 *   commonly available (separate app + Rust daemon + Redis + reverse proxy). Present
 *   means push is an available *optimisation*; absent is the expected case and changes
 *   nothing, because push never replaces polling.
 * @property files the §7.8 filename rules, read at runtime rather than assumed.
 */
public data class NextcloudCapabilities(
    val serverVersion: String? = null,
    val pollIntervalSeconds: Long = DEFAULT_POLL_INTERVAL_SECONDS,
    val bruteforceDelaySeconds: Long = 0L,
    val pushWebsocketUrl: String? = null,
    val files: FileNameRules = FileNameRules(),
) {

    /** SPEC §7.2 — a non-zero delay means this client is already in the penalty box. */
    public val alreadyThrottled: Boolean get() = bruteforceDelaySeconds > 0L

    public val pushAvailable: Boolean get() = !pushWebsocketUrl.isNullOrEmpty()

    /**
     * SPEC §7.8's runtime half.
     *
     * The static half is [RemoteName], which already applies the *stricter* Windows rules
     * SPEC §7.8 asks for. These lists can therefore only ever forbid **more**, and this
     * type's job is to catch the instance that has been configured to be fussier — not to
     * relax anything.
     *
     * ⚠️ Nextcloud returns all four **lowercased**, so every comparison here is
     * case-insensitive. Comparing a returned `"con"` against a user's `"CON"` with `==`
     * is how a rule that is being read at runtime ends up never matching.
     */
    @Serializable
    public data class FileNameRules(
        val forbiddenFilenames: Set<String> = emptySet(),
        val forbiddenBasenames: Set<String> = emptySet(),
        val forbiddenCharacters: Set<Char> = emptySet(),
        val forbiddenExtensions: Set<String> = emptySet(),
    ) {

        /**
         * A legal remote segment for [name] on **this** server.
         *
         * [RemoteName.sanitise] first — it does the byte-limit, control-character,
         * reserved-basename and trailing-dot work, and it is what the user already saw
         * when they named the folder (see `LibraryRepository.legalName`). Then the
         * server's extra rules on top.
         *
         * Applied in this order so the result is still stable: sanitising an
         * already-sanitised name returns it unchanged, and so does this.
         */
        public fun sanitise(name: String): String {
            var result = RemoteName.sanitise(name)
            if (forbiddenCharacters.isNotEmpty()) {
                result = result.map { if (it in forbiddenCharacters) '_' else it }.joinToString("")
            }
            for (extension in forbiddenExtensions) {
                val dotted = if (extension.startsWith('.')) extension else ".$extension"
                while (result.length > dotted.length && result.endsWith(dotted, ignoreCase = true)) {
                    result = result.dropLast(dotted.length)
                }
            }
            val lower = result.lowercase()
            val stem = lower.substringBefore('.')
            if (lower in forbiddenFilenames || stem in forbiddenBasenames) result += "_"
            // The server's rules can reintroduce what RemoteName removed — stripping a
            // forbidden extension can expose a trailing dot — so it runs again. It is
            // idempotent, so this costs nothing and closes the gap.
            return RemoteName.sanitise(result)
        }

        public fun isLegal(name: String): Boolean = name == sanitise(name)
    }

    public companion object {

        /** SPEC §7.6 — the documented default when the server does not say. */
        public const val DEFAULT_POLL_INTERVAL_SECONDS: Long = 60L

        /**
         * Parse `ocs.data`, which is `{ "version": {...}, "capabilities": {...} }`.
         *
         * Every lookup is null-tolerant. A capabilities document that has moved a field
         * gives a default here, not an exception — and a default that is wrong costs a
         * slightly-too-eager poll, whereas an exception costs the account.
         */
        public fun parse(data: JsonObject): NextcloudCapabilities {
            val capabilities = data["capabilities"] as? JsonObject
            val core = capabilities?.get("core") as? JsonObject
            val filesNode = capabilities?.get("files") as? JsonObject
            val bruteforce = capabilities?.get("bruteforce") as? JsonObject
            val push = capabilities?.get("notify_push") as? JsonObject

            return NextcloudCapabilities(
                serverVersion = (data["version"] as? JsonObject)?.get("string")?.textOrNull(),
                pollIntervalSeconds = (core?.get("pollinterval") as? JsonPrimitive)?.longOrNull
                    ?.takeIf { it > 0 } ?: DEFAULT_POLL_INTERVAL_SECONDS,
                bruteforceDelaySeconds = (bruteforce?.get("delay") as? JsonPrimitive)?.longOrNull ?: 0L,
                pushWebsocketUrl = (push?.get("endpoints") as? JsonObject)?.get("websocket")?.textOrNull(),
                files = FileNameRules(
                    forbiddenFilenames = filesNode.stringSet("forbidden_filenames"),
                    forbiddenBasenames = filesNode.stringSet("forbidden_filename_basenames"),
                    forbiddenCharacters = filesNode.stringSet("forbidden_filename_characters")
                        .mapNotNull { it.firstOrNull() }
                        .toSet(),
                    forbiddenExtensions = filesNode.stringSet("forbidden_filename_extensions"),
                ),
            )
        }

        /** Cached capabilities, as stored beside the account. */
        public fun decode(stored: String): NextcloudCapabilities? = runCatching {
            json.decodeFromString<Stored>(stored).toCapabilities()
        }.getOrNull()

        public fun encode(capabilities: NextcloudCapabilities): String = json.encodeToString(Stored.of(capabilities))

        private fun JsonObject?.stringSet(key: String): Set<String> = (this?.get(key) as? JsonArray)
            ?.mapNotNull { it.textOrNull()?.lowercase() }
            ?.toSet()
            .orEmpty()

        private val json = Json { ignoreUnknownKeys = true }
    }

    /**
     * The on-disk form.
     *
     * A separate type because [FileNameRules] holds a `Set<Char>`, which has no stable
     * JSON representation worth relying on across serialization versions — a string is
     * exactly as expressive and reads the same as what the server sent.
     */
    @Serializable
    internal data class Stored(
        val serverVersion: String? = null,
        val pollIntervalSeconds: Long = DEFAULT_POLL_INTERVAL_SECONDS,
        val bruteforceDelaySeconds: Long = 0L,
        val pushWebsocketUrl: String? = null,
        val forbiddenFilenames: List<String> = emptyList(),
        val forbiddenBasenames: List<String> = emptyList(),
        val forbiddenCharacters: String = "",
        val forbiddenExtensions: List<String> = emptyList(),
    ) {
        fun toCapabilities() = NextcloudCapabilities(
            serverVersion = serverVersion,
            pollIntervalSeconds = pollIntervalSeconds,
            bruteforceDelaySeconds = bruteforceDelaySeconds,
            pushWebsocketUrl = pushWebsocketUrl,
            files = FileNameRules(
                forbiddenFilenames = forbiddenFilenames.toSet(),
                forbiddenBasenames = forbiddenBasenames.toSet(),
                forbiddenCharacters = forbiddenCharacters.toSet(),
                forbiddenExtensions = forbiddenExtensions.toSet(),
            ),
        )

        companion object {
            fun of(capabilities: NextcloudCapabilities) = Stored(
                serverVersion = capabilities.serverVersion,
                pollIntervalSeconds = capabilities.pollIntervalSeconds,
                bruteforceDelaySeconds = capabilities.bruteforceDelaySeconds,
                pushWebsocketUrl = capabilities.pushWebsocketUrl,
                forbiddenFilenames = capabilities.files.forbiddenFilenames.toList(),
                forbiddenBasenames = capabilities.files.forbiddenBasenames.toList(),
                forbiddenCharacters = capabilities.files.forbiddenCharacters.joinToString(""),
                forbiddenExtensions = capabilities.files.forbiddenExtensions.toList(),
            )
        }
    }
}
