package com.wasimaster.wmkeyboard.core.kdeconnect

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream

/**
 * The PC's media players, driven from the phone (`kdeconnect.mpris` in,
 * `kdeconnect.mpris.request` out).
 *
 * Status packets are **partial**: the desktop sends only the properties that
 * changed, so every field is merged over what is already known and a missing
 * key never resets anything. Two keys are different — `loopStatus` and
 * `shuffle` being *present at all* is how a player says it supports them.
 *
 * The units are a trap laid by MPRIS itself: positions and lengths travel in
 * milliseconds and `SetPosition` takes milliseconds, but `Seek` — a relative
 * jump — takes **micro**seconds, and both request keys are capitalised where
 * every other key is camelCase.
 */
class MprisRemotePlugin internal constructor(
    host: KdePluginHost,
    /** Fetches `http(s)` album art; the engine has no HTTP client of its own. Null: no remote art. */
    private val fetchArt: ((String) -> ByteArray?)?,
) : KdePlugin(host) {
    override val incoming = setOf(KdeTypes.MPRIS)
    override val outgoing = setOf(KdeTypes.MPRIS_REQUEST)

    private val artLock = Any()
    private val art = object : LinkedHashMap<String, ByteArray>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>?): Boolean = size > MAX_ART
    }
    private val artRequested = HashSet<String>()

    /** The bytes of [url]'s artwork if they have arrived. The UI decodes them. */
    fun art(url: String): ByteArray? = synchronized(artLock) { art[url] }

    fun refresh(deviceId: String) {
        host.send(deviceId, request { put("requestPlayerList", true) })
    }

    fun select(deviceId: String, player: String) {
        host.update(deviceId) { d -> if (player in d.mpris.players) d.copy(mpris = d.mpris.copy(selected = player)) else d }
        requestStatus(deviceId, player)
    }

    fun action(deviceId: String, player: String, action: KdeMediaAction): Boolean =
        host.send(deviceId, request { put("player", player); put("action", action.wire) })

    fun seekTo(deviceId: String, player: String, positionMs: Long): Boolean {
        val target = positionMs.coerceAtLeast(0)
        // Shown at once: the desktop answers with the real position shortly.
        host.update(deviceId) { d -> d.withPlayer(player) { it.copy(positionMs = target, positionAtMs = host.now()) } }
        return host.send(deviceId, request { put("player", player); put("SetPosition", target) })
    }

    fun seekBy(deviceId: String, player: String, deltaMs: Long): Boolean =
        host.send(deviceId, request { put("player", player); put("Seek", deltaMs * 1000) })

    fun setVolume(deviceId: String, player: String, percent: Int): Boolean {
        val volume = percent.coerceIn(0, 100)
        host.update(deviceId) { d -> d.withPlayer(player) { it.copy(volume = volume) } }
        return host.send(deviceId, request { put("player", player); put("setVolume", volume) })
    }

    fun setLoop(deviceId: String, player: String, loop: KdeLoop): Boolean =
        host.send(deviceId, request { put("player", player); put("setLoopStatus", loop.wire) })

    fun setShuffle(deviceId: String, player: String, shuffle: Boolean): Boolean =
        host.send(deviceId, request { put("player", player); put("setShuffle", shuffle) })

    override fun onConnected(deviceId: String) = refresh(deviceId)

    override fun onDisconnected(deviceId: String) {
        host.update(deviceId) { it.copy(mpris = KdeMpris()) }
    }

    override fun onPacket(deviceId: String, packet: KdePacket) {
        if (packet.bool("transferringAlbumArt")) {
            receiveArt(deviceId, packet)
            return
        }
        packet.boolOrNull("supportAlbumArtPayload")?.let { supported ->
            host.update(deviceId) { it.copy(mpris = it.mpris.copy(supportsArtTransfer = supported)) }
        }
        if (packet.has("playerList")) {
            val players = packet.strings("playerList")
            val before = host.device(deviceId)?.mpris?.players.orEmpty()
            host.update(deviceId) { d ->
                val status = d.mpris.status.filterKeys { it in players }
                val selected = d.mpris.selected?.takeIf { it in players }
                    ?: status.values.firstOrNull { it.playing }?.name
                    ?: players.firstOrNull()
                d.copy(mpris = d.mpris.copy(players = players, status = status, selected = selected))
            }
            for (player in players) if (player !in before) requestStatus(deviceId, player)
        }
        val name = packet.string("player") ?: return
        host.update(deviceId) { d ->
            val old = d.mpris.status[name] ?: KdePlayer(name)
            val merged = merge(old, packet)
            val players = if (name in d.mpris.players) d.mpris.players else d.mpris.players + name
            // Follow the music: whatever starts playing while the shown player
            // is silent becomes the shown player.
            val current = d.mpris.selected?.let(d.mpris.status::get)
            val selected = when {
                d.mpris.selected == null -> name
                merged.playing && !old.playing && current?.playing != true -> name
                else -> d.mpris.selected
            }
            d.copy(mpris = d.mpris.copy(players = players, status = d.mpris.status + (name to merged), selected = selected))
        }
        host.device(deviceId)?.mpris?.status?.get(name)?.albumArtUrl?.let { wantArt(deviceId, name, it) }
    }

    private fun merge(old: KdePlayer, p: KdePacket): KdePlayer {
        val hasPosition = p.has("pos")
        val title = p.string("title") ?: old.title
        val artist = p.string("artist") ?: old.artist
        return old.copy(
            // GSConnect of a certain age sends only the combined "Artist - Title".
            title = if (title.isEmpty() && artist.isEmpty()) p.string("nowPlaying") ?: title else title,
            artist = artist,
            album = p.string("album") ?: old.album,
            albumArtUrl = p.string("albumArtUrl") ?: old.albumArtUrl,
            playing = p.boolOrNull("isPlaying") ?: old.playing,
            positionMs = if (hasPosition) (p.long("pos") ?: 0).coerceAtLeast(0) else old.positionMs,
            positionAtMs = if (hasPosition || p.has("isPlaying")) host.now() else old.positionAtMs,
            lengthMs = p.long("length") ?: old.lengthMs,
            volume = p.int("volume") ?: old.volume,
            canPlay = p.boolOrNull("canPlay") ?: old.canPlay,
            canPause = p.boolOrNull("canPause") ?: old.canPause,
            canNext = p.boolOrNull("canGoNext") ?: old.canNext,
            canPrevious = p.boolOrNull("canGoPrevious") ?: old.canPrevious,
            canSeek = p.boolOrNull("canSeek") ?: old.canSeek,
            loop = if (p.has("loopStatus")) KdeLoop.fromWire(p.string("loopStatus")) ?: old.loop else old.loop,
            shuffle = if (p.has("shuffle")) p.bool("shuffle") else old.shuffle,
        ).let { merged ->
            // When only isPlaying changed, carry the position forward to now so
            // extrapolation restarts from the right place.
            if (!hasPosition && p.has("isPlaying") && old.playing) {
                merged.copy(positionMs = old.positionMs + (host.now() - old.positionAtMs).coerceAtLeast(0))
            } else {
                merged
            }
        }
    }

    private fun requestStatus(deviceId: String, player: String) {
        host.send(deviceId, request { put("player", player); put("requestNowPlaying", true); put("requestVolume", true) })
    }

    private fun wantArt(deviceId: String, player: String, url: String) {
        if (url.isEmpty()) return
        synchronized(artLock) {
            if (url in art || !artRequested.add(url)) return
            if (artRequested.size > 64) artRequested.clear()
        }
        val scheme = url.substringBefore(':', "").lowercase()
        when (scheme) {
            "http", "https" -> {
                val fetch = fetchArt ?: return
                host.launch {
                    val bytes = runCatching { fetch(url) }.getOrNull()
                    if (bytes != null && bytes.isNotEmpty()) storeArt(deviceId, url, bytes)
                }
            }
            // Local to the PC: only it can read the file, and it sends the
            // bytes only for the exact URL it last announced.
            "file", "kdeconnect" -> if (host.device(deviceId)?.mpris?.supportsArtTransfer == true) {
                host.send(deviceId, request { put("player", player); put("albumArtUrl", url) })
            }
        }
    }

    private fun receiveArt(deviceId: String, packet: KdePacket) {
        val url = packet.string("albumArtUrl") ?: return
        if (!packet.hasPayload) return
        if (packet.payloadSize > MAX_ART_BYTES) return
        host.launch {
            val buffer = ByteArrayOutputStream()
            if (host.download(deviceId, packet, buffer, cancelled = { buffer.size() > MAX_ART_BYTES }, onProgress = {})) {
                storeArt(deviceId, url, buffer.toByteArray())
            }
        }
    }

    private fun storeArt(deviceId: String, url: String, bytes: ByteArray) {
        synchronized(artLock) { art[url] = bytes }
        host.update(deviceId) { d -> d.copy(mpris = d.mpris.copy(artRevision = d.mpris.artRevision + 1)) }
    }

    private inline fun request(build: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) =
        kdePacket(KdeTypes.MPRIS_REQUEST, build)

    private fun KdeDevice.withPlayer(name: String, change: (KdePlayer) -> KdePlayer): KdeDevice {
        val player = mpris.status[name] ?: return this
        return copy(mpris = mpris.copy(status = mpris.status + (name to change(player))))
    }

    private companion object {
        const val MAX_ART = 8
        const val MAX_ART_BYTES = 6L * 1024 * 1024
    }
}

