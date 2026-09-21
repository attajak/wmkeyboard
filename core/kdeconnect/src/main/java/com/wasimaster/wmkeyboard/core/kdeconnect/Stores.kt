package com.wasimaster.wmkeyboard.core.kdeconnect

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.io.File
import java.security.KeyFactory
import java.security.KeyPair
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date

/** This device on the network: its id, its key pair, and the certificate over both. */
class KdeLocalIdentity(val deviceId: String, val keys: KeyPair, val certificate: X509Certificate)

/**
 * Where [KdeLocalIdentity] lives between runs: `key.pk8` and `cert.der` under
 * [dir]. The device id is not stored on its own — it *is* the certificate's
 * common name, the way kdeconnect-kde keeps it, so the two cannot drift apart.
 *
 * A null [dir] is the keyboard before its first unlock (or a unit test): the
 * identity is made fresh and kept in memory only.
 *
 * **Replacing the certificate unpairs everything**, on both sides — the peers
 * pin it. So it is regenerated only when it is missing, unreadable, outside its
 * validity window, or names an id no peer would accept, and the caller is told
 * ([Loaded.created]) so it can clear the trust store that no longer applies.
 */
class KdeIdentityStore(private val dir: File?) {

    class Loaded(val identity: KdeLocalIdentity, val created: Boolean)

    fun loadOrCreate(now: Date = Date()): Loaded {
        load(now)?.let { return Loaded(it, created = false) }
        val keys = DerCertificate.generateKeyPair()
        val deviceId = KdeDeviceIds.generate()
        val certificate = DerCertificate.selfSigned(deviceId, keys, now)
        val identity = KdeLocalIdentity(deviceId, keys, certificate)
        dir?.let {
            runCatching {
                it.mkdirs()
                writeAtomically(File(it, KEY_FILE), keys.private.encoded)
                writeAtomically(File(it, CERT_FILE), certificate.encoded)
            }
        }
        return Loaded(identity, created = true)
    }

    private fun load(now: Date): KdeLocalIdentity? {
        val folder = dir ?: return null
        return runCatching {
            val keyBytes = File(folder, KEY_FILE).takeIf { it.isFile }?.readBytes() ?: return null
            val certBytes = File(folder, CERT_FILE).takeIf { it.isFile }?.readBytes() ?: return null
            val certificate = DerCertificate.parse(certBytes)
            if (now.before(certificate.notBefore) || now.after(certificate.notAfter)) return null
            val deviceId = DerCertificate.commonName(certificate)?.takeIf(KdeDeviceIds::isValid) ?: return null
            val private = KeyFactory.getInstance(certificate.publicKey.algorithm)
                .generatePrivate(PKCS8EncodedKeySpec(keyBytes))
            KdeLocalIdentity(deviceId, KeyPair(certificate.publicKey, private), certificate)
        }.getOrNull()
    }

    companion object {
        const val KEY_FILE = "key.pk8"
        const val CERT_FILE = "cert.der"
    }
}

/**
 * A device the user paired, and the certificate it presented when they did.
 * That certificate is the whole of the trust: every later connection from this
 * id must present exactly these bytes.
 */
@Serializable
data class KdeTrustedDevice(
    val id: String,
    val name: String,
    val type: String = KdeDeviceType.DESKTOP.wire,
    /** The DER certificate in hex. (`java.util.Base64` needs API 26; this module's floor is 24.) */
    val certificate: String,
    /**
     * The highest protocol version this device has been seen speaking. A later
     * connection claiming an older one is refused: the downgrade would strip
     * the protections version 8 added, and both reference clients refuse it too.
     */
    val protocolVersion: Int = KDE_PROTOCOL_VERSION,
    val lastAddress: String = "",
    val lastSeenMs: Long = 0,
    /** Plugin keys the user switched off for this device only ([KdePluginKeys]). */
    val disabled: Set<String> = emptySet(),
) {
    fun x509(): X509Certificate? =
        runCatching { DerCertificate.parse(certificate.hexToBytes()) }.getOrNull()
}

/** The paired devices, in `devices.json` under [dir]; in memory only when [dir] is null. */
class KdeTrustStore(private val dir: File?) {

    private val lock = Any()
    private var devices: Map<String, KdeTrustedDevice> = load()

    fun all(): List<KdeTrustedDevice> = synchronized(lock) { devices.values.sortedBy { it.name.lowercase() } }

    fun get(id: String): KdeTrustedDevice? = synchronized(lock) { devices[id] }

    fun isTrusted(id: String): Boolean = synchronized(lock) { id in devices }

    fun put(device: KdeTrustedDevice) = synchronized(lock) {
        devices = devices + (device.id to device)
        save()
    }

    fun update(id: String, change: (KdeTrustedDevice) -> KdeTrustedDevice) = synchronized(lock) {
        val current = devices[id] ?: return@synchronized
        val next = change(current)
        if (next != current) {
            devices = devices + (id to next)
            save()
        }
    }

    fun remove(id: String) = synchronized(lock) {
        if (id in devices) {
            devices = devices - id
            save()
        }
    }

    fun clear() = synchronized(lock) {
        if (devices.isNotEmpty()) {
            devices = emptyMap()
            save()
        }
    }

    private fun load(): Map<String, KdeTrustedDevice> {
        val file = dir?.let { File(it, FILE) }?.takeIf { it.isFile } ?: return emptyMap()
        return runCatching {
            WireJson.decodeFromString(ListSerializer(KdeTrustedDevice.serializer()), file.readText())
                .filter { KdeDeviceIds.isValid(it.id) && it.x509() != null }
                .associateBy { it.id }
        }.getOrDefault(emptyMap())
    }

    private fun save() {
        val folder = dir ?: return
        runCatching {
            folder.mkdirs()
            val text = WireJson.encodeToString(ListSerializer(KdeTrustedDevice.serializer()), devices.values.toList())
            writeAtomically(File(folder, FILE), text.toByteArray(Charsets.UTF_8))
        }
    }

    companion object {
        const val FILE = "devices.json"

        fun encode(certificate: X509Certificate): String = certificate.encoded.toHex()
    }
}

/** The per-device switches a user can turn off; stored in [KdeTrustedDevice.disabled]. */
object KdePluginKeys {
    const val CLIPBOARD = "clipboard"
    const val REMOTE_TYPING = "remote_typing"
    const val MEDIA = "media"
    const val SHARE = "share"
    const val BATTERY = "battery"
}

/** Write-then-rename, so a crash mid-write cannot leave half a private key behind. */
internal fun writeAtomically(target: File, bytes: ByteArray) {
    val tmp = File(target.parentFile, target.name + ".tmp")
    tmp.writeBytes(bytes)
    if (!tmp.renameTo(target)) {
        target.delete()
        if (!tmp.renameTo(target)) {
            target.writeBytes(bytes)
            tmp.delete()
        }
    }
}

internal fun ByteArray.toHex(): String {
    val out = StringBuilder(size * 2)
    for (b in this) {
        val v = b.toInt() and 0xFF
        out.append(HEX[v ushr 4]).append(HEX[v and 0x0F])
    }
    return out.toString()
}

internal fun String.hexToBytes(): ByteArray {
    require(length % 2 == 0) { "odd hex length" }
    return ByteArray(length / 2) { i ->
        ((Character.digit(this[i * 2], 16) shl 4) or Character.digit(this[i * 2 + 1], 16)).toByte()
    }
}

private const val HEX = "0123456789abcdef"
