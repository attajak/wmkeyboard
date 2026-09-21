package com.wasimaster.wmkeyboard.core.kdeconnect

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * The self-signed certificate a KDE Connect device identifies itself with,
 * written out in DER by hand.
 *
 * Every other client reaches for BouncyCastle or OpenSSL here. The platform has
 * no X.509 *builder*, only a parser, and a certificate this plain — version 3,
 * no extensions, one RDN sequence used twice — is about a hundred lines of DER,
 * which is a far smaller thing to own than a 1.5 MB dependency in a keyboard.
 *
 * What the peers require of it, all of which is pinned by `DerCertificateTest`:
 * - the common name is the device id (KDE compares them and drops the link on a
 *   mismatch);
 * - the self-signature really verifies (Valent checks it, even though nobody
 *   validates a chain);
 * - an EC P-256 key, signed `SHA512withECDSA`, which is what kdeconnect-android
 *   and kdeconnect-kde both mint today.
 */
object DerCertificate {

    private const val ORGANIZATION = "KDE"
    private const val UNIT = "KDE Connect"
    private const val SIGNATURE_ALGORITHM = "SHA512withECDSA"

    // 1.2.840.10045.4.3.4 — ecdsa-with-SHA512
    private val OID_ECDSA_SHA512 = byteArrayOf(0x2A, 0x86.toByte(), 0x48, 0xCE.toByte(), 0x3D, 0x04, 0x03, 0x04)
    private val OID_COMMON_NAME = byteArrayOf(0x55, 0x04, 0x03)
    private val OID_ORGANIZATION = byteArrayOf(0x55, 0x04, 0x0A)
    private val OID_ORGANIZATIONAL_UNIT = byteArrayOf(0x55, 0x04, 0x0B)

    fun generateKeyPair(random: SecureRandom = SecureRandom()): KeyPair =
        KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"), random)
        }.generateKeyPair()

    /**
     * A certificate for [deviceId] over [keys], valid from a year ago (clocks on
     * phones and desktops disagree, and a certificate that is "not yet valid"
     * on the other side ends the handshake) until ten years from now.
     */
    fun selfSigned(
        deviceId: String,
        keys: KeyPair,
        now: Date = Date(),
        random: SecureRandom = SecureRandom(),
    ): X509Certificate {
        val name = sequence(
            rdn(OID_COMMON_NAME, deviceId),
            rdn(OID_ORGANIZATIONAL_UNIT, UNIT),
            rdn(OID_ORGANIZATION, ORGANIZATION),
        )
        val algorithm = sequence(tlv(TAG_OID, OID_ECDSA_SHA512))
        // 159 bits keeps the INTEGER positive and within RFC 5280's 20 octets.
        val serial = BigInteger(159, random).max(BigInteger.ONE)
        val tbs = sequence(
            tlv(TAG_CONTEXT_0, tlv(TAG_INTEGER, byteArrayOf(2))),
            tlv(TAG_INTEGER, serial.toByteArray()),
            algorithm,
            name,
            sequence(time(shiftYears(now, -1)), time(shiftYears(now, 10))),
            name,
            // Already a DER SubjectPublicKeyInfo.
            keys.public.encoded,
        )
        val signature = Signature.getInstance(SIGNATURE_ALGORITHM).run {
            initSign(keys.private, random)
            update(tbs)
            sign()
        }
        val der = sequence(tbs, algorithm, tlv(TAG_BIT_STRING, byteArrayOf(0) + signature))
        return parse(der)
    }

    fun parse(der: ByteArray): X509Certificate =
        CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(der)) as X509Certificate

    /**
     * The common name of [certificate]'s subject, or null. Read off the RFC 2253
     * string because `javax.naming` does not exist on Android; device ids are
     * alphanumerics, hyphens and underscores, so no escaping can be involved
     * in a name worth accepting.
     */
    fun commonName(certificate: X509Certificate): String? =
        certificate.subjectX500Principal.name
            .split(',')
            .map { it.trim() }
            .firstOrNull { it.startsWith("CN=", ignoreCase = true) }
            ?.substring(3)
            ?.takeIf { it.isNotEmpty() }

    /** `AA:BB:…` SHA-256 of the certificate, the form the desktop clients show. */
    fun fingerprint(certificate: X509Certificate): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(certificate.encoded)
            .joinToString(":") { "%02X".format(it) }

    private const val TAG_INTEGER = 0x02
    private const val TAG_BIT_STRING = 0x03
    private const val TAG_OID = 0x06
    private const val TAG_UTF8_STRING = 0x0C
    private const val TAG_UTC_TIME = 0x17
    private const val TAG_GENERALIZED_TIME = 0x18
    private const val TAG_SEQUENCE = 0x30
    private const val TAG_SET = 0x31
    private const val TAG_CONTEXT_0 = 0xA0

    private fun rdn(oid: ByteArray, value: String): ByteArray =
        tlv(TAG_SET, sequence(tlv(TAG_OID, oid), tlv(TAG_UTF8_STRING, value.toByteArray(Charsets.UTF_8))))

    private fun sequence(vararg parts: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        for (part in parts) out.write(part)
        return tlv(TAG_SEQUENCE, out.toByteArray())
    }

    private fun tlv(tag: Int, content: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(content.size + 6)
        out.write(tag)
        val length = content.size
        if (length < 0x80) {
            out.write(length)
        } else {
            val bytes = BigInteger.valueOf(length.toLong()).toByteArray().dropWhile { it == 0.toByte() }
            out.write(0x80 or bytes.size)
            for (b in bytes) out.write(b.toInt())
        }
        out.write(content)
        return out.toByteArray()
    }

    /** UTCTime through 2049 and GeneralizedTime after, as RFC 5280 requires. */
    private fun time(date: Date): ByteArray {
        val utc = TimeZone.getTimeZone("UTC")
        val year = Calendar.getInstance(utc).apply { time = date }.get(Calendar.YEAR)
        val (tag, pattern) = if (year < 2050) TAG_UTC_TIME to "yyMMddHHmmss'Z'" else TAG_GENERALIZED_TIME to "yyyyMMddHHmmss'Z'"
        val text = SimpleDateFormat(pattern, Locale.US).apply { timeZone = utc }.format(date)
        return tlv(tag, text.toByteArray(Charsets.US_ASCII))
    }

    private fun shiftYears(date: Date, years: Int): Date =
        Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            time = date
            add(Calendar.YEAR, years)
        }.time
}
