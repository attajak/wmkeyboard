package com.wasimaster.wmkeyboard.core.stickers.signal

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The one part of the Signal import that cannot be wrong by a little: a key
 * derived a byte off opens nothing, and says nothing about why.
 *
 * So the derivation is pinned to the RFC's own vectors, the blob layout to a
 * round trip through an encryptor written here from the format's description,
 * and the two together to a real manifest as Signal's CDN serves it.
 */
class SignalStickerCryptoTest {

    private fun unhex(hex: String): ByteArray =
        ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    // ---- HKDF (RFC 5869, appendix A) -------------------------------------

    @Test
    fun `hkdf matches RFC 5869 test case 1`() {
        val okm = SignalStickerCrypto.hkdf(
            ikm = unhex("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b"),
            salt = unhex("000102030405060708090a0b0c"),
            info = unhex("f0f1f2f3f4f5f6f7f8f9"),
            length = 42,
        )
        assertEquals(
            "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865",
            hex(okm),
        )
    }

    @Test
    fun `hkdf with no salt is hkdf with a salt of zeros`() {
        // Test case 3, whose salt is empty. Signal passes 32 zero bytes, which
        // the RFC defines as the same thing, so both spellings must agree with
        // the vector.
        val ikm = unhex("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b")
        val expected = "8da4e775a563c18f715f802a063c5a31b8a11f5c5ee1879ec3454e5f3c738d2d9d201395faa4b61a96c8"
        assertEquals(expected, hex(SignalStickerCrypto.hkdf(ikm, ByteArray(0), ByteArray(0), 42)))
        assertEquals(expected, hex(SignalStickerCrypto.hkdf(ikm, ByteArray(32), ByteArray(0), 42)))
    }

    // ---- the blob ----------------------------------------------------------

    private val key = "17e971c134035622781d2ee249e6473b774583750b68c11bb82b7509c68b6dfd"

    /** Signal's sticker encryption, written from the format and not from the decryptor. */
    private fun encrypt(plain: ByteArray, packKey: String): ByteArray {
        val material = SignalStickerCrypto.hkdf(unhex(packKey), ByteArray(32), "Sticker Pack".toByteArray(), 64)
        val iv = ByteArray(16) { (it * 7).toByte() }
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(material.copyOfRange(0, 32), "AES"), IvParameterSpec(iv))
        }
        val body = iv + cipher.doFinal(plain)
        val mac = Mac.getInstance("HmacSHA256").apply {
            init(SecretKeySpec(material.copyOfRange(32, 64), "HmacSHA256"))
        }
        return body + mac.doFinal(body)
    }

    @Test
    fun `what was encrypted under a pack key opens under it`() {
        val plain = "a sticker, as far as this test is concerned".toByteArray()
        assertArrayEquals(plain, SignalStickerCrypto.decrypt(encrypt(plain, key), key))
    }

    @Test
    fun `a pack key in capitals is the same key`() {
        val plain = byteArrayOf(1, 2, 3)
        assertArrayEquals(plain, SignalStickerCrypto.decrypt(encrypt(plain, key), key.uppercase()))
    }

    @Test
    fun `one changed byte anywhere fails the check instead of decrypting`() {
        val blob = encrypt(ByteArray(100) { it.toByte() }, key)
        for (at in listOf(0, 20, blob.size - 1)) {
            val tampered = blob.copyOf().also { it[at] = (it[at] + 1).toByte() }
            assertNull("byte $at", SignalStickerCrypto.decrypt(tampered, key))
        }
    }

    @Test
    fun `another pack's key opens nothing`() {
        val other = key.replaceRange(0, 2, "ff")
        assertNull(SignalStickerCrypto.decrypt(encrypt(byteArrayOf(1, 2, 3), key), other))
    }

    @Test
    fun `a blob too short to hold an IV a block and a MAC is refused`() {
        assertNull(SignalStickerCrypto.decrypt(ByteArray(0), key))
        assertNull(SignalStickerCrypto.decrypt(ByteArray(63), key))
    }

    @Test
    fun `a key that is not 32 bytes of hex is refused`() {
        val blob = encrypt(byteArrayOf(1), key)
        assertNull(SignalStickerCrypto.decrypt(blob, key.dropLast(2)))
        assertNull(SignalStickerCrypto.decrypt(blob, key.replaceRange(0, 1, "g")))
        assertNull(SignalStickerCrypto.decrypt(blob, ""))
    }

    @Test
    fun `ids and keys are checked for length and normalised`() {
        assertEquals(
            "fb535407d2f6497ec074df8b9c51dd1d",
            SignalStickerCrypto.packIdOrNull(" FB535407D2F6497EC074DF8B9C51DD1D "),
        )
        assertNull(SignalStickerCrypto.packIdOrNull("fb535407"))
        assertNull(SignalStickerCrypto.packIdOrNull("zz535407d2f6497ec074df8b9c51dd1d"))
        assertEquals(key, SignalStickerCrypto.packKeyOrNull(key))
        assertNull(SignalStickerCrypto.packKeyOrNull("fb535407d2f6497ec074df8b9c51dd1d"))
    }

    // ---- the real thing ----------------------------------------------------

    /**
     * `stickers/fb535407d2f6497ec074df8b9c51dd1d/manifest.proto` as served on
     * 2026-09-21: "Zozo the French Bulldog", one of the packs Signal ships
     * with, whose id and key are in Signal's own source.
     */
    private val zozoManifest = Base64.getDecoder().decode(
        "FHTddYYla6jJbVb85nAs1CXBjohIZV+PtfydoS3gvXymUYE9ZjH0Zfp+oiwOFkhqgk44TozGRLye" +
            "pdv3t99/XMOXY5fCsXE0y91HoQXdz77U7Ii/3H+Seio+FYpyGqfYXDM7OXliThmbeGvnb4PZLY8P" +
            "XnnB2wSIM2XflWOnkouUvRM3o0XWITAM4Pt2yAkvi6Qd1Tf2NHFhap+6vce1xPKU5Rd/9iQC5mfd" +
            "Ub9QGG+0gI5giTvUGrt6yoO2jBfnbTbWRX6kVOuQVpEXNr9qQVgZTUel1bO2kmLW1Wzp87olyBtP" +
            "aPUbjW3LGrshKsC9FB0k+ZF5bG+cCzld02dZFywzJ8EVmA7nkCAn90EPZ1LmTbN84VIXYc+62LYa" +
            "kTVI3UVu9dFE/R6GRAAtRh3qcMCGnehm+sdAEcusUFeaYpP1B8JqArbgtlXTkdDO1AE7vkR0iLd/" +
            "qE/nnOgF6s5Mvw==",
    )

    @Test
    fun `a manifest from Signal's CDN opens and reads`() {
        val plain = SignalStickerCrypto.decrypt(zozoManifest, key)
        assertNotNull(plain)
        val manifest = SignalPackManifest.parse(plain!!)
        assertNotNull(manifest)
        assertEquals("Zozo the French Bulldog", manifest!!.title)
        assertEquals("Arrow Bowie", manifest.author)
        assertEquals(25, manifest.coverId)
        assertEquals(0, manifest.stickers.first().id)
        assertEquals("✨", manifest.stickers.first().emoji)
        assertEquals(manifest.stickers.size, manifest.stickers.map { it.id }.toSet().size)
    }
}
