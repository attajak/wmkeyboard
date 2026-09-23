package com.wasimaster.wmkeyboard.core.settings.sink.sftp

import com.jcraft.jsch.JSch

/**
 * The SSH algorithms an SFTP location offers, as JSch session config.
 *
 * JSch picks every algorithm class with `Class.forName`, from a name in this
 * table or its own defaults. That decides two things here:
 *
 * - Where JSch's default points at a JCE class Android does not have (X25519
 *   and Ed25519 need Java 11 and 15), the name is pointed at the Bouncy Castle
 *   version instead. Without that, curve25519, the post-quantum hybrids and
 *   ed25519 keys all silently drop out on a phone.
 * - R8 cannot see a class named in a string. `app/proguard-rules.pro` keeps
 *   exactly [reflectedClasses], and a test in `:app` checks the two agree, so
 *   turning on an algorithm here without keeping its class fails the build's
 *   tests rather than a user's backup in release.
 *
 * Modern first, and nothing OpenSSH has turned off unless [legacy] is set.
 */
object SftpAlgorithms {

    private val KEX = listOf(
        "mlkem768x25519-sha256",
        "sntrup761x25519-sha512",
        "sntrup761x25519-sha512@openssh.com",
        "curve25519-sha256",
        "curve25519-sha256@libssh.org",
        "ecdh-sha2-nistp256",
        "ecdh-sha2-nistp384",
        "ecdh-sha2-nistp521",
        "diffie-hellman-group-exchange-sha256",
        "diffie-hellman-group16-sha512",
        "diffie-hellman-group18-sha512",
        "diffie-hellman-group14-sha256",
    )
    private val KEX_LEGACY = listOf("diffie-hellman-group14-sha1", "diffie-hellman-group-exchange-sha1")

    private val HOST_KEYS = listOf(
        "ssh-ed25519",
        "ecdsa-sha2-nistp256",
        "ecdsa-sha2-nistp384",
        "ecdsa-sha2-nistp521",
        "rsa-sha2-512",
        "rsa-sha2-256",
    )
    private val HOST_KEYS_LEGACY = listOf("ssh-rsa")

    private val CIPHERS = listOf(
        "chacha20-poly1305@openssh.com",
        "aes128-gcm@openssh.com",
        "aes256-gcm@openssh.com",
        "aes128-ctr",
        "aes192-ctr",
        "aes256-ctr",
    )

    private val MACS = listOf(
        "hmac-sha2-256-etm@openssh.com",
        "hmac-sha2-512-etm@openssh.com",
        "hmac-sha2-256",
        "hmac-sha2-512",
    )
    private val MACS_LEGACY = listOf("hmac-sha1-etm@openssh.com", "hmac-sha1")

    /** Where JSch's default class is the JCE one Android lacks. */
    private val OVERRIDES = mapOf(
        "xdh" to "com.jcraft.jsch.bc.XDH",
        "ssh-ed25519" to "com.jcraft.jsch.bc.SignatureEd25519",
        "keypairgen.eddsa" to "com.jcraft.jsch.bc.KeyPairGenEdDSA",
    )

    /**
     * Names JSch resolves on the way to the ones above, and while it reads a
     * private key: hashes, the DH and ECDH engines, the random source, the
     * sign-in methods, and the ciphers and KDFs of encrypted key files.
     */
    private val INDIRECT = listOf(
        "xdh", "mlkem768", "sntrup761", "ecdh-sha2-nistp", "dh",
        "sha-1", "sha-256", "sha-384", "sha-512", "sha1", "sha256", "sha384", "sha512", "md5",
        "random", "none",
        "userauth.none", "userauth.password", "userauth.keyboard-interactive", "userauth.publickey",
        "keypairgen.rsa", "keypairgen.ecdsa", "keypairgen.eddsa", "keypairgen_fromprivate.eddsa",
        "pbkdf2", "bcrypt", "aes128-cbc", "aes192-cbc", "aes256-cbc", "3des-cbc",
    )

    /** The session config for one connection. */
    fun config(legacy: Boolean): Map<String, String> {
        fun list(modern: List<String>, old: List<String>) = (if (legacy) modern + old else modern).joinToString(",")
        return OVERRIDES + mapOf(
            "kex" to list(KEX, KEX_LEGACY),
            "server_host_key" to list(HOST_KEYS, HOST_KEYS_LEGACY),
            "PubkeyAcceptedAlgorithms" to list(HOST_KEYS, HOST_KEYS_LEGACY),
            "cipher.c2s" to CIPHERS.joinToString(","),
            "cipher.s2c" to CIPHERS.joinToString(","),
            "mac.c2s" to list(MACS, MACS_LEGACY),
            "mac.s2c" to list(MACS, MACS_LEGACY),
            "compression.c2s" to "none",
            "compression.s2c" to "none",
            // Kerberos is not on Android, and JSch would try it first.
            "PreferredAuthentications" to "publickey,keyboard-interactive,password",
            "enable_strict_kex" to "yes",
        )
    }

    /** Every class JSch can look up by name under [config] with legacy on. */
    fun reflectedClasses(): Set<String> {
        val names = KEX + KEX_LEGACY + HOST_KEYS + HOST_KEYS_LEGACY + CIPHERS + MACS + MACS_LEGACY + INDIRECT
        return names.mapNotNull { OVERRIDES[it] ?: JSch.getConfig(it) }.filter { it.startsWith("com.jcraft.") }.toSortedSet()
    }
}
