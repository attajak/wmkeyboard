package com.wasimaster.wmkeyboard.core.prediction

import com.wasimaster.wmkeyboard.core.util.SnapshotFile
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Email addresses the user has typed into email fields, so the next email
 * field can offer them before a letter is typed (issue #475).
 *
 * [ContactEmails] covers the address book and is never written anywhere. This
 * is the other half: the user's own addresses, and the ones they sign in with,
 * which are often in nobody's contacts. It is opt-in, and it only ever learns
 * from a field that declared itself an email field, never from ordinary text.
 *
 * Ranked by how often an address was used, then how recently, and capped at
 * [MAX_ADDRESSES] with the least recently used falling off.
 *
 * Personal-store contract: nullable file for a locked device, dirty-flag save
 * on dismissal, [reload] for the Storage screen's delete.
 */
class TypedEmails(private val storageFile: File? = null) {

    @Serializable
    private data class Entry(val address: String, val uses: Int, val lastUsed: Long)

    @Serializable
    private data class Snapshot(val addresses: List<Entry> = emptyList())

    private val entries = LinkedHashMap<String, Entry>()
    private val json = Json { ignoreUnknownKeys = true }
    private var dirty = false
    private val snapshotFile = storageFile?.let(::SnapshotFile)

    init {
        load()
    }

    @get:Synchronized
    val isEmpty: Boolean get() = entries.isEmpty()

    /**
     * Counts one use of [raw], when it is an address at all: one `@` with
     * something on either side and a dot after it, no whitespace. Anything
     * else typed into an email field (a user name, half an address) is left
     * alone.
     */
    @Synchronized
    fun record(raw: String, now: Long = System.currentTimeMillis()): Boolean {
        val address = normalize(raw) ?: return false
        val old = entries.remove(address)
        entries[address] = Entry(address, (old?.uses ?: 0) + 1, now)
        while (entries.size > MAX_ADDRESSES) {
            val stalest = entries.values.minByOrNull { it.lastUsed } ?: break
            entries.remove(stalest.address)
        }
        dirty = true
        return true
    }

    /** The addresses starting with [prefix] (any case), best first; every one for an empty prefix. */
    @Synchronized
    fun complete(prefix: String, limit: Int): List<String> {
        val lower = prefix.lowercase()
        return entries.values
            .filter { it.address.startsWith(lower) && it.address != lower }
            .sortedWith(compareByDescending<Entry> { it.uses }.thenByDescending { it.lastUsed })
            .take(limit)
            .map { it.address }
    }

    @Synchronized
    fun forget(address: String) {
        if (entries.remove(address.lowercase()) != null) dirty = true
    }

    fun save() {
        val file = snapshotFile ?: return
        val (ticket, snapshot) = synchronized(this) {
            if (!dirty) return
            dirty = false
            file.ticket() to Snapshot(entries.values.toList())
        }
        if (!file.write(ticket) { json.encodeToString(snapshot) }) markUnsaved()
    }

    @Synchronized
    private fun markUnsaved() {
        dirty = true
    }

    @Synchronized
    fun reload() {
        snapshotFile?.supersede()
        entries.clear()
        load()
        dirty = false
    }

    @Synchronized
    fun clear() {
        entries.clear()
        dirty = snapshotFile?.delete() == false
    }

    private fun load() {
        val file = storageFile ?: return
        if (!file.exists()) return
        runCatching {
            val snapshot = json.decodeFromString<Snapshot>(file.readText())
            for (entry in snapshot.addresses.takeLast(MAX_ADDRESSES)) {
                val address = normalize(entry.address) ?: continue
                entries[address] = entry.copy(address = address, uses = entry.uses.coerceAtLeast(1))
            }
        }
    }

    companion object {
        /** More than anyone signs in with; few enough that a scan is free. */
        const val MAX_ADDRESSES = 50

        /** [raw] trimmed and lowercased when it is plausibly an address, else null. */
        fun normalize(raw: String): String? {
            val address = raw.trim().lowercase()
            if (address.length > MAX_LENGTH || address.any { it.isWhitespace() }) return null
            val at = address.indexOf('@')
            if (at <= 0 || at != address.lastIndexOf('@')) return null
            val domain = address.substring(at + 1)
            val dot = domain.lastIndexOf('.')
            if (dot <= 0 || dot == domain.length - 1) return null
            return address
        }

        private const val MAX_LENGTH = 254
    }
}
