package dev.inkstone.app.sync.cloud

import java.io.File
import kotlin.test.Test

/**
 * Not a test: a way to run [GraphStandIn] on the developer's machine for as long as an emulator walk
 * needs it (docs/CLOUD-PROVIDERS.md §P4). The emulator reaches the host at `10.0.2.2`.
 *
 * Does nothing unless `INKSTONE_HOLD_STANDIN` is set, so `check` and CI pass straight through it:
 *
 * ```
 * INKSTONE_HOLD_STANDIN=30 INKSTONE_STANDIN_LOG=<file> gradlew :app:testDebugUnitTest --tests "*GraphStandInHost"
 * ```
 *
 * `INKSTONE_HOLD_STANDIN` is minutes to stay up. The server log is rewritten to `INKSTONE_STANDIN_LOG`
 * every half second, so what the app asked the "server" is readable while the walk is in progress.
 * The debug APK must be built with `-Pinkstone.msClientId=standin-client
 * -Pinkstone.msStandIn=http://10.0.2.2:8099`.
 */
class GraphStandInHost {

    @Test
    fun hold() {
        val minutes = System.getenv("INKSTONE_HOLD_STANDIN")?.toLongOrNull() ?: return
        val logFile = System.getenv("INKSTONE_STANDIN_LOG")?.let(::File)
        val server = GraphStandIn(port = PORT, publicHost = "10.0.2.2")
        try {
            server.seedFile("Inkstone/Physics/Notes.pdf", "%PDF-1.4 stand-in".encodeToByteArray())
            server.seedFile("Inkstone/Chemistry/Lab.pdf", "%PDF-1.4 stand-in".encodeToByteArray())
            val until = System.currentTimeMillis() + minutes * MILLIS_PER_MINUTE
            while (System.currentTimeMillis() < until) {
                logFile?.writeText(snapshot(server))
                Thread.sleep(HALF_SECOND)
            }
        } finally {
            server.close()
        }
    }

    private fun snapshot(server: GraphStandIn): String = buildString {
        appendLine("authorize requests: ${server.authorizeRequests.size}")
        server.authorizeRequests.forEach { appendLine("  $it") }
        appendLine("token endpoint hits: ${server.tokenEndpoint.count}, api hits: ${server.api.count}")
        synchronized(server.log) { server.log.forEach { appendLine(it) } }
    }

    private companion object {
        const val PORT = 8099
        const val MILLIS_PER_MINUTE = 60_000L
        const val HALF_SECOND = 500L
    }
}
