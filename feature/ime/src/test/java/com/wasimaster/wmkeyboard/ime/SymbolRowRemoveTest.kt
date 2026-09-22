package com.wasimaster.wmkeyboard.ime

import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import com.wasimaster.wmkeyboard.core.tools.BuiltInSymbolSets
import com.wasimaster.wmkeyboard.core.tools.SymbolSet
import com.wasimaster.wmkeyboard.core.tools.resolveSymbolSets
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * "Remove" on a held symbol row entry (#323): the write behind it, against a
 * real repository. Each test works on a set id of its own, so a DataStore that
 * outlives one test cannot hand its sets to the next.
 */
@RunWith(RobolectricTestRunner::class)
class SymbolRowRemoveTest {

    private val repository = SettingsRepository(RuntimeEnvironment.getApplication())

    private fun set(id: String): SymbolSet? = runBlocking {
        resolveSymbolSets(repository.settings.first().customSymbolSets).firstOrNull { it.id == id }
    }

    @Test
    fun `removing from a built-in set stores an override without the entry`() = runBlocking {
        repository.removeSymbolSetEntry(BuiltInSymbolSets.WEB_ID, 0, "https://")
        val web = set(BuiltInSymbolSets.WEB_ID)!!
        assertEquals(BuiltInSymbolSets.byId(BuiltInSymbolSets.WEB_ID)!!.chars.drop(1), web.chars)
        // The override is what the editor's Reset drops.
        assertEquals(
            listOf(BuiltInSymbolSets.WEB_ID),
            repository.settings.first().customSymbolSets.map { it.id }.filter { it == BuiltInSymbolSets.WEB_ID },
        )
    }

    @Test
    fun `the held copy of a repeated entry goes, and a stale index falls back to the first`() = runBlocking {
        repository.upsertSymbolSet(SymbolSet("custom_repeat", "Repeat", listOf("a", "b", "a", "c")))
        repository.removeSymbolSetEntry("custom_repeat", 2, "a")
        assertEquals(listOf("a", "b", "c"), set("custom_repeat")!!.chars)
        // Index 1 now holds "b", not "c": the text wins over the stale slot.
        repository.removeSymbolSetEntry("custom_repeat", 1, "c")
        assertEquals(listOf("a", "b"), set("custom_repeat")!!.chars)
    }

    @Test
    fun `a popup goes with its entry and the last entry stays`() = runBlocking {
        repository.upsertSymbolSet(
            SymbolSet("custom_popup", "Popup", listOf("x", "y"), popups = mapOf("x" to listOf("X"))),
        )
        repository.removeSymbolSetEntry("custom_popup", 0, "x")
        val left = set("custom_popup")!!
        assertEquals(listOf("y"), left.chars)
        assertEquals(emptyMap<String, List<String>>(), left.popups)
        repository.removeSymbolSetEntry("custom_popup", 0, "y")
        assertEquals(listOf("y"), set("custom_popup")!!.chars)
    }

    @Test
    fun `a custom set keeps its place among the others`() = runBlocking {
        repository.upsertSymbolSet(SymbolSet("custom_first", "First", listOf("1", "2")))
        repository.upsertSymbolSet(SymbolSet("custom_second", "Second", listOf("3", "4")))
        repository.removeSymbolSetEntry("custom_first", 0, "1")
        val ids = repository.settings.first().customSymbolSets.map { it.id }
        assert(ids.indexOf("custom_first") < ids.indexOf("custom_second")) { ids.toString() }
    }
}
