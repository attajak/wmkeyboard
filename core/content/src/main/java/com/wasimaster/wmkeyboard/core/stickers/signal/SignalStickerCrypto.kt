package com.wasimaster.wmkeyboard.core.stickers.signal

import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Opens what Signal's sticker CDN serves.
 *
 * A pack is public to anyone who holds its link, and the link is the key: the
 * manifest and every sticker are stored encrypted under keys derived from the
 * 32-byte `pack_key` the link carries, so the server never sees a picture.
 *
 * ```
 * blob      = IV(16) ‖ AES-256-CBC/PKCS5(plaintext) ‖ HMAC-SHA256(IV ‖ ciphertext)
 * key bytes = HKDF-SHA256(ikm = pack_key, salt = 32 zero bytes, info = "Sticker Pack", 64)
 *             first 32 = AES key, last 32 = MAC key
 * ```
 *
 * The same construction as Signal's `AttachmentCipherInputStream.createForStickerData`.
 * The MAC is checked before a single block is decrypted, and a blob that fails
 * it comes back as null rather than as an exception: from here a wrong key, a
 * truncated download and a tampered file are all the same answer.
 */
object SignalStickerCrypto {

    /** Hex characters in a `pack_id`: 16 bytes. */
    const val PACK_ID_LENGTH = 32

    /** Hex characters in a `pack_key`: 32 bytes. */
    const val PACK_KEY_LENGTH = 64

    private const val BLOCK = 16
    private const val KEY = 32
    private const val MAC = 32
    private const val HMAC = "HmacSHA256"
    private val INFO = "Sticker Pack".toByteArray(Charsets.US_ASCII)

    /** [value] lowercased when it is exactly [length] hex characters, else null. */
    fun hexOrNull(value: String, length: Int): String? {
        val text = value.trim().lowercase()
        if (text.length != length) return null
        return text.takeIf { hex -> hex.all { it in '0'..'9' || it in 'a'..'f' } }
    }

    fun packIdOrNull(value: String): String? = hexOrNull(value, PACK_ID_LENGTH)

    fun packKeyOrNull(value: String): String? = hexOrNull(value, PACK_KEY_LENGTH)

    /**
     * The plaintext of [blob], or null when [packKeyHex] is not a pack key or
     * the blob does not verify under it.
     */
    fun decrypt(blob: ByteArray, packKeyHex: String): ByteArray? {
        val key = packKeyOrNull(packKeyHex)?.let(::unhex) ?: return null
        // An IV, at least one block, and the MAC.
        if (blob.size < BLOCK + BLOCK + MAC) return null
        val material = hkdf(key, ByteArray(KEY), INFO, KEY + MAC)
        val aesKey = material.copyOfRange(0, KEY)
        val macKey = material.copyOfRange(KEY, KEY + MAC)

        val signedUntil = blob.size - MAC
        val mac = Mac.getInstance(HMAC).apply { init(SecretKeySpec(macKey, HMAC)) }
        mac.update(blob, 0, signedUntil)
        val theirs = blob.copyOfRange(signedUntil, blob.size)
        if (!MessageDigest.isEqual(mac.doFinal(), theirs)) return null

        return runCatching {
            Cipher.getInstance("AES/CBC/PKCS5Padding").run {
                init(Cipher.DECRYPT_MODE, SecretKeySpec(aesKey, "AES"), IvParameterSpec(blob, 0, BLOCK))
                doFinal(blob, BLOCK, signedUntil - BLOCK)
            }
        }.getOrNull()
    }

    /** RFC 5869 with SHA-256. An empty [salt] is the same as 32 zero bytes. */
    fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        val prk = hmac(if (salt.isEmpty()) ByteArray(KEY) else salt, ikm)
        val out = ByteArray(length)
        var previous = ByteArray(0)
        var written = 0
        var counter = 1
        while (written < length) {
            previous = hmac(prk, previous + info + counter.toByte())
            val take = minOf(previous.size, length - written)
            previous.copyInto(out, written, 0, take)
            written += take
            counter++
        }
        return out
    }

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance(HMAC).run {
            init(SecretKeySpec(key, HMAC))
            doFinal(data)
        }

    private fun unhex(hex: String): ByteArray =
        ByteArray(hex.length / 2) { i ->
            ((Character.digit(hex[i * 2], HEX_RADIX) shl 4) or Character.digit(hex[i * 2 + 1], HEX_RADIX)).toByte()
        }

    private const val HEX_RADIX = 16
}
