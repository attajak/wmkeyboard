package com.wasimaster.wmkeyboard.core.prediction

import com.wasimaster.wmkeyboard.core.util.SnapshotFile
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Numbers the user has typed, so typing the first digits of one again offers
 * the whole of it on the strip (issue #431) — the way Samsung's keyboard
 * remembers a phone number or an account number typed often.
 *
 * Digits never reach the composing buffer (a digit at the start of a word
 * commits as it is typed), so the dictionary never sees them and the word
 * learner, which wants a letter, would refuse them anyway. This is the store
 * they go to instead. It is opt-in, and it only keeps a run of digits that
 * stood on its own: one with a letter glued to it is part of a word or a code
 * ("win10", "a1b2"), and one behind a separator is the tail of a bigger number
 * ("1,234", "12:30", "0171-234") that would be remembered in pieces.
 *
 * Ranked by how often a number was typed, then how recently, and capped at
 * [MAX_NUMBERS] with the least recently used falling off.
 *
 * Personal-store contract: nullable file for a locked device, dirty-flag save
 * on dismissal, [reload] for the Storage screen's delete.
 */
class TypedNumbers(private val storageFile: File? = null) {

    @Serializable
    private data class Entry(val number: String, val uses: Int, val lastUsed: Long)

    @Serializable
    private data class Snapshot(val numbers: List<Entry> = emptyList())

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
     * Counts one use of [raw], when it is a number worth keeping: digits only,
     * [MIN_LENGTH] to [MAX_LENGTH] of them. A one- or two-digit number is
     * quicker to type than to pick, and anything longer than a phone number
     * with its country code is not one anybody types twice.
     */
    @Synchronized
    fun record(raw: String, now: Long = System.currentTimeMillis()): Boolean {
        val number = normalize(raw) ?: return false
        val old = entries.remove(number)
        entries[number] = Entry(number, (old?.uses ?: 0) + 1, now)
        while (entries.size > MAX_NUMBERS) {
            val stalest = entries.values.minByOrNull { it.lastUsed } ?: break
            entries.remove(stalest.number)
        }
        dirty = true
        return true
    }

    /**
     * The numbers that go on from [prefix], best first. Nothing for an empty
     * prefix — unlike an email field, an ordinary text field gives no sign
     * that a number is coming — and never the prefix itself, which has
     * nothing left to complete.
     */
    @Synchronized
    fun complete(prefix: String, limit: Int): List<String> {
        if (prefix.isEmpty()) return emptyList()
        return entries.values
            .filter { it.number.length > prefix.length && it.number.startsWith(prefix) }
            .sortedWith(compareByDescending<Entry> { it.uses }.thenByDescending { it.lastUsed })
            .take(limit)
            .map { it.number }
    }

    @Synchronized
    fun forget(number: String) {
        if (entries.remove(number) != null) dirty = true
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
            for (entry in snapshot.numbers.takeLast(MAX_NUMBERS)) {
                val number = normalize(entry.number) ?: continue
                entries[number] = entry.copy(number = number, uses = entry.uses.coerceAtLeast(1))
            }
        }
    }

    companion object {
        /** Room for every number anyone types by hand; few enough that a scan is free. */
        const val MAX_NUMBERS = 100

        /** Shorter is quicker typed than picked. */
        const val MIN_LENGTH = 3

        /** A phone number with its country code, and a little over. */
        const val MAX_LENGTH = 20

        /**
         * What may stand right in front of a number for it to be one of its
         * own: the plus of a phone number, an opening bracket, a hash.
         * Whitespace and the start of the text count too.
         */
        private const val OPENERS = "+(#[{"

        /** [raw] when it is a run of digits of a length worth keeping, else null. */
        fun normalize(raw: String): String? {
            val number = raw.trim()
            if (number.length !in MIN_LENGTH..MAX_LENGTH || !number.all(Char::isDigit)) return null
            return number
        }

        /**
         * The run of digits [before] ends in, when it stands on its own: at
         * the start of the text, after whitespace, or after one of the
         * [OPENERS]. Null when [before] does not end in a digit, when the run
         * is glued to a letter or hangs off a separator (see the class note),
         * and when it is too long to be a number anybody types twice. Any
         * script's digits, so a Bengali ১২৩ is a run like 123.
         */
        fun runBefore(before: CharSequence?): String? {
            if (before.isNullOrEmpty() || !before[before.length - 1].isDigit()) return null
            var start = before.length
            while (start > 0 && before[start - 1].isDigit()) start--
            if (before.length - start > MAX_LENGTH) return null
            if (start > 0) {
                val ahead = before[start - 1]
                if (!ahead.isWhitespace() && ahead !in OPENERS) return null
            }
            return before.subSequence(start, before.length).toString()
        }
    }
}
