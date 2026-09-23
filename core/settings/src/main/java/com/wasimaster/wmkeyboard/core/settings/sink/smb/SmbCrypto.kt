package com.wasimaster.wmkeyboard.core.settings.sink.smb

import org.bouncycastle.crypto.digests.MD4Digest
import org.bouncycastle.crypto.digests.MD5Digest
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.digests.SHA512Digest
import org.bouncycastle.crypto.engines.AESEngine
import org.bouncycastle.crypto.engines.RC4Engine
import org.bouncycastle.crypto.macs.CMac
import org.bouncycastle.crypto.macs.HMac
import org.bouncycastle.crypto.modes.AEADBlockCipher
import org.bouncycastle.crypto.modes.CCMBlockCipher
import org.bouncycastle.crypto.modes.GCMBlockCipher
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.KeyParameter

/**
 * Every primitive SMB and NTLM need, from Bouncy Castle's lightweight API.
 *
 * Not the platform's JCE, on purpose: Android has no MD4 (the NT password
 * hash), no AES-CMAC (SMB 3 signing) and no AES-CCM (SMB 3.0 encryption), and
 * using one library for all of them keeps the unit tests on a plain JVM
 * identical to what runs on a phone. Nothing here registers a provider.
 */
internal object SmbCrypto {

    fun md4(data: ByteArray): ByteArray = digest(MD4Digest(), data)

    fun md5(vararg parts: ByteArray): ByteArray = digest(MD5Digest(), *parts)

    fun sha512(vararg parts: ByteArray): ByteArray = digest(SHA512Digest(), *parts)

    fun hmacMd5(key: ByteArray, vararg parts: ByteArray): ByteArray = hmac(HMac(MD5Digest()), key, *parts)

    fun hmacSha256(key: ByteArray, vararg parts: ByteArray): ByteArray = hmac(HMac(SHA256Digest()), key, *parts)

    /** AES-128-CMAC, RFC 4493: the SMB 3.x signature. */
    fun aesCmac(key: ByteArray, data: ByteArray): ByteArray {
        val mac = CMac(AESEngine.newInstance())
        mac.init(KeyParameter(key))
        mac.update(data, 0, data.size)
        return ByteArray(mac.macSize).also { mac.doFinal(it, 0) }
    }

    /**
     * A running RC4 keystream. NTLM keeps one per direction and never restarts
     * it, so this is an object with state rather than a function.
     */
    class Rc4(key: ByteArray) {
        private val engine = RC4Engine().apply { init(true, KeyParameter(key)) }

        fun apply(data: ByteArray): ByteArray =
            ByteArray(data.size).also { engine.processBytes(data, 0, data.size, it, 0) }
    }

    fun rc4(key: ByteArray, data: ByteArray): ByteArray = Rc4(key).apply(data)

    /**
     * SP 800-108 in counter mode over HMAC-SHA256, one round, 128 bits out:
     * the only shape SMB 3 uses. [label] and [context] are passed with their
     * NUL terminators already on, exactly as MS-SMB2 writes them.
     */
    fun kdf(key: ByteArray, label: ByteArray, context: ByteArray): ByteArray {
        val counter = byteArrayOf(0, 0, 0, 1)
        val separator = byteArrayOf(0)
        val length = byteArrayOf(0, 0, 0, KEY_BITS.toByte())
        return hmacSha256(key, counter, label, separator, context, length).copyOf(KEY_BYTES)
    }

    /** Which AEAD cipher a session negotiated, by its MS-SMB2 id. */
    enum class Cipher(val id: Int, val nonceBytes: Int) {
        AES_128_CCM(1, 11),
        AES_128_GCM(2, 12),
        ;

        fun newAead(): AEADBlockCipher = when (this) {
            AES_128_CCM -> CCMBlockCipher.newInstance(AESEngine.newInstance())
            AES_128_GCM -> GCMBlockCipher.newInstance(AESEngine.newInstance())
        }

        companion object {
            fun of(id: Int): Cipher? = entries.firstOrNull { it.id == id }
        }
    }

    /** Encrypts [plain] and returns ciphertext followed by the 16-byte tag. */
    fun seal(cipher: Cipher, key: ByteArray, nonce: ByteArray, aad: ByteArray, plain: ByteArray): ByteArray {
        val aead = cipher.newAead()
        aead.init(true, AEADParameters(KeyParameter(key), TAG_BITS, nonce.copyOf(cipher.nonceBytes), aad))
        val out = ByteArray(aead.getOutputSize(plain.size))
        val n = aead.processBytes(plain, 0, plain.size, out, 0)
        aead.doFinal(out, n)
        return out
    }

    /** The reverse of [seal]. Throws when the tag does not match. */
    fun open(cipher: Cipher, key: ByteArray, nonce: ByteArray, aad: ByteArray, sealed: ByteArray): ByteArray {
        val aead = cipher.newAead()
        aead.init(false, AEADParameters(KeyParameter(key), TAG_BITS, nonce.copyOf(cipher.nonceBytes), aad))
        val out = ByteArray(aead.getOutputSize(sealed.size))
        val n = aead.processBytes(sealed, 0, sealed.size, out, 0)
        aead.doFinal(out, n)
        return out
    }

    private fun digest(d: org.bouncycastle.crypto.Digest, vararg parts: ByteArray): ByteArray {
        for (p in parts) d.update(p, 0, p.size)
        return ByteArray(d.digestSize).also { d.doFinal(it, 0) }
    }

    private fun hmac(mac: HMac, key: ByteArray, vararg parts: ByteArray): ByteArray {
        mac.init(KeyParameter(key))
        for (p in parts) mac.update(p, 0, p.size)
        return ByteArray(mac.macSize).also { mac.doFinal(it, 0) }
    }

    /** Constant time, so a signature check does not leak how much of it matched. */
    fun equal(a: ByteArray, b: ByteArray): Boolean = java.security.MessageDigest.isEqual(a, b)

    const val KEY_BYTES = 16
    private const val KEY_BITS = 128
    private const val TAG_BITS = 128
}
