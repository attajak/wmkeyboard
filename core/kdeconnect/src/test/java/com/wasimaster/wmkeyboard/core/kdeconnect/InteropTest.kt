package com.wasimaster.wmkeyboard.core.kdeconnect

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * The engine against a **real kdeconnect-kde daemon** on this machine.
 *
 * Skipped unless asked for:
 *
 * ```
 * ./gradlew :core:kdeconnect:testFullDebugUnitTest --tests "*InteropTest*" -Pkde.interop=1
 * ```
 *
 * It needs KDE Connect running locally with its `kdeconnect-cli` on the PATH as
 * `kdeconnect` (the Homebrew cask does both). The daemon ignores peers on the
 * loopback address, so the announcement goes to this machine's LAN address
 * instead; and since the daemon already owns UDP 1716, this side runs
 * announce-only — which is exactly the situation of a phone that also has the
 * KDE Connect app installed.
 *
 * The identity is kept in `-Pkde.interop.dir` (default: a folder under the
 * system temp dir) so a pairing made once is reused by later runs.
 */
class InteropTest {

    private val enabled = System.getProperty("kde.interop") == "1"

    /** `-Pkde.interop.tls=TLSv1.3,TLSv1.2` to try something other than the default. */
    private val tlsProtocols: List<String> =
        System.getProperty("kde.interop.tls").orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }
            .ifEmpty { KdeTls.DEFAULT_PROTOCOLS }

    private fun interopDir() =
        File(System.getProperty("kde.interop.dir").orEmpty().ifEmpty { System.getProperty("java.io.tmpdir") + "/wmkb-kde-interop" })
    private val events = CopyOnWriteArrayList<KdeEvent>()
    private val log = CopyOnWriteArrayList<String>()

    private fun cli(vararg args: String, timeoutSeconds: Long = 15): String {
        val process = ProcessBuilder(listOf("kdeconnect") + args).redirectErrorStream(true).start()
        val out = process.inputStream.bufferedReader().readText()
        process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        return out
    }

    private fun lanAddress(): String =
        NetworkInterface.getNetworkInterfaces().asSequence()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.asSequence() }
            .filterIsInstance<Inet4Address>()
            .first { it.isSiteLocalAddress }
            .hostAddress

    private fun await(what: String, timeoutMs: Long = 15_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) {
                throw AssertionError("timed out waiting for: $what\n--- engine log ---\n" + log.joinToString("\n"))
            }
            Thread.sleep(50)
        }
    }

    @Test
    fun `pair with the desktop and exercise every plugin it has`() {
        assumeTrue("interop tests need -Pkde.interop=1", enabled)
        val dir = interopDir()
        val inbox = File(dir, "received").apply { mkdirs() }
        val sink = object : KdeFileSink {
            override fun create(deviceName: String, fileName: String, size: Long): KdeIncomingFile {
                val target = File(inbox, fileName)
                val stream = FileOutputStream(target)
                return object : KdeIncomingFile {
                    override val stream: OutputStream = stream
                    override fun finish(success: Boolean): String? {
                        stream.close()
                        return if (success) target.absolutePath else null.also { target.delete() }
                    }
                }
            }
        }
        // An ephemeral UDP port: two sockets sharing 1716 on one machine would
        // split the unicast traffic between them, and the daemon needs its share.
        val engine = KdeConnectEngine(
            dir = dir,
            ports = KdePorts(udp = 0),
            fileSink = sink,
            tlsProtocols = tlsProtocols,
            log = { log += it; println("[engine] $it") },
        )
        engine.configure(KdeEngineConfig(deviceName = "WM Keyboard interop"))
        val collector = CoroutineScope(Dispatchers.Default).launch { engine.events.collect { events += it; println("[event] $it") } }
        try {
            assertTrue("engine could not bind a TCP port", engine.start())
            val me = engine.state.value.selfId
            val desktopId = cli("--my-id").lines().last { KdeDeviceIds.isValid(it.trim()) }.trim()
            println("me=$me desktop=$desktopId tcp=${engine.tcpPort} listening=${engine.state.value.listening}")

            // ---- discovery + handshake (they dial us: we are the TLS client) ----
            engine.setDiscovering(true)
            engine.announceTo(lanAddress())
            await("the desktop to dial back") { engine.state.value.device(desktopId)?.reachable == true }
            val seen = engine.state.value.device(desktopId)!!
            println("desktop: ${seen.name} (${seen.type}) accepts=${seen.accepts}")
            assertTrue(KdeTypes.MOUSEPAD_REQUEST in seen.accepts)

            // ---- pairing ----
            if (!seen.paired) {
                engine.requestPair(desktopId)
                await("our request to register") { engine.state.value.device(desktopId)?.pairState == KdePairState.REQUESTED }
                println("verification code on our side: ${engine.state.value.device(desktopId)?.verificationKey}")
                // Pressing "pair" on a device that is asking is how the desktop says yes.
                println(cli("--pair", "-d", me))
                await("the desktop to accept", 30_000) { engine.state.value.device(desktopId)?.paired == true }
            }
            engine.setDiscovering(false)
            assertTrue(cli("-a", "--id-only").contains(me))

            // ---- ping, both ways ----
            assertTrue(engine.ping.ping(desktopId, "hello from the JVM"))
            cli("--ping-msg", "hello from the desktop", "-d", me)
            await("the desktop's ping") { events.any { it is KdeEvent.Ping && it.message == "hello from the desktop" } }

            // ---- share: text, then a file in each direction (payload sockets, both TLS roles) ----
            cli("--share-text", "shared text", "-d", me)
            await("shared text") { events.any { it is KdeEvent.TextReceived && it.text == "shared text" } }

            val outgoing = File(dir, "from-desktop.bin").apply { writeBytes(ByteArray(150_000) { (it % 97).toByte() }) }
            cli("--share", outgoing.absolutePath, "-d", me)
            await("the desktop's file", 30_000) { events.any { it is KdeEvent.FileReceived } }
            val landed = File((events.first { it is KdeEvent.FileReceived } as KdeEvent.FileReceived).location)
            assertTrue(landed.readBytes().contentEquals(outgoing.readBytes()))

            val name = "wmkb-interop-${System.currentTimeMillis()}.txt"
            val body = "sent by WM Keyboard's engine".toByteArray()
            engine.share.sendFiles(desktopId, listOf(KdeOutgoingFile(name, body.size.toLong(), System.currentTimeMillis()) { body.inputStream() }))
            await("our file to be fetched", 40_000) {
                engine.state.value.device(desktopId)!!.transfers.any { it.fileName == name && it.state == KdeTransferState.DONE }
            }

            // ---- clipboard: theirs to us ----
            // The machine's real clipboard is borrowed for this and put back.
            val borrowed = ProcessBuilder("pbpaste").start().inputStream.readBytes()
            fun pbcopy(bytes: ByteArray) = ProcessBuilder("pbcopy").start().apply { outputStream.use { it.write(bytes) } }.waitFor()
            try {
                pbcopy("copied on the desktop".toByteArray())
                Thread.sleep(500)
                cli("--send-clipboard", "-d", me)
                await("the desktop's clipboard") { events.any { it is KdeEvent.ClipboardReceived && it.text == "copied on the desktop" } }
            } finally {
                pbcopy(borrowed)
            }

            // ---- the desktop typing into us ----
            engine.setKeyboardShown(true)
            Thread.sleep(500)
            cli("--send-keys", "typed on the desktop", "-d", me)
            await("remote keys") { events.filterIsInstance<KdeEvent.RemoteKey>().joinToString("") { it.text } == "typed on the desktop" }

            // ---- the pointer: one pixel out and back, so nothing visibly moves ----
            assertTrue(engine.mousepad.move(desktopId, 1.0, 0.0))
            assertTrue(engine.mousepad.move(desktopId, -1.0, 0.0))
            println(cli("--encryption-info", "-d", me))

            // ---- what the desktop told us about itself ----
            Thread.sleep(1_500)
            val after = engine.state.value.device(desktopId)!!
            println("battery=${after.battery} sinks=${after.volume.sinks.map { it.description }} players=${after.mpris.players} commands=${after.commands.list.map { it.name }} lock=${after.lock}")
            assertNotNull(after.fingerprint)
            assertEquals(95, after.fingerprint.length)
        } finally {
            collector.cancel()
            engine.close()
        }
    }

    /**
     * The other handshake: **we** dial, which makes us the TLS *server*. On a
     * real network that happens whenever the desktop's announcement is heard;
     * here a stand-in for mDNS reports the daemon, and our own announcements are
     * aimed at the discard port so the daemon cannot get in first.
     *
     * Needs the pairing the test above leaves behind in the interop dir.
     */
    @Test
    fun `dialling a paired desktop found by mdns links with the pinned certificate`() {
        assumeTrue("interop tests need -Pkde.interop=1", enabled)
        val desktopId = cli("--my-id").lines().last { KdeDeviceIds.isValid(it.trim()) }.trim()
        val address = java.net.InetAddress.getByName(lanAddress())
        val mdns = object : KdeMdns {
            override fun start(deviceId: String, name: String, type: String, protocolVersion: Int, tcpPort: Int, onFound: (KdeMdns.Found) -> Unit) {
                onFound(KdeMdns.Found(desktopId, address, 1716, KDE_PROTOCOL_VERSION))
            }

            override fun stop() = Unit
        }
        val engine = KdeConnectEngine(
            dir = interopDir(),
            ports = KdePorts(udp = 0, udpTarget = 9),
            mdns = mdns,
            tlsProtocols = tlsProtocols,
            log = { log += it; println("[engine] $it") },
        )
        engine.configure(KdeEngineConfig(deviceName = "WM Keyboard interop"))
        val collector = CoroutineScope(Dispatchers.Default).launch { engine.events.collect { events += it; println("[event] $it") } }
        try {
            assertTrue(engine.start())
            assumeTrue("run the pairing test first", engine.hasPairedDevices())
            await("our dial to link") { engine.state.value.device(desktopId)?.reachable == true }
            assertTrue(log.any { it.contains("dialled") })
            cli("--ping-msg", "over the dialled link", "-d", engine.state.value.selfId)
            await("a ping over it") { events.any { it is KdeEvent.Ping && it.message == "over the dialled link" } }
        } finally {
            collector.cancel()
            engine.close()
        }
    }
}
