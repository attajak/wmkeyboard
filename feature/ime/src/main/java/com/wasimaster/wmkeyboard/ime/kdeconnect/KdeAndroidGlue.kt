package com.wasimaster.wmkeyboard.ime.kdeconnect

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import com.wasimaster.wmkeyboard.core.kdeconnect.KdeFileSink
import com.wasimaster.wmkeyboard.core.kdeconnect.KdeIncomingFile
import com.wasimaster.wmkeyboard.core.kdeconnect.KdeMdns
import com.wasimaster.wmkeyboard.core.kdeconnect.KdeOutgoingFile
import java.io.File
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/**
 * `_kdeconnect._udp` over Android's `NsdManager`: announce ourselves, and
 * report every other instance to the engine, which dials it.
 *
 * mDNS is the second way devices find each other, beside the UDP broadcast,
 * and the one that still works on networks that drop broadcasts but route
 * multicast. The record is the four TXT keys and nothing else — kdeconnect-kde
 * ignores an instance whose `protocol` key is missing, silently.
 *
 * Everything here is best effort. `NsdManager` fails in ways that differ by
 * vendor and release, none of them worth surfacing: the broadcast path is still
 * there, and "add by address" is there when neither works.
 */
internal class AndroidMdns(context: Context) : KdeMdns {

    private val nsd = context.applicationContext.getSystemService(Context.NSD_SERVICE) as? NsdManager
    private var registration: NsdManager.RegistrationListener? = null
    private var discovery: NsdManager.DiscoveryListener? = null
    private val resolving = ConcurrentHashMap.newKeySet<String>()

    @Synchronized
    override fun start(deviceId: String, name: String, type: String, protocolVersion: Int, tcpPort: Int, onFound: (KdeMdns.Found) -> Unit) {
        val manager = nsd ?: return
        stop()
        val info = NsdServiceInfo().apply {
            serviceName = deviceId
            serviceType = SERVICE_TYPE
            port = tcpPort
            setAttribute("id", deviceId)
            // Each key=value must stay under 255 bytes.
            setAttribute("name", name.take(60))
            setAttribute("type", type)
            setAttribute("protocol", protocolVersion.toString())
        }
        val registered = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) = Unit
            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) = Unit
            override fun onServiceUnregistered(info: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) = Unit
        }
        runCatching { manager.registerService(info, NsdManager.PROTOCOL_DNS_SD, registered) }
            .onSuccess { registration = registered }

        val found = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            override fun onServiceLost(service: NsdServiceInfo) = Unit
            override fun onServiceFound(service: NsdServiceInfo) {
                val id = service.serviceName ?: return
                // The instance name is the device id, which is all that can be
                // known before resolving — enough to skip ourselves.
                if (id == deviceId || !resolving.add(id)) return
                resolve(manager, service, onFound)
            }
        }
        runCatching { manager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, found) }
            .onSuccess { discovery = found }
    }

    @Suppress("DEPRECATION") // resolveService: its replacement is API 34, and this floor is 24.
    private fun resolve(manager: NsdManager, service: NsdServiceInfo, onFound: (KdeMdns.Found) -> Unit) {
        val listener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                resolving.remove(info.serviceName.orEmpty())
            }

            override fun onServiceResolved(info: NsdServiceInfo) {
                val id = info.serviceName.orEmpty()
                resolving.remove(id)
                val host = info.host ?: return
                val version = info.attributes["protocol"]?.toString(Charsets.UTF_8)?.toIntOrNull() ?: return
                onFound(KdeMdns.Found(id, host, info.port, version))
            }
        }
        runCatching { manager.resolveService(service, listener) }.onFailure { resolving.remove(service.serviceName.orEmpty()) }
    }

    @Synchronized
    override fun stop() {
        val manager = nsd ?: return
        registration?.let { runCatching { manager.unregisterService(it) } }
        discovery?.let { runCatching { manager.stopServiceDiscovery(it) } }
        registration = null
        discovery = null
        resolving.clear()
    }

    private companion object {
        const val SERVICE_TYPE = "_kdeconnect._udp"
    }
}

/**
 * Where files a computer sends end up: **Downloads/WM Keyboard** on Android 10
 * and later (through MediaStore, which needs no permission for an app's own
 * files), and the app's own folder behind the clipboard's FileProvider before
 * that, where writing to shared storage would need one.
 *
 * Either way the location handed back is a `content://` URI, which is what the
 * clipboard history and an `ACTION_VIEW` intent both want.
 */
