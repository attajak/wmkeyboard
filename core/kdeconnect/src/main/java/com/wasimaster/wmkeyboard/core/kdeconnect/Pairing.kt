package com.wasimaster.wmkeyboard.core.kdeconnect

import kotlinx.serialization.json.put
import java.security.MessageDigest
import java.security.cert.X509Certificate
import kotlin.math.abs

enum class KdePairState { NOT_PAIRED, REQUESTED, REQUESTED_BY_PEER, PAIRED }

/**
 * The code both screens show while pairing, so the user can see that the device
 * asking is the device in front of them and not something else on the Wi-Fi.
 *
 * `SHA-256( larger key ‖ smaller key ‖ timestamp as decimal text )`, first eight
 * hex digits, upper case. The details that are easy to get wrong, each of which
 * produces a code that simply never matches the desktop's:
 * - the input is the public key's SubjectPublicKeyInfo DER, not the certificate;
 * - "larger" is an **unsigned** byte comparison, larger first;
 * - the timestamp is the *request's*, in **seconds**, as ASCII digits;
 * - protocol 7 peers leave the timestamp out altogether.
 */
object KdeVerificationKey {

    fun of(a: X509Certificate, b: X509Certificate, timestampSeconds: Long?): String =
        of(a.publicKey.encoded, b.publicKey.encoded, timestampSeconds)

    fun of(keyA: ByteArray, keyB: ByteArray, timestampSeconds: Long?): String {
        val digest = MessageDigest.getInstance("SHA-256")
        if (compareUnsigned(keyA, keyB) < 0) {
            digest.update(keyB)
            digest.update(keyA)
        } else {
            digest.update(keyA)
            digest.update(keyB)
        }
        if (timestampSeconds != null) digest.update(timestampSeconds.toString().toByteArray(Charsets.US_ASCII))
        return digest.digest().toHex().substring(0, 8).uppercase()
    }

    internal fun compareUnsigned(a: ByteArray, b: ByteArray): Int {
        val n = minOf(a.size, b.size)
        for (i in 0 until n) {
            val x = a[i].toInt() and 0xFF
            val y = b[i].toInt() and 0xFF
            if (x != y) return x - y
        }
        return a.size - b.size
    }
}

/**
 * Pairing with one device: the four states and every edge between them, with
 * no sockets, clocks or coroutines of its own so the whole table can be tested.
 * The engine feeds it packets and user decisions and carries out the [Effect]s
 * it answers with.
 *
 * The table is the reference clients', including its two surprises:
 * - a pair request from a device we already trust is **not** auto-accepted.
 *   Request and accept are the same packet (`pair: true`), so two devices
 *   auto-accepting each other would never stop. We unpair and treat it as new.
 * - a second request while one is pending is ignored rather than restarted.
 */
