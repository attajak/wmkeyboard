package com.wasimaster.wmkeyboard.core.handwriting

import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognitionModelIdentifier
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the generated [InkModelTags] to the library it was generated from.
 * The constant is what maps the keyboard's languages onto ML Kit's models
 * before the recogniser's on-demand module is on the device (Play), so a
 * library bump that adds or drops a model has to regenerate it:
 *
 *     python3 tools/handwriting/gen_ink_models.py
 *
 * The parse in [InkTag] is pinned here too, against the subtags the library's
 * own identifier reports, since `tagFor` used to read those directly.
 */
class InkModelTagsTest {

    private val library = DigitalInkRecognitionModelIdentifier.allModelIdentifiers()
        .filter { !it.languageTag.contains("-x-") }

    @Test
    fun `the generated tag list is the library's language catalogue`() {
        assertEquals(library.map { it.languageTag }.sorted(), InkModelTags.ALL)
    }

    @Test
    fun `the generated tag list is sorted and free of duplicates`() {
        assertEquals(InkModelTags.ALL.toSortedSet().toList(), InkModelTags.ALL)
    }

    @Test
    fun `InkTag splits every tag the way the library's identifier does`() {
        for (identifier in library) {
            val parsed = InkTag(identifier.languageTag)
            assertEquals(identifier.languageTag, identifier.languageSubtag, parsed.language)
            assertEquals(identifier.languageTag, identifier.scriptSubtag.orBlankAsNull(), parsed.script)
            assertEquals(identifier.languageTag, identifier.regionSubtag.orBlankAsNull(), parsed.region)
        }
    }

    private fun String?.orBlankAsNull(): String? = this?.takeIf { it.isNotEmpty() }
}
