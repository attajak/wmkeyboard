package com.wasimaster.wmkeyboard.core.settings.sync

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * A bundle section taken apart into the entries sync compares, and put back.
 *
 * The unit of "last change wins" decides what two phones can edit at once
 * without one undoing the other. A whole section is too coarse: learning a
 * word on one phone would throw away every word the other learned since.
 * So a section is split one level down, generically, in a way every store's
 * JSON already fits:
 *
 * - an object's keys are entries (every setting in the settings section);
 * - under those, an object is split again by its keys (a dictionary's
 *   `words`, the statistics `days`, sticker `files`), and an array of objects
 *   that each carry an `id` is split by id (snippets, clipboard items), with
 *   the order kept as an entry of its own;
 * - a root array of objects with ids is split the same way (themes);
 * - anything else is one entry.
 *
 * Deeper than that the value is compared whole, which is the right grain for
 * one theme or one snippet.
 *
 * Entry keys join the parts with control characters, which no store uses in
 * a key: a dictionary word may well contain a dot.
 */
object SyncEntries {

    private const val MAP = '\u0001'
    private const val ITEM = '\u0002'
    private const val ORDER = "\u0003order"
    private const val MAP_MARK = "\u0004map"
    private const val ROOT = ""

    /**
     * [deep] false keeps each of the root object's values whole. The settings
     * section wants that: a setting is stored as `{type, value}`, and the two
     * halves of one setting must never come from two different phones.
     */
    fun explode(element: JsonElement, deep: Boolean = true): Map<String, JsonElement> {
        val out = LinkedHashMap<String, JsonElement>()
        when {
            element is JsonObject && !deep -> out.putAll(element)
            element is JsonObject -> for ((key, value) in element) explodeField(key, value, out)
            element is JsonArray && element.isIdArray() -> explodeArray(ROOT, element, out)
            else -> out[ROOT] = element
        }
        return out
    }

    private fun explodeField(key: String, value: JsonElement, out: MutableMap<String, JsonElement>) {
        when {
            value is JsonObject -> {
                // The marker keeps an empty map a map, rather than no field.
                out[key + MAP_MARK] = JsonPrimitive(true)
                for ((sub, subValue) in value) out["$key$MAP$sub"] = subValue
            }
            value is JsonArray && value.isIdArray() -> explodeArray(key, value, out)
            else -> out[key] = value
        }
    }

    private fun explodeArray(key: String, array: JsonArray, out: MutableMap<String, JsonElement>) {
        val ids = array.map { itemKey(it as JsonObject) }
        out[key + ORDER] = JsonArray(ids.map(::JsonPrimitive))
        for ((index, item) in array.withIndex()) out["$key$ITEM${ids[index]}"] = item
    }

    /**
     * The key an item syncs under: its id, and its creation time when it has
     * one. Stores that count ids from 1 hand the same id to different items on
     * different phones; the creation time tells a snippet 6 made on one phone
     * from a snippet 6 made on another, so neither is merged away.
     */
    private fun itemKey(item: JsonObject): String {
        val id = item.idOf()!!
        val created = (item["createdAt"] as? JsonPrimitive)?.contentOrNull?.takeIf { it != "0" }
        return if (created == null) id else "$id@$created"
    }

    /**
     * Rebuilds a section from [entries], or null when nothing is left of it.
     * [rootIsArray] says which shape the section's root had; the entries of a
     * root array and of a root object look different, but an empty section
     * leaves nothing to tell them apart by.
     */
    fun implode(entries: Map<String, JsonElement>, rootIsArray: Boolean): JsonElement? {
        if (entries.isEmpty()) return null
        entries[ROOT]?.let { if (entries.size == 1) return it }
        if (rootIsArray) return buildArray(ROOT, entries)

        val fields = LinkedHashMap<String, JsonElement>()
        val maps = LinkedHashMap<String, LinkedHashMap<String, JsonElement>>()
        val arrays = LinkedHashSet<String>()
        for ((key, value) in entries) {
            when {
                key.endsWith(MAP_MARK) -> maps.getOrPut(key.removeSuffix(MAP_MARK)) { LinkedHashMap() }
                key.endsWith(ORDER) -> arrays += key.removeSuffix(ORDER)
                MAP in key -> {
                    val field = key.substringBefore(MAP)
                    maps.getOrPut(field) { LinkedHashMap() }[key.substringAfter(MAP)] = value
                }
                ITEM in key -> arrays += key.substringBefore(ITEM)
                else -> fields[key] = value
            }
        }
        for ((field, map) in maps) fields[field] = JsonObject(map)
        for (field in arrays) fields[field] = buildArray(field, entries)
        return JsonObject(fields)
    }

    /**
     * The items under [key], in the stored order, then any the order does not
     * know yet. An item missing from the entries is a deleted one, and drops
     * out of the order with it.
     */
    private fun buildArray(key: String, entries: Map<String, JsonElement>): JsonArray {
        val prefix = "$key$ITEM"
        val items = entries.filterKeys { it.startsWith(prefix) }.mapKeys { it.key.removePrefix(prefix) }
        val order = (entries[key + ORDER] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            .orEmpty()
        val ordered = order.filter { it in items } + items.keys.filterNot { it in order }.sorted()
        return JsonArray(renumberClashes(ordered.distinct().map { items.getValue(it) }))
    }

    /**
     * Two items that met here under one id, from two phones that counted
     * the same way, keep the first and give the later ones a new number past
     * the largest. A string id gets a suffix instead. Without this the store
     * would hold two things one edit or one delete could not tell apart.
     */
    private fun renumberClashes(items: List<JsonElement>): List<JsonElement> {
        val ids = items.mapNotNull { (it as? JsonObject)?.idOf() }
        if (ids.size == ids.toSet().size) return items
        var next = (ids.mapNotNull { it.toLongOrNull() }.maxOrNull() ?: 0L) + 1
        val seen = HashSet<String>()
        return items.map { item ->
            val obj = item as? JsonObject ?: return@map item
            val id = obj.idOf() ?: return@map item
            if (seen.add(id)) return@map item
            val fresh = if (id.toLongOrNull() != null) JsonPrimitive(next++) else JsonPrimitive("$id-${next++}")
            seen += fresh.content
            JsonObject(obj + ("id" to fresh))
        }
    }

    fun isRootArray(element: JsonElement): Boolean = element is JsonArray

    private fun JsonArray.isIdArray(): Boolean =
        isNotEmpty() && all { it is JsonObject && it.idOf() != null } &&
            map { (it as JsonObject).idOf() }.toSet().size == size

    private fun JsonObject.idOf(): String? =
        (this["id"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull?.takeIf { it.isNotEmpty() }
}
