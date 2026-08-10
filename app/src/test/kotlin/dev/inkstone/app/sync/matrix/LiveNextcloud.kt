package dev.inkstone.app.sync.matrix

import dev.inkstone.core.model.NextcloudCredential
import java.io.File
import java.util.Properties

/*
 * How SPEC §13.2's matrix finds a real Nextcloud, and what it says when there isn't one.
 *
 * SPEC §13 is unambiguous: the merge matrix runs against a real Nextcloud in Docker, "not a
 * mock", because mocks do not reproduce weak ETags, fileid churn on trash-restore, or
 * brute-force lockout. This file is the honest half of that. Every row also carries whatever
 * coverage is possible without a server — see [MatrixServer] — but the claims that need the
 * real thing say so out loud, in the test output, naming what IS proven and what is NOT.
 *
 * A test that reports success without having run is worse than no test.
 *
 * Block comment, not KDoc: a file-level KDoc attaches to no declaration and ktlint rejects it.
 */

/**
 * A live Nextcloud the matrix may drive.
 *
 * @property occ the shell command that reaches `occ` on that instance, or null. Two rows cannot
 *   be produced by a healthy server on demand — a `nc:data-fingerprint` change (row 15) is what
 *   an admin restoring a backup does, and revoking an app password (row 20) is a security
 *   setting — so `tools/nextcloud-docker/up.sh` writes the command that can do both.
 */
internal class LiveNextcloud(val credential: NextcloudCredential, val occ: String?) {

    companion object {

        /**
         * Env vars first, then a gitignored properties file at the repo root.
         *
         * Env first because CI would supply it that way; the file second because a Gradle `Test`
         * task is not re-run when an env var changes, so a developer flipping between "with a
         * server" and "without" gets a stale result from the env path and a correct one from the
         * file. `up.sh` writes the file.
         */
        fun find(): LiveNextcloud? = fromEnvironment() ?: fromPropertiesFile()

        private fun fromEnvironment(): LiveNextcloud? = credentialOf(
            url = env("INKSTONE_NC_URL"),
            login = env("INKSTONE_NC_LOGIN"),
            // ⚠️ SPEC §0.1 #3 — the WebDAV path segment is `ocs.data.id`, NOT the login name.
            // Two variables, so a run against an instance where they differ (an email login)
            // exercises the difference instead of hiding it.
            uid = env("INKSTONE_NC_UID"),
            password = env("INKSTONE_NC_APP_PASSWORD"),
        )?.let { LiveNextcloud(it, env("INKSTONE_NC_OCC")) }

        private fun fromPropertiesFile(): LiveNextcloud? {
            val file = repoRootFile(PROPERTIES_NAME) ?: return null
            val properties = Properties()
            file.inputStream().use(properties::load)
            val credential = credentialOf(
                url = properties.value("url"),
                login = properties.value("login"),
                uid = properties.value("uid"),
                password = properties.value("appPassword"),
            ) ?: return null
            return LiveNextcloud(credential, properties.value("occ"))
        }

        /** Null unless **all four** are present: a partial configuration is not a server. */
        private fun credentialOf(url: String?, login: String?, uid: String?, password: String?): NextcloudCredential? {
            val complete = listOf(url, login, uid, password).filterNotNull()
            if (complete.size < FIELDS_REQUIRED) return null
            return NextcloudCredential(
                serverUrl = complete[0].trimEnd('/'),
                loginName = complete[1],
                uid = complete[2],
                appPassword = complete[3],
            )
        }

        /** url, login, uid, appPassword. */
        private const val FIELDS_REQUIRED = 4

        /**
         * Walk up from the working directory looking for [name].
         *
         * Gradle runs a unit test with the *module* directory as `user.dir`, and the properties
         * file is at the repo root beside `.gitignore`. Hard-coding `../` would break the moment
         * somebody ran the suite from an IDE run configuration.
         */
        private fun repoRootFile(name: String): File? {
            var directory: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
            while (directory != null) {
                val candidate = File(directory, name)
                if (candidate.isFile) return candidate
                directory = directory.parentFile
            }
            return null
        }

        private fun env(name: String): String? = System.getenv(name)?.takeIf { it.isNotBlank() }

        private fun Properties.value(key: String): String? = getProperty(key)?.trim()?.takeIf { it.isNotBlank() }

        const val PROPERTIES_NAME: String = "nextcloud-test.local.properties"
    }
}

/**
 * Whether *this* test task may drive the live server.
 *
 * ⚠️ `check` runs `testDebugUnitTest` **and** `testReleaseUnitTest`. Both would otherwise take
 * the same account, and — because the matrix writes to real remote paths — a row in one run
 * would see the other run's file appear under its note and report a conflict that nothing in
 * the app caused. In the milestone whose subject is conflicts, a false positive there costs a
 * day. So the live half runs under the debug task only; the release task still runs every
 * row's local half, which is where the semantics are.
 */
internal object MatrixVariant {

    private val variant: String = System.getProperty("inkstone.test.variant").orEmpty()

    /** True for `testDebugUnitTest`, and for an IDE run where the property is absent. */
    val maysDriveLiveServer: Boolean get() = !variant.contains("Release", ignoreCase = true)

    val describe: String get() = variant.ifEmpty { "(no variant property — assuming a single run)" }
}

/**
 * The loud skip. Copied from `PdfRoundTripSpikeTest`, which is the in-repo idiom.
 *
 * @param row which of SPEC §13.2's rows, so the output can be read against the table.
 * @param proven what the local half of this row establishes without a server. **Must be true.**
 * @param notProven what only a real Nextcloud can settle.
 */
internal fun announceNoLiveServer(row: String, proven: String, notProven: String) {
    println(
        """
        |
        |  SPEC 13.2 $row — LIVE HALF SKIPPED: no Nextcloud configured.
        |
        |  PROVEN anyway:  $proven
        |  NOT PROVEN:     $notProven
        |
        |  Start one with `bash tools/nextcloud-docker/up.sh`, or set
        |  INKSTONE_NC_URL / _LOGIN / _UID / _APP_PASSWORD, or write
        |  ${LiveNextcloud.PROPERTIES_NAME} at the repo root.
        |  Test task: ${MatrixVariant.describe}
        |
        """.trimMargin(),
    )
}

/** The same, for a row that needs a capability on top of a server. */
internal fun announceNeedsAdmin(row: String, needs: String, proven: String) {
    println(
        """
        |
        |  SPEC 13.2 $row — LIVE HALF SKIPPED: needs $needs.
        |
        |  PROVEN anyway:  $proven
        |  NOT PROVEN:     that a real Nextcloud behaves this way when $needs really happens.
        |
        """.trimMargin(),
    )
}
