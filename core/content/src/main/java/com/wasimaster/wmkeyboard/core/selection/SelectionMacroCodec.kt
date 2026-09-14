package com.wasimaster.wmkeyboard.core.selection

/**
 * How the selection macro settings are stored, kept apart from the
 * repository so the version rule has a unit test.
 *
 * Both lists are stamped with [SelectionMacros.LIST_VERSION]. A list stored
 * under an older version, or none at all, reads as the shipped list: that is
 * the one-time reset a build with new defaults gives every existing user, and
 * it is also what a restored backup from an older build gets. Names this
 * build does not know are dropped rather than failing the whole list.
 */
object SelectionMacroCodec {

    private const val SEPARATOR = "\t"

    fun decodeMacros(storedVersion: Int?, names: Set<String>?): Set<SelectionMacro> {
        if (storedVersion != SelectionMacros.LIST_VERSION || names == null) return SelectionMacros.defaultMacros
        return names.mapNotNullTo(mutableSetOf()) { name -> runCatching { SelectionMacro.valueOf(name) }.getOrNull() }
            .filterTo(mutableSetOf()) { it in SelectionMacros.configurable }
    }

    fun encodeMacros(macros: Set<SelectionMacro>): Set<String> =
        macros.filter { it in SelectionMacros.configurable }.mapTo(mutableSetOf()) { it.name }

    /**
     * The stored order followed by whatever it does not name, in shipped
     * order, so a macro added later has a place instead of vanishing.
     */
    fun decodeOrder(storedVersion: Int?, encoded: String?): List<SelectionMacro> {
        if (storedVersion != SelectionMacros.LIST_VERSION || encoded.isNullOrBlank()) return SelectionMacros.defaultOrder
        val stored = encoded.split(SEPARATOR)
            .mapNotNull { name -> runCatching { SelectionMacro.valueOf(name) }.getOrNull() }
            .filter { it in SelectionMacros.configurable && it !in SelectionMacros.ladderOnly }
            .distinct()
        return stored + SelectionMacros.defaultOrder.filter { it !in stored }
    }

    fun encodeOrder(order: List<SelectionMacro>): String = order.joinToString(SEPARATOR) { it.name }
}
