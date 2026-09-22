package com.wasimaster.wmkeyboard.netlog

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Keeps the network activity log's promise, "every request the keyboard's own
 * code makes", true after this commit.
 *
 * Any source file that opens a connection — an `HttpURLConnection`, an OkHttp
 * client, a socket, an image loader — must also route it through the log:
 * `NetLog`, `NetLogInterceptor`, or KDE Connect's `KdeTrafficMeter`. A new
 * client that opens its own connection and forgets fails here, which is far
 * cheaper than a user noticing a request the screen never showed them.
 *
 * [EXEMPT] is the short list of files that open connections the log records
 * elsewhere, each with the reason. Keep the reasons honest: the docs page
 * repeats them.
 */
class NetworkCallSitesTest {

    private val opensConnection = Regex(
        """openConnection\(|OkHttpClient\.Builder\(|OkHttpClient\(\)|\bSocket\(\)|\bSocket\(|""" +
            """ServerSocket\(|DatagramSocket\(|MulticastSocket\(|ImageLoader\.Builder\(|createSocket\(""",
    )

    private val recordsIt = Regex("""\bNetLog\b|NetLogInterceptor|KdeTrafficMeter|KdeTrafficTap""")

    @Test
    fun everyConnectionIsLogged() {
        val root = listOf(File(".."), File("."))
            .firstOrNull { File(it, "settings.gradle.kts").isFile }
            ?: error("cannot find the repository root from ${File("").absolutePath}")
        val sources = listOf("app", "core", "feature").flatMap { top ->
            File(root, top).walkTopDown()
                .onEnter { dir -> dir.name != "build" && dir.name != "test" && dir.name != "androidTest" }
                .filter { it.isFile && it.extension == "kt" }
                .toList()
        }
        assertTrue("found no sources under $root", sources.size > 100)

        val offenders = sources.mapNotNull { file ->
            val path = file.relativeTo(root).invariantSeparatorsPath
            val text = file.readText()
            when {
                !opensConnection.containsMatchIn(text) -> null
                recordsIt.containsMatchIn(text) -> null
                EXEMPT.keys.any { path.endsWith(it) } -> null
                else -> path
            }
        }
        assertTrue(
            "These files open network connections without going through the network activity log " +
                "(NetLog, NetLogInterceptor or KdeTrafficMeter):\n" + offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }

    @Test
    fun exemptionsStillExist() {
        val root = listOf(File(".."), File("."))
            .first { File(it, "settings.gradle.kts").isFile }
        val all = listOf("app", "core", "feature").flatMap { top ->
            File(root, top).walkTopDown().filter { it.isFile && it.extension == "kt" }
                .map { it.relativeTo(root).invariantSeparatorsPath }.toList()
        }
        val stale = EXEMPT.keys.filter { key -> all.none { it.endsWith(key) } }
        assertTrue("Exempt files that no longer exist, drop them: $stale", stale.isEmpty())
    }

    private companion object {
        val EXEMPT = mapOf(
            // Accepts and dials the control connections; each becomes a KdeLink,
            // which reports itself through the traffic meter. Also sends the
            // small UDP identity announcements KDE Connect discovery is made of,
            // which carry no user data and are not logged.
            "core/kdeconnect/LanTransport.kt" to "links counted in Link.kt; UDP discovery",
            // Wraps sockets already opened elsewhere in TLS; opens nothing itself.
            "core/kdeconnect/Tls.kt" to "wraps existing sockets",
        ).mapKeys { (key, _) ->
            // Written short above; matched against the full source path.
            key.replace("core/kdeconnect/", "core/kdeconnect/src/main/java/com/wasimaster/wmkeyboard/core/kdeconnect/")
        }
    }
}
