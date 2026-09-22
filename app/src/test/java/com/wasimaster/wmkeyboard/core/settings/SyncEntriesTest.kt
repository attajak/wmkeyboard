package com.wasimaster.wmkeyboard.core.settings

import com.wasimaster.wmkeyboard.core.settings.sync.SyncEntries
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncEntriesTest {

    private fun json(text: String): JsonElement = Json.parseToJsonElement(text)

    private fun roundTrip(text: String, deep: Boolean = true) {
        val element = json(text)
        val back = SyncEntries.implode(SyncEntries.explode(element, deep), SyncEntries.isRootArray(element))
        assertEquals(element, back)
    }

    @Test
    fun `settings stay whole per key`() {
        val settings = """{"autocorrect":{"type":"bool","value":true},"key_sound":{"type":"string","value":"soft"}}"""
        val entries = SyncEntries.explode(json(settings), deep = false)
        assertEquals(setOf("autocorrect", "key_sound"), entries.keys)
        roundTrip(settings, deep = false)
    }

    @Test
    fun `a dictionary splits by word, and a word may hold a dot`() {
        val lexicon = """{"version":2,"words":{"e.g.":{"count":3},"hello":{"count":9}}}"""
        val entries = SyncEntries.explode(json(lexicon))
        assertEquals(4, entries.size) // version, the map marker, two words
        roundTrip(lexicon)
    }

    @Test
    fun `an array of items with ids splits by id and keeps its order`() {
        val snippets = """{"snippets":[{"id":"b","text":"two"},{"id":"a","text":"one"}],"folders":[]}"""
        roundTrip(snippets)
    }

    @Test
    fun `themes, a root array, split by id`() {
        roundTrip("""[{"id":"t2","name":"Dark"},{"id":"t1","name":"Light"}]""")
    }

    @Test
    fun `an empty map is still a map after the trip`() {
        roundTrip("""{"words":{},"other":1}""")
    }

    @Test
    fun `a deleted item drops out of the order`() {
        val element = json("""{"snippets":[{"id":"a","t":1},{"id":"b","t":2},{"id":"c","t":3}]}""")
        val entries = SyncEntries.explode(element).filterKeys { !it.endsWith("\u0002b") }
        val back = SyncEntries.implode(entries, rootIsArray = false).toString()
        assertEquals("""{"snippets":[{"id":"a","t":1},{"id":"c","t":3}]}""", back)
    }

    @Test
    fun `an item the order does not know is kept, after the known ones`() {
        val element = json("""{"snippets":[{"id":"a"}]}""")
        val entries = SyncEntries.explode(element) + ("snippets\u0002z" to json("""{"id":"z"}"""))
        val back = SyncEntries.implode(entries, rootIsArray = false).toString()
        assertTrue(back, back.indexOf("\"a\"") < back.indexOf("\"z\""))
    }

    @Test
    fun `two phones' snippet 6 are two entries, and meet under two ids`() {
        val a = json("""{"snippets":[{"id":6,"text":"from A","createdAt":100}]}""")
        val b = json("""{"snippets":[{"id":6,"text":"from B","createdAt":200}]}""")
        val merged = SyncEntries.explode(a) + SyncEntries.explode(b).filterKeys { !it.endsWith("\u0003order") }
        assertEquals(2, merged.keys.count { "\u0002" in it })
        val back = SyncEntries.implode(merged, rootIsArray = false) as kotlinx.serialization.json.JsonObject
        val ids = (back["snippets"] as kotlinx.serialization.json.JsonArray).map { it.toString().substringAfter("\"id\":").substringBefore(",") }
        assertEquals(2, ids.toSet().size)
    }
}
