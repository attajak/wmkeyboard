package com.wasimaster.wmkeyboard.core.kdeconnect

import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pairing table, and the code the two screens show. Both are small and
 * both are where a from-scratch client most easily disagrees with a desktop
 * without any error appearing anywhere: a state machine that auto-accepts loops
 * forever, a verification code off by one byte-order simply never matches.
 */
class PairingTest {

    private val now = 1_758_500_000_000L
    private fun pairing(paired: Boolean = false, version: Int = 8) = KdePairing(paired) { version }

    private fun request(stampSeconds: Long? = now / 1000) = kdePacket(KdeTypes.PAIR) {
        put("pair", true)
        if (stampSeconds != null) put("timestamp", stampSeconds)
    }

    private val accept = kdePacket(KdeTypes.PAIR) { put("pair", true) }
    private val refuse = kdePacket(KdeTypes.PAIR) { put("pair", false) }

    private fun List<KdePairing.Effect>.sent(): List<KdePacket> = filterIsInstance<KdePairing.Effect.Send>().map { it.packet }

    @Test
    fun `our request carries a timestamp in seconds and starts the 30 second timer`() {
        val p = pairing()
        val effects = p.request(now)
        assertEquals(KdePairState.REQUESTED, p.state)
        val packet = effects.sent().single()
        assertTrue(packet.bool("pair"))
        assertEquals(now / 1000, packet.long("timestamp"))
        assertTrue(KdePairing.Effect.StartTimer(30_000) in effects)
        assertEquals(now / 1000, p.timestampSeconds)
    }

    @Test
    fun `their yes to our request trusts them`() {
        val p = pairing()
        p.request(now)
        val effects = p.onPacket(accept, now)
        assertEquals(KdePairState.PAIRED, p.state)
        assertTrue(KdePairing.Effect.Trust in effects)
        assertTrue(KdePairing.Effect.StopTimer in effects)
        assertTrue(effects.sent().isEmpty())
    }

    @Test
    fun `their no to our request fails it`() {
        val p = pairing()
        p.request(now)
        val effects = p.onPacket(refuse, now)
        assertEquals(KdePairState.NOT_PAIRED, p.state)
        assertTrue(KdePairing.Effect.Failed(KdePairFailure.REJECTED) in effects)
    }

    @Test
    fun `their request asks the user and gives them 25 seconds`() {
        val p = pairing()
        val effects = p.onPacket(request(), now)
        assertEquals(KdePairState.REQUESTED_BY_PEER, p.state)
        assertTrue(KdePairing.Effect.AskUser in effects)
        assertTrue(KdePairing.Effect.StartTimer(25_000) in effects)
        assertTrue(effects.sent().isEmpty())
    }

    @Test
    fun `accepting answers without a timestamp and trusts`() {
        val p = pairing()
        p.onPacket(request(), now)
        val effects = p.accept()
        assertEquals(KdePairState.PAIRED, p.state)
        val packet = effects.sent().single()
        assertTrue(packet.bool("pair"))
        assertFalse(packet.has("timestamp"))
        assertTrue(KdePairing.Effect.Trust in effects)
    }

    @Test
    fun `pressing pair while they are asking is a yes`() {
        val p = pairing()
        p.onPacket(request(), now)
        assertTrue(KdePairing.Effect.Trust in p.request(now))
        assertEquals(KdePairState.PAIRED, p.state)
    }

    @Test
    fun `a second request while one is pending changes nothing`() {
        val p = pairing()
        p.onPacket(request(), now)
        assertTrue(p.onPacket(request(), now).isEmpty())
        assertEquals(KdePairState.REQUESTED_BY_PEER, p.state)
    }

    @Test
    fun `a request from a device already trusted is not auto accepted`() {
        // Request and accept are the same packet. Two devices auto-accepting
        // each other would never stop, so trust is dropped and the user asked.
        val p = pairing(paired = true)
        val effects = p.onPacket(request(), now)
        assertEquals(KdePairState.REQUESTED_BY_PEER, p.state)
        assertEquals(KdePairing.Effect.Distrust, effects.first())
        assertTrue(KdePairing.Effect.AskUser in effects)
        assertFalse(KdePairing.Effect.Trust in effects)
    }

    @Test
    fun `a version 8 request without a timestamp is ignored`() {
        val p = pairing()
        assertTrue(p.onPacket(request(stampSeconds = null), now).isEmpty())
        assertEquals(KdePairState.NOT_PAIRED, p.state)
    }

