package com.wasimaster.wmkeyboard.core.keyman

/**
 * The rule engine's view of the text behind the caret.
 *
 * Holds at most [KeymanLimits.MAX_CONTEXT_UNITS] UTF-16 code units — Keyman
 * Core's own window, and units rather than characters, so a deadkey marker eats
 * three of them and a surrogate pair two. Matching that number exactly is what
 * makes a rule with a long context behave here the way it does upstream.
 *
 * ## Deadkeys
 *
 * A deadkey lives *in* this buffer as the three units
 * `UC_SENTINEL, CODE_DEADKEY, id` — the same spelling rules use, which is what
 * lets a rule match one. They are invisible: they must never be handed to the
 * text field, and every length this class reports to the host is a length of
 * **visible** text with the markers taken out.
 *
 * ## Three units of measurement
 *
 * Keyman counts code points, this buffer holds code units with markers, and
 * `deleteSurroundingText` counts UTF-16 units of visible text. Those are three
 * different numbers and conflating any two breaks by exactly one on every
 * astral-plane keyboard — Osage, Deseret, Adlam — which is a large part of what
 * Keyman exists for. [visibleUnitsForElements] is the only conversion, and the
 * host is given nothing else.
 */
class KeymanContext {

    private val buf = StringBuilder()

    /** The raw buffer, markers included. Rule matching reads this. */
    internal val raw: CharSequence get() = buf

    val isEmpty: Boolean get() = buf.isEmpty()

    /** Visible text only, markers removed. What the field would show. */
    fun visible(): String {
        if (!hasMarkers()) return buf.toString()
        val out = StringBuilder(buf.length)
        var i = 0
        while (i < buf.length) {
            val next = KmxString.next(buf.toString(), i)
            if (!isDeadkeyAt(i)) out.append(buf, i, next.coerceAtMost(buf.length))
            i = next
        }
        return out.toString()
    }

    /** True when the context holds any character, as opposed to only deadkeys or nothing. */
    val hasVisibleText: Boolean
        get() {
            var i = 0
            while (i < buf.length) {
                if (!isDeadkeyAt(i)) return true
                i += DEADKEY_UNITS
            }
            return false
        }

    /** True when a deadkey marker sits at the very end. */
    val endsWithDeadkey: Boolean
        get() = buf.length >= DEADKEY_UNITS && isDeadkeyAt(buf.length - DEADKEY_UNITS)

    fun clear() {
        buf.setLength(0)
    }

    /** The buffer as it stands, markers included, for [restore]. */
    fun snapshot(): String = buf.toString()

    /** Puts back what [snapshot] took. */
    fun restore(snapshot: String) {
        buf.setLength(0)
        buf.append(snapshot)
    }

    /** Replaces the buffer with the tail of [before], dropping every deadkey. */
    fun reset(before: CharSequence) {
        buf.setLength(0)
        val from = (before.length - KeymanLimits.MAX_CONTEXT_UNITS).coerceAtLeast(0)
        var start = from
        // A window that begins on a low surrogate would put half a character at
        // the front of the context, where it can match nothing and confuses any
        // length taken from it.
        if (start > 0 && start < before.length && Character.isLowSurrogate(before[start])) start++
        buf.append(before, start, before.length)
    }

    fun append(text: CharSequence) {
        buf.append(text)
        trim()
    }

    /** [id] is the deadkey's number as the file stores it: biased by one, so never 0. */
    fun appendDeadkey(id: Int) {
        buf.append(KmxFormat.UC_SENTINEL.toChar())
        buf.append(KmxFormat.CODE_DEADKEY.toChar())
        buf.append(id.toChar())
        trim()
    }

    /**
     * Drops the last logical element — one character, one surrogate pair, or one
     * three-unit deadkey marker. Returns how many units of **visible** text went
     * with it, which is 0 for a deadkey.
     */
    fun deleteLastElement(): Int {
        if (buf.isEmpty()) return 0
        if (endsWithDeadkey) {
            buf.setLength(buf.length - DEADKEY_UNITS)
            return 0
        }
        val units = if (buf.length >= 2 &&
            Character.isLowSurrogate(buf[buf.length - 1]) &&
            Character.isHighSurrogate(buf[buf.length - 2])
        ) 2 else 1
        buf.setLength(buf.length - units)
        return units
    }

