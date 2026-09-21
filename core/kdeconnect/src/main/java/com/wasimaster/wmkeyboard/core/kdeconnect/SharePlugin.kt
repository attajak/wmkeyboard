package com.wasimaster.wmkeyboard.core.kdeconnect

import kotlinx.serialization.json.put
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * `kdeconnect.share.request`, both ways: text, a link, or files.
 *
 * A file is a header packet announcing a payload; the bytes travel on a second
 * connection ([PayloadTransfer]). Several files are one *composite* transfer:
 * each header carries the running `numberOfFiles` / `totalPayloadSize`, and a
 * `share.request.update` packet ahead of them says the same, so the desktop can
 * draw one progress bar instead of several. Those totals are decoration — a
 * receiver must never wait on them, and this one does not.
 */
class SharePlugin internal constructor(host: KdePluginHost) : KdePlugin(host) {
    override val incoming = setOf(KdeTypes.SHARE_REQUEST, KdeTypes.SHARE_REQUEST_UPDATE)
    override val outgoing = setOf(KdeTypes.SHARE_REQUEST, KdeTypes.SHARE_REQUEST_UPDATE)
    override val key = KdePluginKeys.SHARE

    private val ids = AtomicLong(1)
    private val cancelled = ConcurrentHashMap.newKeySet<Long>()

    fun sendText(deviceId: String, text: String): Boolean {
        if (text.isEmpty()) return false
        return host.send(deviceId, kdePacket(KdeTypes.SHARE_REQUEST) { put("text", text) })
    }

    /** The desktop opens [url] with whatever handles its scheme. */
    fun sendUrl(deviceId: String, url: String): Boolean {
        if (url.isBlank()) return false
        return host.send(deviceId, kdePacket(KdeTypes.SHARE_REQUEST) { put("url", url.trim()) })
    }

    /**
     * Queues [files] as one composite transfer and returns at once; progress
     * shows up in [KdeDevice.transfers]. [open] asks the desktop to open each
     * file when it lands.
     */
    fun sendFiles(deviceId: String, files: List<KdeOutgoingFile>, open: Boolean = false) {
        if (files.isEmpty() || host.device(deviceId)?.canShare != true) return
        val entries = files.map { file ->
            KdeTransfer(ids.getAndIncrement(), file.name, outgoing = true, size = file.size) to file
        }
        host.update(deviceId) { it.copy(transfers = (it.transfers + entries.map { e -> e.first }).takeLast(MAX_TRANSFERS)) }
        val total = files.sumOf { it.size.coerceAtLeast(0) }
        host.launch {
            host.send(
                deviceId,
                kdePacket(KdeTypes.SHARE_REQUEST_UPDATE) { put("numberOfFiles", files.size); put("totalPayloadSize", total) },
            )
            for ((transfer, file) in entries) {
                if (transfer.id in cancelled) {
                    mark(deviceId, transfer.id) { it.copy(state = KdeTransferState.CANCELLED) }
                    continue
                }
                mark(deviceId, transfer.id) { it.copy(state = KdeTransferState.RUNNING) }
                val header = kdePacket(KdeTypes.SHARE_REQUEST) {
                    put("filename", file.name)
                    if (file.lastModifiedMs > 0) put("lastModified", file.lastModifiedMs)
                    put("numberOfFiles", files.size)
                    put("totalPayloadSize", total)
                    // A file to be opened is always a transfer of its own on the far side.
                    if (open) put("open", true)
                }
                val ok = runCatching {
                    file.open().use { stream ->
                        host.upload(deviceId, header, file.size, stream, { transfer.id in cancelled }) { sent ->
                            progress(deviceId, transfer.id, sent)
                        }
                    }
                }.getOrDefault(false)
                finish(deviceId, transfer.id, ok)
            }
        }
    }

    fun cancel(transferId: Long) {
        cancelled += transferId
    }

    fun clearFinished(deviceId: String) {
        host.update(deviceId) { d ->
            d.copy(transfers = d.transfers.filter { it.state == KdeTransferState.WAITING || it.state == KdeTransferState.RUNNING })
        }
    }

