package com.wasimaster.wmkeyboard.core.kdeconnect

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Two whole engines, one machine, real sockets and real TLS.
 *
 * Nothing here is mocked: the "phone" and the "pc" are both this module, on
 * ephemeral ports, meeting over 127.0.0.1 exactly as two devices meet over a
 * LAN — a UDP announcement, the dial back, the plaintext line, the inverted TLS
 * roles, the encrypted identity exchange, pairing, then plugins. It cannot
 * prove we agree with kdeconnect-kde (the interop test does that), but it does
 * prove the engine agrees with *itself* in both roles of every exchange, which
 * is where most of the ways to be wrong live.
 */
class LoopbackTest {

    private val dirs = ArrayList<File>()
    private val engines = ArrayList<KdeConnectEngine>()
    private val collectors = ArrayList<Job>()
    private val scope = CoroutineScope(Dispatchers.Default)

    private class Peer(val engine: KdeConnectEngine, val events: MutableList<KdeEvent>, val dir: File) {
        val id: String get() = engine.state.value.selfId
    }

    private fun peer(name: String, sink: KdeFileSink? = null, dir: File? = null): Peer {
        val folder = dir ?: Files.createTempDirectory("kde-$name").toFile().also { dirs += it }
        val engine = KdeConnectEngine(
            dir = folder,
            ports = KdePorts.Ephemeral,
            fileSink = sink,
            allowLoopback = true,
            log = { println("[$name] $it") },
        )
        engine.configure(KdeEngineConfig(deviceName = name))
        engines += engine
        val events = CopyOnWriteArrayList<KdeEvent>()
        collectors += scope.launch { engine.events.collect { events += it } }
        assertTrue(engine.start())
        return Peer(engine, events, folder)
    }

    @After
    fun tearDown() {
        collectors.forEach { it.cancel() }
        engines.forEach { it.close() }
        dirs.forEach { it.deleteRecursively() }
    }