/**
 * The phone's own media, offered to the PC as one MPRIS player
 * (`kdeconnect.mpris.request` in, `kdeconnect.mpris` out). The host app feeds
 * [setMedia]; transport presses come back as [KdeEvent.PhoneMediaCommand].
 */
class MprisHostPlugin internal constructor(host: KdePluginHost) : KdePlugin(host) {
    override val incoming = setOf(KdeTypes.MPRIS_REQUEST)
    override val outgoing = setOf(KdeTypes.MPRIS)
    override val key = KdePluginKeys.MEDIA

    @Volatile private var media: KdePhoneMedia? = null

    fun setMedia(next: KdePhoneMedia?) {
        val previous = media
        if (previous == next) return
        media = next
        for (id in host.connectedIds()) {
            if (!active(id)) continue
            if (previous?.player != next?.player) sendPlayerList(id)
            if (next != null) sendStatus(id, next)
        }
    }

    override fun onConnected(deviceId: String) {
        if (active(deviceId) && media != null) sendPlayerList(deviceId)
    }

    override fun onPacket(deviceId: String, packet: KdePacket) {
        // A packet carrying a player list came from another controller, not for us.
        if (packet.has("playerList") || !active(deviceId)) return
        if (packet.bool("requestPlayerList")) {
            sendPlayerList(deviceId)
            return
        }
        val current = media ?: return
        if (packet.string("player") != current.player) {
            sendPlayerList(deviceId)
            return
        }
        packet.long("SetPosition")?.let { host.emit(KdeEvent.PhoneMediaCommand(deviceId, seekToMs = it)) }
        packet.long("Seek")?.let { host.emit(KdeEvent.PhoneMediaCommand(deviceId, seekByMs = it / 1000)) }
        packet.int("setVolume")?.let { host.emit(KdeEvent.PhoneMediaCommand(deviceId, volume = it.coerceIn(0, 100))) }
        KdeMediaAction.fromWire(packet.string("action"))?.let { host.emit(KdeEvent.PhoneMediaCommand(deviceId, action = it)) }
        if (packet.bool("requestNowPlaying") || packet.bool("requestVolume")) sendStatus(deviceId, current)
    }

    private fun active(deviceId: String) = host.config.exposeMedia && enabledFor(deviceId)

    private fun sendPlayerList(deviceId: String) {
        val players = listOfNotNull(media?.player)
        host.send(
            deviceId,
            kdePacket(KdeTypes.MPRIS) {
                put("playerList", JsonArray(players.map(::JsonPrimitive)))
                put("supportAlbumArtPayload", false)
            },
        )
    }

    private fun sendStatus(deviceId: String, m: KdePhoneMedia) {
        host.send(
            deviceId,
            kdePacket(KdeTypes.MPRIS) {
                put("player", m.player)
                put("title", m.title)
                put("artist", m.artist)
                put("album", m.album)
                // Deprecated, and still the only thing older GSConnect reads.
                put("nowPlaying", listOf(m.artist, m.title).filter { it.isNotEmpty() }.joinToString(" - "))
                put("isPlaying", m.playing)
                put("pos", m.positionMs)
                put("length", m.lengthMs)
                put("volume", m.volume)
                put("canPlay", true)
                put("canPause", true)
                put("canGoNext", m.canNext)
                put("canGoPrevious", m.canPrevious)
                put("canSeek", m.canSeek)
            },
        )
    }
}
