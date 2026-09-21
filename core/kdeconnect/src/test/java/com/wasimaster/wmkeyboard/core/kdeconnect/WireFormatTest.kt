package com.wasimaster.wmkeyboard.core.kdeconnect

import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Date

/**
 * The parts of the protocol that are pure data: what a packet looks like on the
 * wire, which ids and names a peer will accept, and the certificate. Each of
 * these fails *silently* against a real desktop when wrong — a dropped packet,
 * a device that never appears — so they are pinned here instead.
 */
class WireFormatTest {

    @Test
    fun `a packet is one line of compact json ending in a newline`() {
        val line = kdePacket(KdeTypes.PING) { put("message", "hi") }.serialize(nowMs = 42)
        assertEquals("{\"id\":42,\"type\":\"kdeconnect.ping\",\"body\":{\"message\":\"hi\"}}\n", line)
    }

    @Test
    fun `forward slashes are not escaped`() {
        // Qt does not escape them and kdeconnect-android un-escapes its own to match.
        val line = kdePacket(KdeTypes.SHARE_REQUEST) { put("url", "https://kde.org/a/b") }.serialize(0)
        assertTrue(line.contains("https://kde.org/a/b"))
    }

    @Test
    fun `payload fields appear only with a payload and round trip`() {
        val plain = kdePacket(KdeTypes.SHARE_REQUEST) { put("text", "x") }
        assertFalse(plain.serialize(0).contains("payloadSize"))

        val withPayload = plain.copy(payloadSize = 882, payloadPort = 1739)
        val parsed = KdePacket.parse(withPayload.serialize(0))!!
        assertEquals(882, parsed.payloadSize)
        assertEquals(1739, parsed.payloadPort)
        assertTrue(parsed.hasPayload)
    }

    @Test
    fun `an endless stream announces minus one`() {
        val parsed = KdePacket.parse("""{"id":0,"type":"kdeconnect.share.request","body":{"filename":"a"},"payloadSize":-1,"payloadTransferInfo":{"port":1740}}""")!!
        assertEquals(-1, parsed.payloadSize)
        assertTrue(parsed.hasPayload)
    }

    @Test
    fun `the schema example from kdeconnect-meta parses`() {
        val identity = KdePacket.parse(
            """{"id":0,"type":"kdeconnect.identity","body":{"deviceId":"740bd4b9b4184ee497d6caf1da8151be","deviceName":"FOSS Phone","deviceType":"phone","incomingCapabilities":["kdeconnect.mock.echo"],"outgoingCapabilities":["kdeconnect.mock.transfer"],"protocolVersion":8}}""",
        )!!
        val info = KdeDeviceInfo.fromPacket(identity)!!
        assertEquals("740bd4b9b4184ee497d6caf1da8151be", info.id)
        assertEquals("FOSS Phone", info.name)
        assertEquals(KdeDeviceType.PHONE, info.type)
        assertEquals(setOf("kdeconnect.mock.echo"), info.incoming)
        assertEquals(setOf("kdeconnect.mock.transfer"), info.outgoing)
    }

    @Test
    fun `numbers and booleans are read in either spelling`() {
        // kdeconnect-android really sends targetProtocolVersion as "8" and
        // telephony's isCancel as "true"; an id has been seen as a string.
        val p = KdePacket.parse("""{"id":"17","type":"kdeconnect.identity","body":{"targetProtocolVersion":"8","isCancel":"true","n":3.0,"nothing":null}}""")!!
        assertEquals(8, p.int("targetProtocolVersion"))
        assertTrue(p.bool("isCancel"))
        assertEquals(3, p.int("n"))
        assertNull(p.int("nothing"))
        assertFalse(p.bool("nothing"))
        assertTrue(p.has("nothing"))
        assertNull(p.boolOrNull("nothing"))
    }

    @Test
    fun `garbage from the network is null and never an exception`() {
        assertNull(KdePacket.parse(""))
        assertNull(KdePacket.parse("not json"))
        assertNull(KdePacket.parse("[1,2,3]"))
        assertNull(KdePacket.parse("""{"body":{}}"""))
        assertNull(KdePacket.parse("""{"type":7,"body":{}}"""))
        assertNotNull(KdePacket.parse("""{"type":"kdeconnect.ping"}"""))
    }

    @Test
    fun `generated ids are 32 hex characters and old shaped ids are accepted`() {
        val id = KdeDeviceIds.generate()
        assertTrue(Regex("^[a-f0-9]{32}$").matches(id))
        assertTrue(KdeDeviceIds.isValid(id))
        // The shape kdeconnect-android minted for years.
        assertTrue(KdeDeviceIds.isValid("ee061a75_e403_4ecc_9261_5ffe2722f698"))
        assertFalse(KdeDeviceIds.isValid("short"))
        assertFalse(KdeDeviceIds.isValid("a".repeat(39)))
        assertFalse(KdeDeviceIds.isValid("a".repeat(31) + "/"))
    }