    override fun onPacket(deviceId: String, packet: KdePacket) {
        if (packet.type == KdeTypes.SHARE_REQUEST_UPDATE || !enabledFor(deviceId)) return
        val name = host.device(deviceId)?.name.orEmpty()
        when {
            packet.hasPayload || packet.has("filename") -> receiveFile(deviceId, name, packet)
            packet.has("text") -> packet.string("text")?.takeIf { it.isNotEmpty() }
                ?.let { host.emit(KdeEvent.TextReceived(deviceId, name, it)) }
            packet.has("url") -> packet.string("url")?.takeIf { it.isNotBlank() }
                ?.let { host.emit(KdeEvent.UrlReceived(deviceId, name, it)) }
        }
    }

    private fun receiveFile(deviceId: String, deviceName: String, packet: KdePacket) {
        if (!packet.hasPayload || !host.config.receiveFiles) return
        val sink = host.fileSink ?: return
        val fileName = safeFileName(packet.string("filename"), host.now())
        val transfer = KdeTransfer(
            id = ids.getAndIncrement(),
            fileName = fileName,
            outgoing = false,
            size = packet.payloadSize,
            state = KdeTransferState.RUNNING,
        )
        host.update(deviceId) { it.copy(transfers = (it.transfers + transfer).takeLast(MAX_TRANSFERS)) }
        host.launch {
            val target = runCatching { sink.create(deviceName, fileName, packet.payloadSize) }.getOrNull()
            if (target == null) {
                finish(deviceId, transfer.id, ok = false)
                return@launch
            }
            val ok = host.download(deviceId, packet, target.stream, { transfer.id in cancelled }) { received ->
                progress(deviceId, transfer.id, received)
            }
            val location = runCatching { target.finish(ok) }.getOrNull()
            if (ok && location != null) {
                mark(deviceId, transfer.id) { it.copy(location = location) }
                finish(deviceId, transfer.id, ok = true)
                host.emit(KdeEvent.FileReceived(deviceId, deviceName, fileName, location))
            } else {
                finish(deviceId, transfer.id, ok = false)
            }
        }
    }

    private fun progress(deviceId: String, id: Long, done: Long) {
        // Every 4 KiB chunk would be a state emission; a few per megabyte is plenty.
        val device = host.device(deviceId) ?: return
        val current = device.transfers.firstOrNull { it.id == id } ?: return
        if (done - current.done < PROGRESS_STEP && done != current.size) return
        mark(deviceId, id) { it.copy(done = done) }
    }

    private fun finish(deviceId: String, id: Long, ok: Boolean) {
        val wasCancelled = cancelled.remove(id)
        mark(deviceId, id) {
            it.copy(
                state = when {
                    ok -> KdeTransferState.DONE
                    wasCancelled -> KdeTransferState.CANCELLED
                    else -> KdeTransferState.FAILED
                },
                done = if (ok && it.size > 0) it.size else it.done,
            )
        }
    }

    private fun mark(deviceId: String, id: Long, change: (KdeTransfer) -> KdeTransfer) {
        host.update(deviceId) { d -> d.copy(transfers = d.transfers.map { if (it.id == id) change(it) else it }) }
    }

    internal companion object {
        const val MAX_TRANSFERS = 20
        const val PROGRESS_STEP = 256L * 1024

        /**
         * A name that is only a name. The peer chooses it, so path separators,
         * parent references and control characters all go before it reaches a
         * file system; a name with nothing left gets a timestamp, as the
         * desktop does for a share that arrives without one.
         */
        fun safeFileName(raw: String?, nowMs: Long): String {
            val base = raw.orEmpty().substringAfterLast('/').substringAfterLast('\\')
            val cleaned = base.filter { it.code >= 0x20 && it !in "<>:\"|?*" }.trim().trim('.')
            return cleaned.take(200).ifEmpty { nowMs.toString() }
        }
    }
}