    /**
     * Drops the last character — one unit, or a surrogate pair — leaving every
     * deadkey marker where it is, before or after it. Returns the units of
     * visible text removed, 0 when there was no character to remove.
     */
    fun deleteLastVisibleElement(): Int {
        var end = buf.length
        while (end >= DEADKEY_UNITS && isDeadkeyAt(end - DEADKEY_UNITS)) end -= DEADKEY_UNITS
        if (end == 0) return 0
        val units = if (end >= 2 &&
            Character.isLowSurrogate(buf[end - 1]) &&
            Character.isHighSurrogate(buf[end - 2])
        ) 2 else 1
        buf.delete(end - units, end)
        return units
    }

    /**
     * How many UTF-16 units of **visible** text the last [elements] logical
     * elements occupy. This is the number, and the only number, the host may
     * hand to `deleteSurroundingText`.
     */
    fun visibleUnitsForElements(elements: Int): Int {
        var remaining = elements
        var at = buf.length
        var units = 0
        while (remaining > 0 && at > 0) {
            if (at >= DEADKEY_UNITS && isDeadkeyAt(at - DEADKEY_UNITS)) {
                at -= DEADKEY_UNITS
            } else if (at >= 2 &&
                Character.isLowSurrogate(buf[at - 1]) &&
                Character.isHighSurrogate(buf[at - 2])
            ) {
                at -= 2
                units += 2
            } else {
                at -= 1
                units += 1
            }
            remaining--
        }
        return units
    }

    /**
     * Whether a context cached here may be kept against what the field actually
     * holds, mirroring `km_core_state_context_set_if_needed`.
     *
     * Markers are ignored in the comparison. A cache that is **shorter than or
     * equal to** the field and agrees with its tail is treated as identical, so
     * deadkeys survive. Anything else — longer, or disagreeing — resets, and
     * resetting drops the deadkeys.
     *
     * The bias is deliberate and one-sided. Losing a deadkey costs the user one
     * re-press. Keeping a context that has gone stale makes the engine delete
     * the wrong number of characters from text it did not type, silently.
     */
    fun decideSync(field: CharSequence): SyncDecision {
        val cached = visible()
        return if (cached.length <= field.length && endsWith(field, cached)) {
            SyncDecision.KEEP
        } else {
            SyncDecision.RESET
        }
    }

    private fun hasMarkers(): Boolean {
        for (i in buf.indices) if (buf[i].code == KmxFormat.UC_SENTINEL) return true
        return false
    }

    private fun isDeadkeyAt(i: Int): Boolean =
        i >= 0 && i + 2 < buf.length + 1 && i + 1 < buf.length &&
            buf[i].code == KmxFormat.UC_SENTINEL &&
            buf[i + 1].code == KmxFormat.CODE_DEADKEY

    /** Drops whole logical elements off the front until the window fits. */
    private fun trim() {
        while (buf.length > KeymanLimits.MAX_CONTEXT_UNITS) {
            val next = KmxString.next(buf.toString(), 0)
            buf.delete(0, next.coerceAtLeast(1).coerceAtMost(buf.length))
        }
    }

    override fun toString(): String = "KeymanContext(${buf.length}u, visible=${visible().length})"

    private companion object {
        const val DEADKEY_UNITS = 3

        fun endsWith(haystack: CharSequence, needle: CharSequence): Boolean {
            if (needle.length > haystack.length) return false
            val offset = haystack.length - needle.length
            for (i in needle.indices) if (haystack[offset + i] != needle[i]) return false
            return true
        }
    }
}

/** Whether a cached context survived a check against the real field. */
enum class SyncDecision {
    /** The cache agrees with the field; deadkeys and all are kept. */
    KEEP,

    /** The cache is stale; it was rebuilt from the field and deadkeys are gone. */
    RESET,
}