    private fun await(what: String, timeoutMs: Long = 10_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) throw AssertionError("timed out waiting for: $what")
            Thread.sleep(20)
        }
    }

    /** [from] announces to [to], which dials back: the way a phone finds a desktop. */
    private fun introduce(from: Peer, to: Peer) {
        from.engine.announceTo("127.0.0.1", to.engine.udpPort)
    }

    private fun pair(phone: Peer, pc: Peer) {
        phone.engine.setDiscovering(true)
        pc.engine.setDiscovering(true)
        introduce(phone, pc)
        await("both sides see each other") {
            phone.engine.state.value.device(pc.id)?.reachable == true &&
                pc.engine.state.value.device(phone.id)?.reachable == true
        }
        phone.engine.requestPair(pc.id)
        await("pc is asked") { pc.engine.state.value.device(phone.id)?.pairState == KdePairState.REQUESTED_BY_PEER }
        pc.engine.acceptPair(phone.id)
        await("paired both ways") {
            phone.engine.state.value.device(pc.id)?.paired == true && pc.engine.state.value.device(phone.id)?.paired == true
        }
        phone.engine.setDiscovering(false)
        pc.engine.setDiscovering(false)
    }

    @Test
    fun `strangers are only linked while the user is looking for devices`() {
        val phone = peer("Phone")
        val pc = peer("PC")
        introduce(phone, pc)
        Thread.sleep(600)
        assertTrue(pc.engine.state.value.devices.isEmpty())
        assertTrue(phone.engine.state.value.devices.isEmpty())

        phone.engine.setDiscovering(true)
        pc.engine.setDiscovering(true)
        // The dial-back is rate limited to once a second per device.
        Thread.sleep(LanTransport.MIN_DIAL_INTERVAL_MS)
        introduce(phone, pc)
        await("linked") { pc.engine.state.value.device(phone.id)?.reachable == true }
        val seen = pc.engine.state.value.device(phone.id)!!
        assertEquals("Phone", seen.name)
        assertFalse(seen.paired)
        assertTrue(KdeTypes.MOUSEPAD_REQUEST in seen.accepts)

        pc.engine.setDiscovering(false)
        await("stranger dropped") { pc.engine.state.value.devices.isEmpty() }
    }

    @Test
    fun `both screens show the same code and accepting pairs both sides`() {
        val phone = peer("Phone")
        val pc = peer("PC")
        phone.engine.setDiscovering(true)
        pc.engine.setDiscovering(true)
        introduce(phone, pc)
        await("linked") { phone.engine.state.value.device(pc.id)?.reachable == true && pc.engine.state.value.device(phone.id)?.reachable == true }

        phone.engine.requestPair(pc.id)
        await("pc is asked") { pc.events.any { it is KdeEvent.PairRequested } }
        val ours = phone.engine.state.value.device(pc.id)!!.verificationKey
        val theirs = pc.engine.state.value.device(phone.id)!!.verificationKey
        assertNotNull(ours)
        assertEquals(ours, theirs)
        assertEquals(ours, (pc.events.first { it is KdeEvent.PairRequested } as KdeEvent.PairRequested).verificationKey)
        assertTrue(phone.engine.state.value.device(pc.id)!!.pairDeadlineMs > System.currentTimeMillis())

        pc.engine.acceptPair(phone.id)
        await("paired") { phone.engine.state.value.device(pc.id)?.paired == true }
        // Events reach the list through a collector coroutine, so they can
        // trail the state that was updated in the same step.
        await("paired event") { phone.events.any { it is KdeEvent.Paired } }
        assertNull(phone.engine.state.value.device(pc.id)!!.verificationKey)
        assertTrue(phone.engine.hasPairedDevices())
    }

    @Test
    fun `a rejected request fails and leaves nobody trusted`() {
        val phone = peer("Phone")
        val pc = peer("PC")
        phone.engine.setDiscovering(true)
        pc.engine.setDiscovering(true)
        introduce(phone, pc)
        await("linked") { phone.engine.state.value.device(pc.id)?.reachable == true && pc.engine.state.value.device(phone.id)?.reachable == true }
        phone.engine.requestPair(pc.id)
        await("pc is asked") { pc.engine.state.value.device(phone.id)?.pairState == KdePairState.REQUESTED_BY_PEER }
        pc.engine.cancelPair(phone.id)
        await("refused") { phone.events.any { it is KdeEvent.PairFailed && it.reason == KdePairFailure.REJECTED } }
        assertFalse(phone.engine.hasPairedDevices())
        assertFalse(pc.engine.hasPairedDevices())
    }

    @Test
    fun `nothing but pairing is accepted from or sent to an unpaired device`() {
        val phone = peer("Phone")
        val pc = peer("PC")
        phone.engine.setDiscovering(true)
        pc.engine.setDiscovering(true)
        introduce(phone, pc)
        await("linked") { phone.engine.state.value.device(pc.id)?.reachable == true }
        // An unpaired peer would answer a stray packet by unpairing us.
        assertFalse(phone.engine.ping.ping(pc.id, "hello"))
        assertFalse(phone.engine.mousepad.text(pc.id, "hello"))
        Thread.sleep(300)
        assertTrue(pc.events.none { it is KdeEvent.Ping })
    }

    @Test
    fun `a paired device reconnects without being asked and unpairing reaches the other side`() {
        val phone = peer("Phone")
        val pc = peer("PC")
        pair(phone, pc)

        // The PC goes away and comes back with the same identity.
        val pcDir = pc.dir
        val pcId = pc.id
        pc.engine.close()
        await("phone notices") { phone.engine.state.value.device(pcId)?.reachable == false }
        assertTrue(phone.engine.state.value.device(pcId)!!.paired)

        val back = peer("PC", dir = pcDir)
        assertEquals(pcId, back.id)
        introduce(back, phone)
        await("relinked, pinned, no discovery needed") {
            phone.engine.state.value.device(pcId)?.reachable == true && back.engine.state.value.device(phone.id)?.reachable == true
        }

        phone.engine.unpair(pcId)
        await("both forget") { !phone.engine.hasPairedDevices() && !back.engine.hasPairedDevices() }
        await("unpaired event") { back.events.any { it is KdeEvent.Unpaired } }
    }

    @Test
    fun `a paired id presenting a different certificate is refused`() {
        val phone = peer("Phone")
        val pc = peer("PC")
        pair(phone, pc)
        val pcId = pc.id
        pc.engine.close()
        await("phone notices") { phone.engine.state.value.device(pcId)?.reachable == false }

        // Same device id, new key pair: what an impersonator on the LAN has.
        val forgedDir = Files.createTempDirectory("kde-forged").toFile().also { dirs += it }
        val keys = DerCertificate.generateKeyPair()
        File(forgedDir, KdeIdentityStore.KEY_FILE).writeBytes(keys.private.encoded)
        File(forgedDir, KdeIdentityStore.CERT_FILE).writeBytes(DerCertificate.selfSigned(pcId, keys).encoded)
        val forged = peer("PC", dir = forgedDir)
        assertEquals(pcId, forged.id)
        forged.engine.setDiscovering(true)
        introduce(forged, phone)
        Thread.sleep(1_500)
        assertFalse(phone.engine.state.value.device(pcId)!!.reachable)
    }

    @Test
    fun `the pc types into the phone and gets the echo its text box needs`() {
        val phone = peer("Phone")
        val pc = peer("PC")
        pair(phone, pc)

        // Keys are dropped until the keyboard says it is on screen.
        pc.engine.mousepad.text(phone.id, "too early")
        Thread.sleep(200)
        assertTrue(phone.events.none { it is KdeEvent.RemoteKey })

        phone.engine.setKeyboardShown(true)
        await("pc learns the phone takes keys") { pc.engine.state.value.device(phone.id)?.acceptsKeys == true }
        pc.engine.mousepad.text(phone.id, "héllo 😀")
        pc.engine.mousepad.special(phone.id, KdeSpecialKey.ENTER, KdeModifiers(ctrl = true))
        pc.engine.mousepad.chord(phone.id, "A", KdeModifiers(ctrl = true))
        await("three keys") { phone.events.count { it is KdeEvent.RemoteKey } == 3 }
        val keys = phone.events.filterIsInstance<KdeEvent.RemoteKey>()
        assertEquals("héllo 😀", keys[0].text)
        assertNull(keys[0].special)
        assertEquals(KdeSpecialKey.ENTER, keys[1].special)
        assertTrue(keys[1].ctrl)
        assertEquals("", keys[1].text)
        assertEquals("a", keys[2].text)
        assertTrue(keys[2].ctrl)

        phone.engine.setKeyboardShown(false)
        await("pc learns the phone stopped") { pc.engine.state.value.device(phone.id)?.acceptsKeys == false }
    }

    @Test
    fun `the clipboard crosses once and never bounces back`() {
        val phone = peer("Phone")
        val pc = peer("PC")
        pair(phone, pc)

        assertEquals(1, phone.engine.clipboard.localChanged("copied on the phone", isSensitive = false))
        await("pc receives") { pc.events.any { it is KdeEvent.ClipboardReceived } }
        assertEquals("copied on the phone", (pc.events.first { it is KdeEvent.ClipboardReceived } as KdeEvent.ClipboardReceived).text)

        // The PC's own clipboard listener now fires for the text it was just given.
        assertEquals(0, pc.engine.clipboard.localChanged("copied on the phone", isSensitive = false))
        Thread.sleep(300)
        assertTrue(phone.events.none { it is KdeEvent.ClipboardReceived })

        // A password is remembered but not sent; asking for it explicitly sends it.
        assertEquals(0, phone.engine.clipboard.localChanged("hunter2", isSensitive = true))
        assertEquals(1, phone.engine.clipboard.push("hunter2"))
        await("pushed") { pc.events.count { it is KdeEvent.ClipboardReceived } == 2 }
    }

    @Test
    fun `text links pings and commands all arrive`() {
        val phone = peer("Phone")
        val pc = peer("PC")
        pair(phone, pc)

        assertTrue(phone.engine.ping.ping(pc.id, "are you there"))
        assertTrue(phone.engine.share.sendText(pc.id, "some text"))
        assertTrue(phone.engine.share.sendUrl(pc.id, "https://kde.org/"))
        await("all three") { pc.events.count { it is KdeEvent.Ping || it is KdeEvent.TextReceived || it is KdeEvent.UrlReceived } == 3 }
        assertEquals("are you there", pc.events.filterIsInstance<KdeEvent.Ping>().single().message)
        assertEquals("some text", pc.events.filterIsInstance<KdeEvent.TextReceived>().single().text)
        assertEquals("https://kde.org/", pc.events.filterIsInstance<KdeEvent.UrlReceived>().single().url)
        // We announce no handler for these, so the peer's send is refused locally.
        assertFalse(phone.engine.state.value.device(pc.id)!!.canSend("kdeconnect.sftp.request"))
    }

    @Test
    fun `files cross on a second connection and land whole`() {
        val received = HashMap<String, ByteArrayOutputStream>()
        val sink = object : KdeFileSink {
            override fun create(deviceName: String, fileName: String, size: Long): KdeIncomingFile {
                val buffer = ByteArrayOutputStream()
                return object : KdeIncomingFile {
                    override val stream: OutputStream = buffer
                    override fun finish(success: Boolean): String? {
                        if (success) synchronized(received) { received[fileName] = buffer }
                        return if (success) "memory://$fileName" else null
                    }
                }
            }
        }
        val phone = peer("Phone")
        val pc = peer("PC", sink = sink)
        pair(phone, pc)

        val big = ByteArray(300_000) { (it % 251).toByte() }
        val small = "hello".toByteArray()
        phone.engine.share.sendFiles(
            pc.id,
            listOf(
                KdeOutgoingFile("../../etc/big.bin", big.size.toLong(), 1_700_000_000_000) { big.inputStream() },
                KdeOutgoingFile("note.txt", small.size.toLong(), 0) { small.inputStream() },
            ),
        )
        await("both received", 20_000) { pc.events.count { it is KdeEvent.FileReceived } == 2 }
        // The path the sender chose is not honoured: only the name.
        assertTrue(synchronized(received) { received.getValue("big.bin").toByteArray() }.contentEquals(big))
        assertTrue(synchronized(received) { received.getValue("note.txt").toByteArray() }.contentEquals(small))
        await("sender sees both done") {
            phone.engine.state.value.device(pc.id)!!.transfers.count { it.state == KdeTransferState.DONE } == 2
        }
        val incoming = pc.engine.state.value.device(phone.id)!!.transfers
        assertEquals(2, incoming.count { !it.outgoing && it.state == KdeTransferState.DONE && it.location.startsWith("memory://") })
    }

    @Test
    fun `the phone's media shows up as a player the pc can drive`() {
        val phone = peer("Phone")
        val pc = peer("PC")
        pair(phone, pc)

        phone.engine.phoneMedia.setMedia(
            KdePhoneMedia("Music", "A Song", "A Band", "An Album", playing = true, positionMs = 12_000, lengthMs = 180_000, volume = 40, canNext = true, canPrevious = false, canSeek = true),
        )
        await("pc sees the player") { pc.engine.state.value.device(phone.id)?.mpris?.current?.title == "A Song" }
        val player = pc.engine.state.value.device(phone.id)!!.mpris.current!!
        assertEquals("A Band", player.artist)
        assertEquals(180_000, player.lengthMs)
        assertTrue(player.playing)
        assertFalse(player.canPrevious)
        assertEquals(40, player.volume)

        pc.engine.mpris.action(phone.id, "Music", KdeMediaAction.PLAY_PAUSE)
        pc.engine.mpris.seekTo(phone.id, "Music", 90_000)
        pc.engine.mpris.seekBy(phone.id, "Music", -5_000)
        await("three commands") { phone.events.count { it is KdeEvent.PhoneMediaCommand } == 3 }
        val commands = phone.events.filterIsInstance<KdeEvent.PhoneMediaCommand>()
        assertEquals(KdeMediaAction.PLAY_PAUSE, commands[0].action)
        assertEquals(90_000L, commands[1].seekToMs)
        // Seek travels in microseconds and must come back as the milliseconds it was.
        assertEquals(-5_000L, commands[2].seekByMs)
    }

    @Test
    fun `battery goes out once per change and a switched off plugin stays silent`() = runBlocking {
        val phone = peer("Phone")
        val pc = peer("PC")
        pair(phone, pc)

        phone.engine.battery.setLocal(81, charging = true, low = false)
        withTimeout(5_000) { pc.engine.state.first { it.device(phone.id)?.battery?.charge == 81 } }
        assertTrue(pc.engine.state.value.device(phone.id)!!.battery!!.charging)

        pc.engine.setPluginEnabled(phone.id, KdePluginKeys.CLIPBOARD, enabled = false)
        phone.engine.clipboard.localChanged("not for you", isSensitive = false)
        Thread.sleep(300)
        assertTrue(pc.events.none { it is KdeEvent.ClipboardReceived })
    }
}
