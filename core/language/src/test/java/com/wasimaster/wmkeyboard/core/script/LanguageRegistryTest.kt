package com.wasimaster.wmkeyboard.core.script

import com.wasimaster.wmkeyboard.core.layout.BuiltInLayouts
import com.wasimaster.wmkeyboard.core.layout.LEGACY_MODE_LANG
import com.wasimaster.wmkeyboard.core.layout.LEGACY_MODE_LAYOUT
import com.wasimaster.wmkeyboard.core.layout.LayoutFile
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * Pins the registry that replaced the `InputMode`/`KeyboardLanguage` enums: every
 * language resolves to a real script, its locale parses, and the migration maps
 * cover every name the old enum could have stored — the two seams an existing
 * install passes through on upgrade.
 */
class LanguageRegistryTest {

    /** The nine former `InputMode` values. If one is missing from a map, an upgrade loses it. */
    private val legacyModeNames = listOf(
        "ENGLISH", "AZERTY", "DVORAK", "AVRO", "PROBHAT", "JATIYA", "FRENCH", "GERMAN", "SPANISH",
    )

    @Test
    fun `every language uses a registered script`() {
        for (lang in LanguageRegistry.all) {
            assertTrue(
                "language ${lang.id} names script ${lang.script} with no ScriptDef",
                ScriptRegistry.isRegistered(lang.script),
            )
        }
    }

    @Test
    fun `every language locale tag parses to a language`() {
        for (lang in LanguageRegistry.all) {
            val locale = Locale.forLanguageTag(lang.localeTag)
            assertTrue(
                "language ${lang.id} has an unparseable locale tag '${lang.localeTag}'",
                locale.language.isNotEmpty(),
            )
        }
    }

    @Test
    fun `every language layout id is a real built-in or asset layout`() {
        // Asset layout ids are not compiled in, so read them off disk the way a
        // unit test reaches the shipped emoji catalog (AssetManager is Android).
        val known = BuiltInLayouts.all.mapTo(HashSet()) { it.id } + assetLayoutIds()
        for (lang in LanguageRegistry.all) {
            for (layoutId in lang.layoutIds) {
                assertTrue(
                    "language ${lang.id} lists unknown layout '$layoutId'",
                    layoutId in known,
                )
            }
        }
    }

    /**
     * The reverse of the test above, and the one that was missing: a layout can
     * be compiled in, counted by `ShippedCountsTest` and written up in the docs
     * while no language offers it, and then there is no way to turn it on.
     *
     * That is not hypothetical. T9 and Compact QWERTY shipped in `880e6764` and
     * were unreachable until discussion #103 asked where they were: the English
     * screen lists [LanguageDef.layoutIds], not every layout whose `langId` says
     * English, so adding a spec to [BuiltInLayouts.all] is only half of shipping
     * it. Assets are left out of this: those are added by dropping a file in,
     * and the ones a language does not list are reachable through import.
     */
    @Test
    fun `every built-in layout is offered by some language`() {
        val offered = LanguageRegistry.all.flatMapTo(HashSet()) { it.layoutIds }
        for (layout in BuiltInLayouts.all) {
            assertTrue(
                "built-in layout '${layout.id}' (${layout.name}) is listed by no language, " +
                    "so nothing in the app can turn it on",
                layout.id in offered,
            )
        }
    }

    @Test
    fun `an unknown id resolves to the generic language, never null`() {
        assertSame(LanguageRegistry.GENERIC, LanguageRegistry.byId("zz-Xxxx"))
        assertSame(LanguageRegistry.GENERIC, LanguageRegistry.byId(""))
    }

    @Test
    fun `byLocale matches on the primary subtag`() {
        assertEquals("fr", LanguageRegistry.byLocale("fr-FR")?.id)
        assertEquals("en", LanguageRegistry.byLocale("en")?.id)
        assertEquals("bn", LanguageRegistry.byLocale("bn_BD")?.id)
        assertEquals(null, LanguageRegistry.byLocale("zz"))
    }

    @Test
    fun `languageOf maps every built-in layout to its language`() {
        assertEquals("en", LanguageRegistry.languageOf(BuiltInLayouts.QWERTY_ID).id)
        assertEquals("en", LanguageRegistry.languageOf(BuiltInLayouts.AZERTY_ID).id)
        assertEquals("bn", LanguageRegistry.languageOf(BuiltInLayouts.AVRO_ID).id)
        assertEquals("hi", LanguageRegistry.languageOf(BuiltInLayouts.HINDI_ID).id)
        assertEquals("hi", LanguageRegistry.languageOf(BuiltInLayouts.HINDI_PHONETIC_ID).id)
        // InScript leads: enabling Hindi must not hand anyone a Latin grid.
        assertEquals(BuiltInLayouts.HINDI_ID, LanguageRegistry.byId("hi").layoutIds.first())
        assertEquals("de", LanguageRegistry.languageOf(BuiltInLayouts.GERMAN_ID).id)
    }

    @Test
    fun `legacy language map covers every old mode name and lands on a real language`() {
        assertEquals(legacyModeNames.toSet(), LEGACY_MODE_LANG.keys)
        for ((mode, langId) in LEGACY_MODE_LANG) {
            assertNotNull("$mode maps to nothing", langId)
            assertFalse(
                "$mode maps to the generic fallback instead of a real language",
                LanguageRegistry.byId(langId) === LanguageRegistry.GENERIC,
            )
        }
    }

    @Test
    fun `legacy layout map covers every old mode name and lands on a real built-in`() {
        assertEquals(legacyModeNames.toSet(), LEGACY_MODE_LAYOUT.keys)
        val builtInIds = BuiltInLayouts.all.mapTo(HashSet()) { it.id }
        for ((mode, layoutId) in LEGACY_MODE_LAYOUT) {
            assertTrue("$mode maps to unknown layout '$layoutId'", layoutId in builtInIds)
        }
    }

    /** The two maps must agree: a mode's layout belongs to the mode's language. */
    @Test
    fun `the two legacy maps are consistent`() {
        for (mode in legacyModeNames) {
            val langId = LEGACY_MODE_LANG.getValue(mode)
            val layoutId = LEGACY_MODE_LAYOUT.getValue(mode)
            assertTrue(
                "$mode: layout '$layoutId' is not in language '$langId'",
                layoutId in LanguageRegistry.byId(langId).layoutIds,
            )
        }
    }

    /** The ids of the shipped `.wmlayout.json` files under `assets/layouts`, read from disk. */
    private fun assetLayoutIds(): Set<String> =
        File("../../app/src/main/assets/layouts")
            .listFiles { f -> f.name.endsWith(".${LayoutFile.FILE_EXTENSION}") }
            .orEmpty()
            .mapNotNull { LayoutFile.decode(it.readText())?.layout?.id }
            .toHashSet()
}
