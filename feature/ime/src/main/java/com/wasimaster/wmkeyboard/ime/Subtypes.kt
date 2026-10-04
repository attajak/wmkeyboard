package com.wasimaster.wmkeyboard.ime

import android.view.inputmethod.InputMethodSubtype
import android.view.inputmethod.InputMethodSubtype.InputMethodSubtypeBuilder
import com.wasimaster.wmkeyboard.core.layout.LayoutSpec
import com.wasimaster.wmkeyboard.core.layout.language
import com.wasimaster.wmkeyboard.core.layout.script
import com.wasimaster.wmkeyboard.core.script.ScriptId

/**
 * Bridges the app's layouts to Android's [InputMethodSubtype] system so the OS
 * language switcher (the "Choose input method" sheet, the launcher globe on some
 * devices) lists every enabled layout and hands selection back to us.
 *
 * `activeLayoutId` stays the single source of truth; the OS is mirrored
 * best-effort — see `WMKeyboardService.registerSubtypes` /
 * `mirrorSubtypeToOs` / `onCurrentInputMethodSubtypeChanged`. The subtype↔layout
 * bridge is the [LAYOUT_ID_KEY] entry in the subtype's extra-value string.
 */

/** Extra-value key carrying the layout id a subtype stands for. */
private const val LAYOUT_ID_KEY = "layoutId"

/** The extra-value string encoding [layoutId] for a subtype (`layoutId=<id>`). */
internal fun layoutExtraValue(layoutId: String): String = "$LAYOUT_ID_KEY=$layoutId"

/**
 * The framework's own extra-value key: when a subtype's name resource holds a
 * `%s`, this string is formatted into it instead of the locale's display name.
 * `InputMethodSubtype.EXTRA_KEY_UNTRANSLATABLE_STRING_IN_SUBTYPE_NAME`, which is
 * hidden API but has read this key since API 19.
 */
private const val NAME_OVERRIDE_KEY = "UntranslatableReplacementStringInSubtypeName"

/**
 * The whole extra-value string for a subtype: the layout id, and the switcher
 * label when [nameOverride] asks for one. The value is a comma-separated list
 * of `key=value` pairs, so a name carrying either separator is flattened
 * rather than allowed to break the pair after it.
 */
internal fun subtypeExtraValue(layoutId: String, nameOverride: String?): String {
    val base = layoutExtraValue(layoutId)
    val safe = nameOverride?.replace(',', ' ')?.replace('=', ' ')?.trim().orEmpty()
    if (safe.isEmpty()) return base
    return "$base,$NAME_OVERRIDE_KEY=$safe"
}

/**
 * The label the OS switcher shows for a language in place of the one it derives
 * from the locale, or null to leave the framework's name alone.
 *
 * Android names `hi-Latn` "Hindi (Latin)", which next to "Hindi (India)" in the
 * same sheet says nothing about which of the two is the phonetic layout (#497).
 * The romanized languages are the ones this app deliberately names differently
 * from their locale, "Hinglish · Hindi (Romanized)", so that name goes out to
 * the switcher too. Every other language keeps the framework's locale name,
 * which is translated into the phone's language where this one is not.
 */
internal fun subtypeNameOverride(languageId: String, displayName: String): String? =
    displayName.takeIf { languageId.endsWith(ROMANIZED_ID_SUFFIX) && it.isNotBlank() }

/** How the romanized languages' ids end: `hi_rom`, `bn_rom`, `ar_rom`, and the rest. */
private const val ROMANIZED_ID_SUFFIX = "_rom"

/**
 * A 31-bit id derived only from the layout id, so a layout keeps the same
 * subtype identity across process restarts and app updates. Android persists the
 * user's enabled-subtype choice by this int — a value that shifted when, say, a
 * display name changed would silently disable the user's picks. Same algorithm
 * as [String.hashCode], forced non-negative (the framework rejects some negative
 * ids).
 *
 * Also what `InputMethodSubtype.hashCode()` returns for our subtypes, and that
 * is the identity `setExplicitlyEnabledInputMethodSubtypes` works in — where 0
 * means "unspecified", so it is nudged off that one value.
 */
internal fun stableSubtypeId(layoutId: String): Int {
    var h = 0
    for (c in layoutId) h = 31 * h + c.code
    return (h and 0x7fffffff).let { if (it == 0) 1 else it }
}

/**
 * Reads the layout id out of a subtype's raw extra-value string, or null when the
 * subtype is not one of ours (method.xml declares none, but the framework hands
 * back a subtype we never registered often enough — a stale one from before an
 * update, say — to be worth the null). Pure so it is unit-testable without the
 * Android framework's [InputMethodSubtype].
 */
internal fun layoutIdFromExtraValue(extraValue: String?): String? {
    if (extraValue.isNullOrEmpty()) return null
    for (pair in extraValue.split(',')) {
        val eq = pair.indexOf('=')
        if (eq > 0 && pair.substring(0, eq) == LAYOUT_ID_KEY) {
            return pair.substring(eq + 1).ifEmpty { null }
        }
    }
    return null
}

/**
 * One OS subtype describing [spec]: locale + language tag for the switcher's
 * label, the layout id in the extra value so [layoutIdOf] can map a selection
 * back. Both `subtypeLocale` (legacy underscore form) and `languageTag` are set
 * — the tag is the modern field, but some OEM switchers still read the locale.
 *
 * [nameResId] chooses the label the switcher shows: 0 (the default) lets the
 * framework derive it from the locale (the plain language name); a string
 * resource containing `%s` is formatted with that locale name, e.g. a
 * "WM Keyboard · %s" resource yields an app-name-first label. A romanized
 * language puts its own name in that `%s` instead ([subtypeNameOverride]), so
 * with no resource asked for it still needs one, the bare "%s".
 */
fun subtypeFor(spec: LayoutSpec, nameResId: Int = 0): InputMethodSubtype {
    val lang = spec.language()
    val asciiCapable = spec.script().id == ScriptId.LATIN
    val nameOverride = subtypeNameOverride(lang.id, lang.displayName)
    val resId = if (nameOverride != null && nameResId == 0) R.string.subtype_plain_label else nameResId
    return InputMethodSubtypeBuilder()
        .setSubtypeMode("keyboard")
        .setSubtypeNameResId(resId)
        .setSubtypeLocale(lang.localeTag.replace('-', '_'))
        .setLanguageTag(lang.localeTag)
        .setSubtypeExtraValue(subtypeExtraValue(spec.id, nameOverride))
        .setIsAsciiCapable(asciiCapable)
        .setOverridesImplicitlyEnabledSubtype(false)
        .setSubtypeId(stableSubtypeId(spec.id))
        .build()
}

/** The layout id a subtype stands for, or null if it is not one of ours. */
fun layoutIdOf(subtype: InputMethodSubtype?): String? =
    layoutIdFromExtraValue(subtype?.extraValue)
