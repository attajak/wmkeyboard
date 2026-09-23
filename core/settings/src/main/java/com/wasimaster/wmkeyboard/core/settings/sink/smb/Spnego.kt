package com.wasimaster.wmkeyboard.core.settings.sink.smb

import java.io.ByteArrayOutputStream

/**
 * The SPNEGO wrapping SMB puts around NTLM (RFC 4178), with NTLM as the only
 * mechanism offered. Just enough DER to write the two client tokens and to
 * find the server's token in its answer.
 */
internal object Spnego {

    /** 1.3.6.1.5.5.2, SPNEGO itself. */
    private val SPNEGO_OID = byteArrayOf(0x2b, 0x06, 0x01, 0x05, 0x05, 0x02)

    /** 1.3.6.1.4.1.311.2.2.10, NTLMSSP. */
    private val NTLM_OID = byteArrayOf(0x2b, 0x06, 0x01, 0x04, 0x01, 0x82.toByte(), 0x37, 0x02, 0x02, 0x0a)

    /**
     * The DER of the MechTypeList, which the mechListMIC signs. Kept as its own
     * value because it must be byte for byte what [init] sent.
     */
    val mechTypeList: ByteArray = tlv(0x30, tlv(0x06, NTLM_OID))

    /** The first token: NegTokenInit, offering NTLM, carrying NTLM's message 1. */
    fun init(ntlmNegotiate: ByteArray): ByteArray {
        val negTokenInit = tlv(
            0x30,
            tlv(0xa0, mechTypeList) + tlv(0xa2, tlv(0x04, ntlmNegotiate)),
        )
        return tlv(0x60, tlv(0x06, SPNEGO_OID) + tlv(0xa0, negTokenInit))
    }

    /** The second token: NegTokenResp with NTLM's message 3 and the MIC. */
    fun response(ntlmAuthenticate: ByteArray, mechListMic: ByteArray?): ByteArray {
        var body = tlv(0xa2, tlv(0x04, ntlmAuthenticate))
        if (mechListMic != null) body += tlv(0xa3, tlv(0x04, mechListMic))
        return tlv(0xa1, tlv(0x30, body))
    }

    /**
     * The mechanism token inside a server NegTokenResp, or the input itself when
     * the server answered with a bare NTLM message, which some do.
     */
    fun responseToken(token: ByteArray): ByteArray? {
        if (token.size >= 8 && token.copyOf(8).contentEquals(Ntlm.SIGNATURE)) return token
        val outer = read(token, 0) ?: return null
        if (outer.tag != 0xa1) return null
        val seq = read(token, outer.start) ?: return null
        if (seq.tag != 0x30) return null
        var at = seq.start
        while (at < seq.end) {
            val field = read(token, at) ?: return null
            if (field.tag == 0xa2) {
                val octets = read(token, field.start) ?: return null
                if (octets.tag != 0x04) return null
                return token.copyOfRange(octets.start, octets.end)
            }
            at = field.end
        }
        return null
    }

    /** A decoded tag, and where its contents start and end. */
    private data class Tlv(val tag: Int, val start: Int, val end: Int)

    private fun read(b: ByteArray, at: Int): Tlv? {
        if (at + 2 > b.size) return null
        val tag = b.u8(at)
        var len = b.u8(at + 1)
        var start = at + 2
        if (len and 0x80 != 0) {
            val n = len and 0x7f
            if (n == 0 || n > 3 || start + n > b.size) return null
            len = 0
            repeat(n) { len = (len shl 8) or b.u8(start++) }
        }
        if (start + len > b.size) return null
        return Tlv(tag, start, start + len)
    }

    fun tlv(tag: Int, content: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(content.size + 6)
        out.write(tag)
        val n = content.size
        when {
            n < 0x80 -> out.write(n)
            n < 0x100 -> {
                out.write(0x81)
                out.write(n)
            }
            n < 0x10000 -> {
                out.write(0x82)
                out.write(n ushr 8)
                out.write(n and 0xff)
            }
            else -> {
                out.write(0x83)
                out.write(n ushr 16)
                out.write((n ushr 8) and 0xff)
                out.write(n and 0xff)
            }
        }
        out.write(content, 0, n)
        return out.toByteArray()
    }
}
