package com.wasimaster.wmkeyboard.core.settings.sink.smb

import java.security.SecureRandom
import java.util.Locale

/**
 * NTLMv2, the client half, as MS-NLMP describes it: the three messages, the
 * session key, and the one signature SPNEGO asks of it.
 *
 * Only v2. LM and NTLMv1 are broken and every server this app would meet has
 * refused them for years, so there is no fallback to offer.
 *
 * One instance is one authentication. It keeps the negotiate and challenge
 * messages because the MIC in the third one covers all three.
 */
internal class Ntlm(
    private val user: String,
    private val password: String,
    private val domain: String,
    private val random: SecureRandom = SecureRandom(),
    /** A test hook: the client challenge and session key are random otherwise. */
    private val fixedClientChallenge: ByteArray? = null,
    private val fixedSessionKey: ByteArray? = null,
    private val nowFileTime: () -> Long = { FileTime.fromMillis(System.currentTimeMillis()) },
) {

    private var negotiateMessage: ByteArray = ByteArray(0)
    private var challengeMessage: ByteArray = ByteArray(0)
    private var flags: Long = 0

    /** The key both sides now share, once [authenticate] has run. */
    var exportedSessionKey: ByteArray = ByteArray(0)
        private set

    /** Message 1. */
    fun negotiate(): ByteArray {
        val w = LeWriter()
        w.bytes(SIGNATURE).u32(TYPE_NEGOTIATE).u32(CLIENT_FLAGS)
        // Domain and workstation fields, both empty.
        w.u16(0).u16(0).u32(0)
        w.u16(0).u16(0).u32(0)
        w.bytes(VERSION)
        negotiateMessage = w.toByteArray()
        return negotiateMessage
    }

    /** Message 3, answering the server's message 2. */
    fun authenticate(challenge: ByteArray): ByteArray {
        require(challenge.size >= CHALLENGE_MIN && challenge.slice(0, 8).contentEquals(SIGNATURE) &&
            challenge.u32(8) == TYPE_CHALLENGE) { "not an NTLM challenge" }
        challengeMessage = challenge
        flags = challenge.u32(20) and CLIENT_FLAGS
        val serverChallenge = challenge.slice(24, 8)
        val targetInfoLen = challenge.u16(40)
        val targetInfoOff = challenge.u32(44).toInt()
        val targetInfo = if (targetInfoLen > 0 && targetInfoOff + targetInfoLen <= challenge.size) {
            challenge.slice(targetInfoOff, targetInfoLen)
        } else {
            ByteArray(0)
        }
        val pairs = AvPairs.parse(targetInfo)
        val serverTime = pairs[AV_TIMESTAMP]
        // With a server timestamp the MIC is mandatory, and the flags pair has
        // to say one is there. Without one there is no MIC at all.
        val withMic = serverTime != null

        val responseKeyNt = SmbCrypto.hmacMd5(
            SmbCrypto.md4(utf16(password)),
            utf16(user.uppercase(Locale.ROOT) + domain),
        )
        val clientChallenge = fixedClientChallenge ?: ByteArray(8).also(random::nextBytes)
        val time = serverTime ?: ByteArray(8).also { it.putU64(0, nowFileTime()) }
        val serverName = AvPairs.encode(
            pairs.toMutableMap().apply {
                if (withMic) {
                    val existing = this[AV_FLAGS]?.u32(0) ?: 0L
                    put(AV_FLAGS, ByteArray(4).also { it.putU32(0, existing or AV_FLAG_MIC) })
                }
            },
        )
        val temp = LeWriter()
            .u8(1).u8(1).u16(0).u32(0)
            .bytes(time)
            .bytes(clientChallenge)
            .u32(0)
            .bytes(serverName)
            .u32(0)
            .toByteArray()
        val ntProof = SmbCrypto.hmacMd5(responseKeyNt, serverChallenge, temp)
        val ntResponse = ntProof + temp
        val lmResponse = if (withMic) {
            ByteArray(LM_RESPONSE_BYTES)
        } else {
            SmbCrypto.hmacMd5(responseKeyNt, serverChallenge, clientChallenge) + clientChallenge
        }
        val sessionBaseKey = SmbCrypto.hmacMd5(responseKeyNt, ntProof)

        // Key exchange: a fresh key of ours, sent wrapped in the one both sides
        // derived. Without the flag the derived key is the session key.
        val encryptedKey: ByteArray
        if (flags and FLAG_KEY_EXCH != 0L) {
            exportedSessionKey = fixedSessionKey ?: ByteArray(16).also(random::nextBytes)
            encryptedKey = SmbCrypto.rc4(sessionBaseKey, exportedSessionKey)
        } else {
            exportedSessionKey = sessionBaseKey
            encryptedKey = ByteArray(0)
        }

        val domainBytes = utf16(domain)
        val userBytes = utf16(user)
        val workstation = ByteArray(0)
        val payloadStart = AUTH_HEADER_BYTES
        val w = LeWriter()
        w.bytes(SIGNATURE).u32(TYPE_AUTHENTICATE)
        var offset = payloadStart
        // Six length/offset fields, in header order, then the flags.
        for (field in listOf(lmResponse, ntResponse, domainBytes, userBytes, workstation, encryptedKey)) {
            w.u16(field.size).u16(field.size).u32(offset)
            offset += field.size
        }
        w.u32(flags)
        w.bytes(VERSION)
        val micAt = w.size
        w.zeros(MIC_BYTES)
        check(w.size == payloadStart)
        w.bytes(lmResponse).bytes(ntResponse).bytes(domainBytes).bytes(userBytes).bytes(workstation).bytes(encryptedKey)
        val message = w.toByteArray()
        if (withMic) {
            val mic = SmbCrypto.hmacMd5(exportedSessionKey, negotiateMessage, challengeMessage, message)
            System.arraycopy(mic, 0, message, micAt, MIC_BYTES)
        }
        return message
    }

    /**
     * The NTLM signature over [message] with sequence number 0: what SPNEGO's
     * mechListMIC is. Client-to-server keys, extended session security.
     */
    fun sign(message: ByteArray): ByteArray {
        val signKey = SmbCrypto.md5(exportedSessionKey, CLIENT_SIGN_MAGIC)
        val seq = ByteArray(4)
        var checksum = SmbCrypto.hmacMd5(signKey, seq, message).copyOf(8)
        if (flags and FLAG_KEY_EXCH != 0L) {
            val sealKey = SmbCrypto.md5(exportedSessionKey, CLIENT_SEAL_MAGIC)
            checksum = SmbCrypto.rc4(sealKey, checksum)
        }
        return LeWriter().u32(1).bytes(checksum).bytes(seq).toByteArray()
    }

    /** Target info: a list of (id, value) ending in EOL. Order is kept. */
    internal object AvPairs {
        fun parse(b: ByteArray): Map<Int, ByteArray> {
            val out = LinkedHashMap<Int, ByteArray>()
            var at = 0
            while (at + 4 <= b.size) {
                val id = b.u16(at)
                val len = b.u16(at + 2)
                if (id == AV_EOL || at + 4 + len > b.size) break
                out[id] = b.slice(at + 4, len)
                at += 4 + len
            }
            return out
        }

        fun encode(pairs: Map<Int, ByteArray>): ByteArray {
            val w = LeWriter()
            for ((id, value) in pairs) w.u16(id).u16(value.size).bytes(value)
            w.u16(AV_EOL).u16(0)
            return w.toByteArray()
        }
    }

    companion object {
        val SIGNATURE = "NTLMSSP\u0000".toByteArray(Charsets.US_ASCII)
        private const val TYPE_NEGOTIATE = 1
        private const val TYPE_CHALLENGE = 2L
        private const val TYPE_AUTHENTICATE = 3

        const val FLAG_UNICODE = 0x00000001L
        const val FLAG_REQUEST_TARGET = 0x00000004L
        const val FLAG_SIGN = 0x00000010L
        const val FLAG_NTLM = 0x00000200L
        const val FLAG_ALWAYS_SIGN = 0x00008000L
        const val FLAG_EXTENDED_SESSION_SECURITY = 0x00080000L
        const val FLAG_TARGET_INFO = 0x00800000L
        const val FLAG_VERSION = 0x02000000L
        const val FLAG_128 = 0x20000000L
        const val FLAG_KEY_EXCH = 0x40000000L
        const val FLAG_56 = 0x80000000L

        val CLIENT_FLAGS = FLAG_UNICODE or FLAG_REQUEST_TARGET or FLAG_SIGN or FLAG_NTLM or
            FLAG_ALWAYS_SIGN or FLAG_EXTENDED_SESSION_SECURITY or FLAG_TARGET_INFO or
            FLAG_VERSION or FLAG_128 or FLAG_KEY_EXCH or FLAG_56

        /** Windows 10, build 19041, NTLM revision 15. Servers only log it. */
        private val VERSION = byteArrayOf(10, 0, 0x61, 0x4A, 0, 0, 0, 15)

        const val AV_EOL = 0
        const val AV_FLAGS = 6
        const val AV_TIMESTAMP = 7
        private const val AV_FLAG_MIC = 0x2L

        private const val CHALLENGE_MIN = 48
        private const val AUTH_HEADER_BYTES = 88
        private const val MIC_BYTES = 16
        private const val LM_RESPONSE_BYTES = 24

        private val CLIENT_SIGN_MAGIC =
            "session key to client-to-server signing key magic constant\u0000".toByteArray(Charsets.US_ASCII)
        private val CLIENT_SEAL_MAGIC =
            "session key to client-to-server sealing key magic constant\u0000".toByteArray(Charsets.US_ASCII)
    }
}
