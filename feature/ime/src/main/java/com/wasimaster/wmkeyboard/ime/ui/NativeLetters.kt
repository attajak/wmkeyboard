package com.wasimaster.wmkeyboard.ime.ui

import android.icu.util.LocaleData
import android.icu.util.ULocale
import com.wasimaster.wmkeyboard.core.layout.Key
import com.wasimaster.wmkeyboard.core.layout.KeyAction
import java.util.concurrent.ConcurrentHashMap

/**
 * The letters a language writes with, lowercased: ICU's main exemplar set for
 * its locale, which is CLDR's answer to "what does this alphabet contain".
 * German is a-z plus ä ö ü ß; English is a-z and nothing else, which is what
 * keeps [nativeLettersFirst] a no-op there.
 *
 * Cached per tag because [currentLayout] asks on every rebuild of the grid.
 * Empty when ICU has nothing for the tag, and in plain JVM tests, where
 * android.icu is a stub that throws: an empty set leaves every key untouched.
 */
internal object NativeLetters {
    private val cache = ConcurrentHashMap<String, Set<String>>()

    fun of(localeTag: String): Set<String> = cache.getOrPut(localeTag) {
        runCatching {
            LocaleData.getExemplarSet(ULocale.forLanguageTag(localeTag), 0)
                .mapTo(HashSet()) { it.lowercase() }
        }.getOrDefault(emptySet())
    }
}

/**
 * Discussion #382: the key with its language's own letters moved to the front
 * of its long-press popup, ahead of the digit or symbol hint that leads it.
 *
 * Every Latin layout puts a hint first (`7` on u, `@` on a) and the accents
 * after it, which is right for English and wrong for German, where a hold on u
 * is for ü far more often than for 7. With hold-to-select, entry 0 is what a
 * plain hold commits and what the corner hint draws, so the umlaut has to be
 * there, not one slide along.
 *
 * Only letters in [native] move, in the order the layout listed them; the hint
 * and any foreign accents follow in their own order, so nothing leaves the
 * popup. A key whose popup does not start with a hint is left as authored —
 * the layout already chose what comes first — and so are the keys of an
 * ambiguous grid, whose leading digit is the keypad's own.
 */
internal fun nativeLettersFirst(key: Key, native: Set<String>): Key {
    if (native.isEmpty() || key.action != KeyAction.Text || key.letters != null) return key
    val lead = key.longPress.firstOrNull() ?: return key
    if (!lead.isHintGlyph()) return key
    val own = (key.output ?: key.label).lowercase()
    val (mine, rest) = key.longPress.partition {
        val letter = it.lowercase()
        letter != own && letter in native
    }
    return if (mine.isEmpty()) key else key.copy(longPress = mine + rest)
}

/**
 * A digit or a symbol: no letter and no combining mark anywhere in it. The
 * mark test keeps the Indic layouts out, whose popups lead with a vowel sign
 * that is as much the language's own as the letters after it.
 */
private fun String.isHintGlyph(): Boolean = isNotEmpty() && codePoints().noneMatch {
    Character.isLetter(it) || Character.getType(it).toByte() in MarkTypes
}

private val MarkTypes = setOf(
    Character.NON_SPACING_MARK,
    Character.COMBINING_SPACING_MARK,
    Character.ENCLOSING_MARK,
)