    @Test
    fun `names lose the punctuation every client strips and stop at 32`() {
        assertEquals("Wasis Pixel 8 WM Keyboard", KdeDeviceNames.sanitize("Wasi's Pixel 8 (WM Keyboard)"))
        assertEquals(32, KdeDeviceNames.sanitize("x".repeat(80)).length)
        assertEquals("", KdeDeviceNames.sanitize("()[]<>.,;:!?\"'"))
        // Cut before a surrogate pair rather than through it.
        val emoji = "a".repeat(31) + "😀"
        assertEquals("a".repeat(31), KdeDeviceNames.sanitize(emoji))
    }

    @Test
    fun `an identity that would not survive the peer's own checks is refused`() {
        fun identity(id: String, name: String, version: Int) = kdePacket(KdeTypes.IDENTITY) {
            put("deviceId", id); put("deviceName", name); put("deviceType", "desktop"); put("protocolVersion", version)
        }
        val good = KdeDeviceIds.generate()
        assertNotNull(KdeDeviceInfo.fromPacket(identity(good, "PC", 8)))
        assertNotNull(KdeDeviceInfo.fromPacket(identity(good, "PC", 7)))
        assertNull(KdeDeviceInfo.fromPacket(identity("bad id", "PC", 8)))
        assertNull(KdeDeviceInfo.fromPacket(identity(good, "...", 8)))
        assertNull(KdeDeviceInfo.fromPacket(identity(good, "PC", 6)))
        // A future peer is spoken to in our dialect.
        assertEquals(8, KdeDeviceInfo.fromPacket(identity(good, "PC", 9))!!.protocolVersion)
    }

    @Test
    fun `the dialler's opening line names its target with an integer version`() {
        val me = KdeDeviceInfo(KdeDeviceIds.generate(), "Phone", KdeDeviceType.PHONE)
        val line = me.toPacket(targetId = "t".repeat(32), targetVersion = 8).serialize(0)
        assertTrue(line.contains("\"targetProtocolVersion\":8"))
        assertTrue(line.contains("\"targetDeviceId\":\"${"t".repeat(32)}\""))
        assertFalse(line.contains("tcpPort"))
        assertTrue(me.toPacket(tcpPort = 1716).serialize(0).contains("\"tcpPort\":1716"))
    }

    @Test
    fun `the certificate is what a desktop expects and its signature verifies`() {
        val keys = DerCertificate.generateKeyPair()
        val id = KdeDeviceIds.generate()
        val now = Date()
        val cert = DerCertificate.selfSigned(id, keys, now)

        assertEquals(id, DerCertificate.commonName(cert))
        assertEquals(cert.subjectX500Principal, cert.issuerX500Principal)
        assertEquals(3, cert.version)
        assertEquals("EC", cert.publicKey.algorithm)
        assertTrue(cert.sigAlgName.equals("SHA512withECDSA", ignoreCase = true))
        assertTrue(cert.subjectX500Principal.name.contains("O=KDE"))
        assertTrue(cert.subjectX500Principal.name.contains("OU=KDE Connect"))
        // Valent verifies the self-signature; a placeholder would be refused.
        cert.verify(keys.public)
        cert.checkValidity(now)
        // Backdated a year: phone and desktop clocks disagree.
        assertTrue(cert.notBefore.before(Date(now.time - 300L * 24 * 3600 * 1000)))
        assertTrue(cert.notAfter.after(Date(now.time + 9L * 365 * 24 * 3600 * 1000)))
        assertTrue(cert.serialNumber.signum() > 0)
        assertEquals(95, DerCertificate.fingerprint(cert).length)
    }

    @Test
    fun `an identity survives a restart and a broken one is replaced`() {
        val dir = createTempDir()
        try {
            val first = KdeIdentityStore(dir).loadOrCreate()
            assertTrue(first.created)
            val second = KdeIdentityStore(dir).loadOrCreate()
            assertFalse(second.created)
            assertEquals(first.identity.deviceId, second.identity.deviceId)
            assertTrue(first.identity.certificate.encoded.contentEquals(second.identity.certificate.encoded))

            java.io.File(dir, KdeIdentityStore.KEY_FILE).writeBytes(byteArrayOf(1, 2, 3))
            assertTrue(KdeIdentityStore(dir).loadOrCreate().created)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `paired devices survive a restart`() {
        val dir = createTempDir()
        try {
            val cert = DerCertificate.selfSigned(KdeDeviceIds.generate(), DerCertificate.generateKeyPair())
            val id = DerCertificate.commonName(cert)!!
            KdeTrustStore(dir).put(KdeTrustedDevice(id, "PC", "laptop", KdeTrustStore.encode(cert), disabled = setOf(KdePluginKeys.SHARE)))
            val reloaded = KdeTrustStore(dir).get(id)!!
            assertEquals("PC", reloaded.name)
            assertEquals(setOf(KdePluginKeys.SHARE), reloaded.disabled)
            assertTrue(reloaded.x509()!!.encoded.contentEquals(cert.encoded))
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun createTempDir(): java.io.File =
        java.nio.file.Files.createTempDirectory("kdeconnect-test").toFile()
}
