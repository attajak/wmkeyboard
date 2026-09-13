package com.wasimaster.wmkeyboard.ime

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Guards the ML Kit tools against the shrinker failure that shipped in 0.5.6
 * and 0.5.7 (#146): every scanner crashed in release with a bare
 * NullPointerException, and worked in every debug build.
 *
 * ML Kit's libraries register themselves through Firebase's component system.
 * Each one names a registrar class in its manifest, and `ComponentDiscovery`
 * instantiates it with `Class.forName` and `getDeclaredConstructor()`. The
 * consumer rule firebase-components ships keeps the class but names no
 * members, and R8 in full mode keeps no member it is not told to — so the
 * no-arg constructor went, discovery skipped every registrar, and the first
 * `getClient()` found no `SharedPrefManager` to log with.
 *
 * `proguard-rules.pro` now keeps that constructor itself. This test holds the
 * rule in place and checks that the two facts it rests on are still true, so
 * an ML Kit or Firebase upgrade that changes how registrars are found fails
 * here instead of on a user's phone.
 *
 * Reads class files as ISO-8859-1 text, the same trick as the plugin shrinker tests.
 */
class MlKitRegistrarRulesTest {

    @Test
    fun `proguard keeps the no-arg constructor of every component registrar`() {
        val rules = proguardRules()
        assertTrue(
            "proguard-rules.pro lost the ComponentRegistrar constructor keep; " +
                "release builds will have no ML Kit components again (#146)",
            REGISTRAR_KEEP.containsMatchIn(rules),
        )
    }

    @Test
    fun `component discovery still instantiates registrars by reflection`() {
        // Positive control: if Firebase stops reaching registrars reflectively,
        // the keep rule is dead weight and this test should be revisited, not
        // silently kept passing.
        val discovery = constantPoolOf(COMPONENT_DISCOVERY)
        assumeTrue("firebase-components is not on this variant's classpath", discovery != null)
        assertTrue(
            "ComponentDiscovery no longer looks registrars up by name",
            "forName" in discovery!! && "getDeclaredConstructor" in discovery,
        )
    }

    @Test
    fun `the common registrar is what the rule matches`() {
        // The rule keys on `implements ComponentRegistrar` and names `<init>()`.
        // Both have to hold for the class the crash went through, or the rule
        // matches nothing and keeps nothing.
        val registrar = runCatching { Class.forName(COMMON_REGISTRAR) }.getOrNull()
        assumeTrue("ML Kit is not on this variant's classpath (lite flavor)", registrar != null)
        val implementsRegistrar = registrar!!.interfaces.any { it.name == COMPONENT_REGISTRAR }
        assertTrue("$COMMON_REGISTRAR no longer implements $COMPONENT_REGISTRAR", implementsRegistrar)
        val constructor = runCatching { registrar.getDeclaredConstructor() }.getOrNull()
        assertTrue("$COMMON_REGISTRAR has no no-arg constructor for discovery to call", constructor != null)
    }

    private fun proguardRules(): String =
        listOf(File("proguard-rules.pro"), File("app/proguard-rules.pro"))
            .firstOrNull { it.isFile }
            ?.readText()
            ?: error("cannot find proguard-rules.pro from ${File("").absolutePath}")

    private fun constantPoolOf(internalName: String): String? =
        javaClass.classLoader?.getResourceAsStream("$internalName.class")
            ?.use { String(it.readBytes(), Charsets.ISO_8859_1) }

    private companion object {
        const val COMPONENT_REGISTRAR = "com.google.firebase.components.ComponentRegistrar"
        const val COMPONENT_DISCOVERY = "com/google/firebase/components/ComponentDiscovery"
        const val COMMON_REGISTRAR = "com.google.mlkit.common.internal.CommonComponentRegistrar"

        /** The rule, whitespace-insensitive, so a reflow does not read as a loss. */
        val REGISTRAR_KEEP = Regex(
            """-keep\s+class\s+\*\s+implements\s+com\.google\.firebase\.components\.ComponentRegistrar\s*\{\s*<init>\(\);\s*}""",
        )
    }
}
