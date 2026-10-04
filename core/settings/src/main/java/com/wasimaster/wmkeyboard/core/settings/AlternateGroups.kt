package com.wasimaster.wmkeyboard.core.settings

/**
 * The three kinds of character a letter key's long-press popup holds, in the
 * order the user wants them (#385): what the layout itself lists under the
 * key, the accents the "all accents" and native-letter passes add, and the
 * key's capital or shifted form. [KeyPopupSettings.alternatesOrder] stores the
 * order; the keyboard sorts a key's popup by it, keeping each group's own
 * order inside.
 */
enum class AlternateGroup { LAYOUT, ACCENTS, SHIFTED }

/** The layout's own, then accents, then the shifted form: the order the popup was always built in. */
val DefaultAlternatesOrder: List<AlternateGroup> = AlternateGroup.entries.toList()

/**
 * [stored] as a full ordering of the three groups. Unknown names are dropped
 * and missing groups are appended in their default place, so a hand-edited or
 * partly restored value still names every group once.
 */
fun decodeAlternatesOrder(stored: String?): List<AlternateGroup> {
    if (stored.isNullOrBlank()) return DefaultAlternatesOrder
    val named = stored.split(',')
        .mapNotNull { name -> AlternateGroup.entries.firstOrNull { it.name == name.trim() } }
        .distinct()
    return named + DefaultAlternatesOrder.filter { it !in named }
}

/** The stored form [decodeAlternatesOrder] reads back. */
fun encodeAlternatesOrder(order: List<AlternateGroup>): String =
    decodeAlternatesOrder(order.joinToString(",") { it.name }).joinToString(",") { it.name }