class KdePairing(
    initiallyPaired: Boolean,
    private val peerProtocolVersion: () -> Int,
) {
    sealed interface Effect {
        data class Send(val packet: KdePacket) : Effect

        /** Start the timer; when it fires, call [onTimeout]. */
        data class StartTimer(val millis: Long) : Effect
        data object StopTimer : Effect

        /** Persist the peer's certificate: it is trusted from now on. */
        data object Trust : Effect

        /** Forget the peer's certificate. */
        data object Distrust : Effect
        data class Failed(val reason: KdePairFailure) : Effect

        /** The peer asked; show the code and the Accept / Reject choice. */
        data object AskUser : Effect
    }

    var state: KdePairState = if (initiallyPaired) KdePairState.PAIRED else KdePairState.NOT_PAIRED
        private set

    /** The request's timestamp in seconds, which the verification code is salted with. */
    var timestampSeconds: Long? = null
        private set

    val inProgress: Boolean
        get() = state == KdePairState.REQUESTED || state == KdePairState.REQUESTED_BY_PEER

    fun request(nowMs: Long): List<Effect> = when (state) {
        KdePairState.PAIRED, KdePairState.REQUESTED -> emptyList()
        // They asked first and the user pressed Pair on our side: that is a yes.
        KdePairState.REQUESTED_BY_PEER -> accept()
        KdePairState.NOT_PAIRED -> {
            val seconds = nowMs / 1000
            timestampSeconds = seconds
            state = KdePairState.REQUESTED
            listOf(
                Effect.Send(kdePacket(KdeTypes.PAIR) { put("pair", true); put("timestamp", seconds) }),
                Effect.StartTimer(REQUEST_TIMEOUT_MS),
            )
        }
    }

    fun accept(): List<Effect> {
        if (state != KdePairState.REQUESTED_BY_PEER) return emptyList()
        state = KdePairState.PAIRED
        return listOf(
            Effect.StopTimer,
            // An accept carries no timestamp: only the request does.
            Effect.Send(kdePacket(KdeTypes.PAIR) { put("pair", true) }),
            Effect.Trust,
        )
    }

    /** Reject their request, or withdraw ours. */
    fun cancel(): List<Effect> {
        if (!inProgress) return emptyList()
        state = KdePairState.NOT_PAIRED
        timestampSeconds = null
        return listOf(Effect.StopTimer, Effect.Send(unpairPacket()))
    }

    fun unpair(reachable: Boolean): List<Effect> {
        val wasPaired = state == KdePairState.PAIRED
        val effects = ArrayList<Effect>()
        if (inProgress) effects += Effect.StopTimer
        state = KdePairState.NOT_PAIRED
        timestampSeconds = null
        if (reachable) effects += Effect.Send(unpairPacket())
        if (wasPaired) effects += Effect.Distrust
        return effects
    }

    fun onTimeout(): List<Effect> {
        if (!inProgress) return emptyList()
        state = KdePairState.NOT_PAIRED
        timestampSeconds = null
        // kdeconnect-kde tells the peer; kdeconnect-android does not. Telling
        // costs a packet and clears the other screen's prompt.
        return listOf(Effect.Send(unpairPacket()), Effect.Failed(KdePairFailure.TIMED_OUT))
    }

    /** The link dropped mid-pairing: there is nobody left to answer. */
    fun onDisconnected(): List<Effect> {
        if (!inProgress) return emptyList()
        state = KdePairState.NOT_PAIRED
        timestampSeconds = null
        return listOf(Effect.StopTimer, Effect.Failed(KdePairFailure.DISCONNECTED))
    }

    fun onPacket(packet: KdePacket, nowMs: Long): List<Effect> {
        val wantsPair = packet.bool("pair")
        if (!wantsPair) {
            return when (state) {
                KdePairState.NOT_PAIRED -> emptyList()
                KdePairState.PAIRED -> {
                    state = KdePairState.NOT_PAIRED
                    listOf(Effect.Distrust)
                }
                KdePairState.REQUESTED, KdePairState.REQUESTED_BY_PEER -> {
                    state = KdePairState.NOT_PAIRED
                    timestampSeconds = null
                    listOf(Effect.StopTimer, Effect.Failed(KdePairFailure.REJECTED))
                }
            }
        }
        return when (state) {
            KdePairState.REQUESTED -> {
                state = KdePairState.PAIRED
                listOf(Effect.StopTimer, Effect.Trust)
            }
            KdePairState.REQUESTED_BY_PEER -> emptyList()
            KdePairState.PAIRED -> {
                state = KdePairState.NOT_PAIRED
                listOf(Effect.Distrust) + incomingRequest(packet, nowMs)
            }
            KdePairState.NOT_PAIRED -> incomingRequest(packet, nowMs)
        }
    }

    private fun incomingRequest(packet: KdePacket, nowMs: Long): List<Effect> {
        if (peerProtocolVersion() >= 8) {
            // Both desktops treat a request without a timestamp as an unpair,
            // and one from a clock half an hour out as a failure.
            val stamp = packet.long("timestamp") ?: return emptyList()
            if (abs(stamp - nowMs / 1000) > MAX_CLOCK_SKEW_SECONDS) {
                return listOf(Effect.Send(unpairPacket()), Effect.Failed(KdePairFailure.CLOCKS_DISAGREE))
            }
            timestampSeconds = stamp
        } else {
            timestampSeconds = null
        }
        state = KdePairState.REQUESTED_BY_PEER
        return listOf(Effect.StartTimer(INCOMING_TIMEOUT_MS), Effect.AskUser)
    }

    private fun unpairPacket() = kdePacket(KdeTypes.PAIR) { put("pair", false) }

    companion object {
        /** How long we wait for the other side to accept. */
        const val REQUEST_TIMEOUT_MS = 30_000L

        /** How long our user gets: five seconds short of the peer's own timeout. */
        const val INCOMING_TIMEOUT_MS = 25_000L
        const val MAX_CLOCK_SKEW_SECONDS = 1_800L
    }
}

enum class KdePairFailure { TIMED_OUT, REJECTED, CLOCKS_DISAGREE, DISCONNECTED, NOT_REACHABLE }