    @Test
    fun `a version 7 request needs no timestamp and gets no salt`() {
        val p = pairing(version = 7)
        assertTrue(KdePairing.Effect.AskUser in p.onPacket(request(stampSeconds = null), now))
        assertNull(p.timestampSeconds)
    }

    @Test
    fun `clocks more than half an hour apart refuse to pair`() {
        val p = pairing()
        val effects = p.onPacket(request(now / 1000 + 1801), now)
        assertEquals(KdePairState.NOT_PAIRED, p.state)
        assertTrue(KdePairing.Effect.Failed(KdePairFailure.CLOCKS_DISAGREE) in effects)
        assertFalse(KdePairing.Effect.AskUser in effects)

        assertTrue(KdePairing.Effect.AskUser in pairing().onPacket(request(now / 1000 - 1799), now))
    }

    @Test
    fun `a timeout tells the other side and fails`() {
        val p = pairing()
        p.request(now)
        val effects = p.onTimeout()
        assertEquals(KdePairState.NOT_PAIRED, p.state)
        assertFalse(effects.sent().single().bool("pair"))
        assertTrue(KdePairing.Effect.Failed(KdePairFailure.TIMED_OUT) in effects)
        // A timer that fires after the matter was settled does nothing.
        assertTrue(pairing(paired = true).onTimeout().isEmpty())
    }

    @Test
    fun `unpair from either side forgets the certificate`() {
        val ours = pairing(paired = true)
        val effects = ours.unpair(reachable = true)
        assertFalse(effects.sent().single().bool("pair"))
        assertTrue(KdePairing.Effect.Distrust in effects)

        // Out of reach: nothing to send, still forgotten.
        assertTrue(pairing(paired = true).unpair(reachable = false).sent().isEmpty())

        val theirs = pairing(paired = true)
        assertEquals(listOf(KdePairing.Effect.Distrust), theirs.onPacket(refuse, now))
        assertEquals(KdePairState.NOT_PAIRED, theirs.state)

        // An unpair for a device that was never paired is not news.
        assertTrue(pairing().onPacket(refuse, now).isEmpty())
    }

    @Test
    fun `losing the link mid pairing fails it quietly`() {
        val p = pairing()
        p.request(now)
        val effects = p.onDisconnected()
        assertTrue(KdePairing.Effect.Failed(KdePairFailure.DISCONNECTED) in effects)
        assertTrue(effects.sent().isEmpty())
        assertTrue(pairing(paired = true).onDisconnected().isEmpty())
    }

    // ---- the verification code ----

    @Test
    fun `the code is the same whichever side computes it`() {
        val a = DerCertificate.selfSigned(KdeDeviceIds.generate(), DerCertificate.generateKeyPair())
        val b = DerCertificate.selfSigned(KdeDeviceIds.generate(), DerCertificate.generateKeyPair())
        val code = KdeVerificationKey.of(a, b, 1_737_228_658)
        assertEquals(code, KdeVerificationKey.of(b, a, 1_737_228_658))
        assertTrue(Regex("^[0-9A-F]{8}$").matches(code))
        assertNotEquals(code, KdeVerificationKey.of(a, b, 1_737_228_659))
        assertNotEquals(code, KdeVerificationKey.of(a, b, null))
    }

    @Test
    fun `the larger key goes first by unsigned comparison`() {
        // 0x80 is larger than 0x7F unsigned and smaller signed: the difference
        // between a code that matches the desktop's and one that never does.
        val high = byteArrayOf(0x30, 0x80.toByte())
        val low = byteArrayOf(0x30, 0x7F)
        assertTrue(KdeVerificationKey.compareUnsigned(high, low) > 0)

        val expected = java.security.MessageDigest.getInstance("SHA-256")
            .digest(high + low + "5".toByteArray())
            .joinToString("") { "%02x".format(it) }.substring(0, 8).uppercase()
        assertEquals(expected, KdeVerificationKey.of(low, high, 5))
        assertEquals(expected, KdeVerificationKey.of(high, low, 5))
    }

    @Test
    fun `a longer key with the same prefix is the larger one`() {
        assertTrue(KdeVerificationKey.compareUnsigned(byteArrayOf(1, 2, 3), byteArrayOf(1, 2)) > 0)
        assertEquals(0, KdeVerificationKey.compareUnsigned(byteArrayOf(9), byteArrayOf(9)))
    }
}
