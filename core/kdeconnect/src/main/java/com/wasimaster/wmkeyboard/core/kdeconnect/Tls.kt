package com.wasimaster.wmkeyboard.core.kdeconnect

import java.net.Socket
import java.security.Principal
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509ExtendedTrustManager

/**
 * TLS the way KDE Connect uses it, which is not the way the web does.
 *
 * - **Every certificate is self-signed.** There is no authority and no chain;
 *   a device *is* its certificate.
 * - **Trust is on first use, then pinned.** An unpaired peer may present
 *   anything — what makes it safe to pair with is the verification code both
 *   screens show, computed from the two public keys. Once paired, a connection
 *   claiming that device id must present exactly the certificate that was
 *   stored, byte for byte.
 * - **Both sides always present a certificate**, because the certificate is
 *   the credential: a TLS server here always demands client auth.
 * - **Either end of a TCP connection may be the TLS server** (see
 *   [LanTransport]), so [wrap] takes the role as a parameter.
 *
 * TLS 1.2 by default because that is where kdeconnect-android pins itself
 * ("1.3 seems to cause issues in some devices"). `InteropTest` passes under 1.3
 * as well, in both roles and on payload sockets, against kdeconnect-kde 26.08 —
 * but that is the JDK's TLS stack, not Conscrypt on a phone, and the one
 * combination known to work from Android against every desktop is the one the
 * reference client ships. [protocols] is a parameter so that can change in one
 * place once it has been seen working on a device.
 */
class KdeTls(
    private val identity: KdeLocalIdentity,
    private val protocols: List<String> = DEFAULT_PROTOCOLS,
) {
    private val random = SecureRandom()
    private val keyManager = FixedKeyManager(identity.keys.private, identity.certificate)

    /**
     * Upgrades the connected [socket] and completes the handshake.
     *
     * @param clientMode our TLS role on this socket
     * @param pinned the certificate the peer must present, or null to accept
     *   whatever it shows (an unpaired device)
     * @throws java.io.IOException when the handshake fails, which includes a
     *   paired device showing the wrong certificate
     */
    fun wrap(socket: Socket, clientMode: Boolean, pinned: X509Certificate?, handshakeTimeoutMs: Int = 10_000): SSLSocket {
        val context = SSLContext.getInstance("TLS")
        context.init(arrayOf(keyManager), arrayOf(PinningTrustManager(pinned)), random)
        val ssl = context.socketFactory.createSocket(
            socket,
            socket.inetAddress.hostAddress,
            socket.port,
            true,
        ) as SSLSocket
        val wanted = protocols.filter { it in ssl.supportedProtocols }
        if (wanted.isNotEmpty()) ssl.enabledProtocols = wanted.toTypedArray()
        ssl.useClientMode = clientMode
        if (!clientMode) ssl.needClientAuth = true
        ssl.soTimeout = handshakeTimeoutMs
        ssl.startHandshake()
        return ssl
    }

    /** The certificate [socket]'s peer presented, or null if it somehow presented none. */
    fun peerCertificate(socket: SSLSocket): X509Certificate? =
        runCatching { socket.session.peerCertificates.firstOrNull() as? X509Certificate }.getOrNull()

    /**
     * Always answers with our one key. The stock key manager filters by the
     * issuers the server lists, and a self-signed certificate's issuer is only
     * on that list when the other side happens to have put it there — which
     * differs between Qt, GnuTLS and Conscrypt.
     */
    private class FixedKeyManager(
        private val key: PrivateKey,
        private val certificate: X509Certificate,
    ) : X509ExtendedKeyManager() {
        private fun matches(keyType: String?): Boolean =
            keyType != null && keyType.startsWith(key.algorithm, ignoreCase = true)

        override fun chooseClientAlias(keyTypes: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?): String? =
            if (keyTypes == null || keyTypes.any(::matches)) ALIAS else null

        override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?): String? =
            if (matches(keyType)) ALIAS else null

        override fun chooseEngineClientAlias(keyTypes: Array<out String>?, issuers: Array<out Principal>?, engine: SSLEngine?): String? =
            chooseClientAlias(keyTypes, issuers, null)

        override fun chooseEngineServerAlias(keyType: String?, issuers: Array<out Principal>?, engine: SSLEngine?): String? =
            chooseServerAlias(keyType, issuers, null)

        override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? =
            if (matches(keyType)) arrayOf(ALIAS) else null

        override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? =
            if (matches(keyType)) arrayOf(ALIAS) else null

        override fun getCertificateChain(alias: String?): Array<X509Certificate>? =
            if (alias == ALIAS) arrayOf(certificate) else null

        override fun getPrivateKey(alias: String?): PrivateKey? = if (alias == ALIAS) key else null
    }

    /**
     * Accept anything, or accept exactly one certificate. An *extended* trust
     * manager so that neither JSSE nor Conscrypt wraps it in their own, which
     * would add hostname and algorithm-constraint checks that mean nothing for
     * a certificate whose only name is a device id.
     */
    private class PinningTrustManager(private val pinned: X509Certificate?) : X509ExtendedTrustManager() {
        private fun check(chain: Array<out X509Certificate>?) {
            val presented = chain?.firstOrNull() ?: throw CertificateException("no certificate presented")
            val expected = pinned ?: return
            if (!presented.encoded.contentEquals(expected.encoded)) {
                throw CertificateException("certificate does not match the paired device")
            }
        }

        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = check(chain)
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = check(chain)
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) = check(chain)
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) = check(chain)
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) = check(chain)
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) = check(chain)
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    companion object {
        private const val ALIAS = "kdeconnect"
        val DEFAULT_PROTOCOLS = listOf("TLSv1.2")
    }
}
