package com.wasimaster.wmkeyboard.core.settings.sink.smb

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The SMB sink's cryptography against published vectors: MS-NLMP 4.2.4 for
 * NTLMv2, RFC 1320 for MD4 and RFC 4493 for AES-CMAC. A wrong byte anywhere in
 * these is a sign-in every server refuses, with no message saying why.
 */
class SmbCryptoTest {

    private fun hex(s: String): ByteArray = s.replace(" ", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test
    fun `md4 matches RFC 1320`() {
        assertArrayEquals(hex("31d6cfe0d16ae931b73c59d7e0c089c0"), SmbCrypto.md4(ByteArray(0)))
        assertArrayEquals(hex("a448017aaf21d8525fc10ae87aa6729d"), SmbCrypto.md4("abc".toByteArray()))
    }

    @Test
    fun `aes-cmac matches RFC 4493`() {
        val key = hex("2b7e151628aed2a6abf7158809cf4f3c")
        assertArrayEquals(hex("bb1d6929e95937287fa37d129b756746"), SmbCrypto.aesCmac(key, ByteArray(0)))
        assertArrayEquals(
            hex("070a16b46b4d4144f79bdd9dd04a287c"),
            SmbCrypto.aesCmac(key, hex("6bc1bee22e409f96e93d7e117393172a")),
        )
    }

    /** The CHALLENGE_MESSAGE of MS-NLMP 4.2.4.3, rebuilt from its parts. */
    private fun specChallenge(): ByteArray {
        val targetName = utf16("Server")
        val targetInfo = Ntlm.AvPairs.encode(linkedMapOf(2 to utf16("Domain"), 1 to utf16("Server")))
        val w = LeWriter()
        w.bytes(Ntlm.SIGNATURE).u32(2)
        w.u16(targetName.size).u16(targetName.size).u32(56)
        w.u32(0xe28a8233L)
        w.bytes(hex("0123456789abcdef")).zeros(8)
        w.u16(targetInfo.size).u16(targetInfo.size).u32(56 + targetName.size)
        w.bytes(byteArrayOf(6, 0, 0x70, 0x17, 0, 0, 0, 15))
        w.bytes(targetName).bytes(targetInfo)
        return w.toByteArray()
    }

    @Test
    fun `ntlmv2 authenticate matches MS-NLMP 4_2_4`() {
        val ntlm = Ntlm(
            user = "User",
            password = "Password",
            domain = "Domain",
            fixedClientChallenge = hex("aaaaaaaaaaaaaaaa"),
            fixedSessionKey = ByteArray(16) { 0x55 },
            nowFileTime = { 0L },
        )
        ntlm.negotiate()
        val auth = ntlm.authenticate(specChallenge())

        fun field(at: Int): ByteArray = auth.slice(auth.u32(at + 4).toInt(), auth.u16(at))
        val lm = field(12)
        val nt = field(20)
        val encryptedKey = field(52)

        // No timestamp in the target info, so an LMv2 response and no MIC.
        assertArrayEquals(hex("86c35097ac9cec102554764a57cccc19aaaaaaaaaaaaaaaa"), lm)
        assertArrayEquals(hex("68cd0ab851e51c96aabc927bebef6a1c"), nt.copyOf(16))
        assertArrayEquals(hex("c5dad2544fc9799094ce1ce90bc9d03e"), encryptedKey)
        assertArrayEquals(ByteArray(16) { 0x55 }, ntlm.exportedSessionKey)
        assertEquals("User", fromUtf16(auth, auth.u32(40).toInt(), auth.u16(36)))
    }

    @Test
    fun `a server timestamp turns on the MIC and blanks the LM response`() {
        val w = LeWriter()
        val info = Ntlm.AvPairs.encode(linkedMapOf(2 to utf16("D"), 7 to ByteArray(8) { 1 }))
        w.bytes(Ntlm.SIGNATURE).u32(2).u16(0).u16(0).u32(56).u32(Ntlm.CLIENT_FLAGS)
        w.bytes(ByteArray(8) { 9 }).zeros(8).u16(info.size).u16(info.size).u32(56).zeros(8).bytes(info)
        val ntlm = Ntlm("u", "p", "")
        ntlm.negotiate()
        val auth = ntlm.authenticate(w.toByteArray())
        val lm = auth.slice(auth.u32(16).toInt(), auth.u16(12))
        assertArrayEquals(ByteArray(24), lm)
        val mic = auth.slice(72, 16)
        assertTrue("MIC must be filled in", mic.any { it.toInt() != 0 })
        // The flags pair in the echoed target info says a MIC is present.
        val nt = auth.slice(auth.u32(24).toInt(), auth.u16(20))
        val pairs = Ntlm.AvPairs.parse(nt.copyOfRange(16 + 28, nt.size - 4))
        assertEquals(2L, pairs.getValue(Ntlm.AV_FLAGS).u32(0) and 2L)
    }

    @Test
    fun `spnego finds the NTLM token in a server answer`() {
        val token = byteArrayOf(1, 2, 3, 4, 5)
        val answer = Spnego.tlv(
            0xa1,
            Spnego.tlv(
                0x30,
                Spnego.tlv(0xa0, Spnego.tlv(0x0a, byteArrayOf(1))) +
                    Spnego.tlv(0xa2, Spnego.tlv(0x04, token)),
            ),
        )
        assertArrayEquals(token, Spnego.responseToken(answer))
        // A long token takes the multi-byte DER length.
        val big = ByteArray(300) { it.toByte() }
        assertArrayEquals(big, Spnego.responseToken(Spnego.tlv(0xa1, Spnego.tlv(0x30, Spnego.tlv(0xa2, Spnego.tlv(0x04, big))))))
        // A bare NTLM message is taken as it is.
        val bare = Ntlm.SIGNATURE + byteArrayOf(2, 0, 0, 0)
        assertArrayEquals(bare, Spnego.responseToken(bare))
    }

    @Test
    fun `seal and open round trip for both ciphers, and a flipped bit is refused`() {
        val key = ByteArray(16) { (it * 7).toByte() }
        val nonce = ByteArray(16) { 3 }
        val aad = ByteArray(32) { 5 }
        val plain = ByteArray(1000) { (it * 13).toByte() }
        for (cipher in SmbCrypto.Cipher.entries) {
            val sealed = SmbCrypto.seal(cipher, key, nonce, aad, plain)
            assertEquals(plain.size + 16, sealed.size)
            assertArrayEquals(plain, SmbCrypto.open(cipher, key, nonce, aad, sealed))
            sealed[10] = (sealed[10].toInt() xor 1).toByte()
            assertNotNull(runCatching { SmbCrypto.open(cipher, key, nonce, aad, sealed) }.exceptionOrNull())
        }
    }

    @Test
    fun `filetime converts both ways`() {
        val ms = 1_758_000_000_000L
        assertEquals(ms, FileTime.toMillis(FileTime.fromMillis(ms)))
        assertEquals(0L, FileTime.toMillis(0L))
    }
}
