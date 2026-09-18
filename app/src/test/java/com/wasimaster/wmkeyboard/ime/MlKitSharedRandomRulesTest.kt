package com.wasimaster.wmkeyboard.ime

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Guards handwriting downloads against the shrinker failure that shipped in
 * 0.5.9 (#235): every model download failed at once in release with "The
 * download did not work", and worked in every debug build.
 *
 * Digital Ink's jar carries two `java.util.Random` subclasses whose `setSeed`
 * throws once a final boolean field is true. The field is set after `super()`
 * returns, and `Random()` calls `setSeed` from inside `super()`, so the guard
 * reads false at that moment. R8 sees the field only ever written true, folds
 * the check to "always throw", and the class initializer that builds the pair
 * dies with ExceptionInInitializerError under the download manager.
 *
 * `proguard-rules.pro` keeps those classes and their fields. This test holds
 * the rule in place and checks that the class it was written for still looks
 * the way the rule assumes, so a Digital Ink upgrade that renames or reshapes
 * it fails here and gets a second look.
 *
 * Reads class files as ISO-8859-1 text, the same trick as the plugin shrinker tests.
 */
class MlKitSharedRandomRulesTest {

    @Test
    fun `proguard keeps the fields of Google's shaded Random subclasses`() {
        val rules = listOf(File("proguard-rules.pro"), File("app/proguard-rules.pro"))
            .firstOrNull { it.isFile }
            ?.readText()
            ?: error("cannot find proguard-rules.pro from ${File("").absolutePath}")
        assertTrue(
            "proguard-rules.pro lost the shaded Random keep; release handwriting downloads " +
                "will die in a class initializer again (#235)",
            RANDOM_KEEP.containsMatchIn(rules),
        )
    }

    @Test
    fun `the class the rule was written for is a guarded Random in the shaded package`() {
        val shared = runCatching { Class.forName(SHARED_RANDOM) }.getOrNull()
        assumeTrue("Digital Ink 19.0.0 is not on this variant's classpath", shared != null)
        assertTrue("$SHARED_RANDOM no longer extends java.util.Random", shared!!.superclass == java.util.Random::class.java)
        assertTrue(
            "$SHARED_RANDOM has no boolean field for R8 to fold",
            shared.declaredFields.any { it.type == Boolean::class.javaPrimitiveType },
        )
        val pool = javaClass.classLoader?.getResourceAsStream("${SHARED_RANDOM.replace('.', '/')}.class")
            ?.use { String(it.readBytes(), Charsets.ISO_8859_1) }
        assertTrue("$SHARED_RANDOM no longer guards setSeed", pool != null && "shared Random object" in pool)
    }

    private companion object {
        const val SHARED_RANDOM = "com.google.android.gms.internal.mlkit_vision_digital_ink.zzaim"

        /** The rule, whitespace-insensitive, so a reflow does not read as a loss. */
        val RANDOM_KEEP = Regex(
            """-keep\s+class\s+com\.google\.android\.gms\.internal\.\*\*\s+extends\s+java\.util\.Random\s*\{\s*<fields>;\s*}""",
        )
    }
}