internal class KdeReceivedFiles(context: Context) : KdeFileSink {
    private val app = context.applicationContext

    override fun create(deviceName: String, fileName: String, size: Long): KdeIncomingFile? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) viaMediaStore(fileName) else viaAppFiles(fileName)

    private fun viaMediaStore(fileName: String): KdeIncomingFile? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val resolver = app.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            mimeOf(fileName)?.let { put(MediaStore.Downloads.MIME_TYPE, it) }
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + FOLDER)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = runCatching { resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) }.getOrNull() ?: return null
        val out = runCatching { resolver.openOutputStream(uri) }.getOrNull() ?: run {
            runCatching { resolver.delete(uri, null, null) }
            return null
        }
        return object : KdeIncomingFile {
            override val stream: OutputStream = out
            override fun finish(success: Boolean): String? {
                runCatching { out.close() }
                if (!success) {
                    runCatching { resolver.delete(uri, null, null) }
                    return null
                }
                runCatching { resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null) }
                return uri.toString()
            }
        }
    }

    private fun viaAppFiles(fileName: String): KdeIncomingFile? {
        val dir = File(app.filesDir, RECEIVED_DIR).apply { mkdirs() }
        var target = File(dir, fileName)
        var n = 1
        while (target.exists()) {
            target = File(dir, fileName.substringBeforeLast('.', fileName) + " ($n)" +
                fileName.substringAfterLast('.', "").let { if (it.isEmpty() || !fileName.contains('.')) "" else ".$it" })
            n++
        }
        val out = runCatching { target.outputStream() }.getOrNull() ?: return null
        return object : KdeIncomingFile {
            override val stream: OutputStream = out
            override fun finish(success: Boolean): String? {
                runCatching { out.close() }
                if (!success) {
                    target.delete()
                    return null
                }
                return runCatching {
                    FileProvider.getUriForFile(app, app.packageName + FILE_PROVIDER_SUFFIX, target).toString()
                }.getOrNull()
            }
        }
    }

    companion object {
        const val FOLDER = "WM Keyboard"
        const val RECEIVED_DIR = "kdeconnect/received"
        /** The clipboard tool's FileProvider (`WMKeyboardService.clipboardFileProviderAuthority`). */
        const val FILE_PROVIDER_SUFFIX = ".clipboard"

        fun mimeOf(fileName: String): String? =
            MimeTypeMap.getSingleton().getMimeTypeFromExtension(fileName.substringAfterLast('.', "").lowercase())
    }
}

/** A `content://` or `file://` URI as something the engine can send. Null when it cannot be opened at all. */
internal fun outgoingFile(context: Context, uri: Uri): KdeOutgoingFile? {
    val resolver = context.applicationContext.contentResolver
    var name: String? = null
    var size = -1L
    if (uri.scheme == "content") {
        runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    name = c.getString(0)
                    if (!c.isNull(1)) size = c.getLong(1)
                }
            }
        }
    } else if (uri.scheme == "file") {
        uri.path?.let { File(it) }?.let { f ->
            name = f.name
            size = f.length()
        }
    }
    val finalName = name?.takeIf { it.isNotBlank() } ?: uri.lastPathSegment?.substringAfterLast('/') ?: return null
    // Opened once up front: a URI whose grant has lapsed should fail here, as
    // a clear "could not read", not halfway through a transfer.
    runCatching { resolver.openInputStream(uri)?.close() }.getOrElse { return null }
    return KdeOutgoingFile(finalName, size, System.currentTimeMillis()) {
        resolver.openInputStream(uri) ?: throw java.io.FileNotFoundException(uri.toString())
    }
}

/**
 * Album art over plain HTTP(S), for players (browsers, Spotify) whose artwork
 * is a web address rather than a file on the computer. Capped, because the URL
 * came from another machine.
 */
internal fun fetchAlbumArt(url: String): ByteArray? {
    val connection = (URL(url).openConnection() as? HttpURLConnection) ?: return null
    return try {
        connection.connectTimeout = 6_000
        connection.readTimeout = 8_000
        connection.instanceFollowRedirects = true
        if (connection.responseCode !in 200..299) return null
        connection.inputStream.use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                out.write(buffer, 0, read)
                if (out.size() > MAX_ART_BYTES) return null
            }
            out.toByteArray()
        }
    } catch (_: Exception) {
        null
    } finally {
        connection.disconnect()
    }
}

private const val MAX_ART_BYTES = 4 * 1024 * 1024
