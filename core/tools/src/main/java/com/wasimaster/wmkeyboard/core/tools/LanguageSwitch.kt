package com.wasimaster.wmkeyboard.core.tools

import android.view.KeyEvent

/**
 * The physical keyboard's language switch: Ctrl+Space steps forward through the
 * enabled layouts, Ctrl+Shift+Space steps back, and holding Ctrl browses a list
 * that commits on release. Pure like the rest of the hardware engine — only
 * `KeyEvent` integer constants, so everything runs in a plain JVM test.
 *
 * Meta+Space is deliberately absent: that is the system's own IME-rotation
 * chord and must keep reaching the framework.
 */

val LanguageSwitchForward = KeyChord(KeyEvent.KEYCODE_SPACE, ctrl = true)
val LanguageSwitchBackward = KeyChord(KeyEvent.KEYCODE_SPACE, ctrl = true, shift = true)

/**
 * +1 forward, -1 backward, null when this key is not a language-switch stroke.
 * [KeyChord.matches] is exact, so AltGr (Ctrl+Alt) producing a character never
 * reads as a switch, and lock bits are already masked off.
 */
fun languageSwitchDelta(keyCode: Int, metaState: Int): Int? = when {
    LanguageSwitchForward.matches(keyCode, metaState) -> 1
    LanguageSwitchBackward.matches(keyCode, metaState) -> -1
    else -> null
}

/**
 * The first candidate of a browse session, or null when there is nothing to
 * cycle. A current layout missing from the cycle (a per-field override, a
 * freshly disabled layout) starts from whichever end the step points at.
 */
fun languageCycleStart(ids: List<String>, currentId: String, delta: Int): Int? {
    if (ids.size < 2) return null
    val at = ids.indexOf(currentId)
    if (at < 0) return if (delta > 0) 0 else ids.lastIndex
    return (at + delta).mod(ids.size)
}

/** The next candidate, wrapping at both ends. */
fun languageCycleStep(candidate: Int, delta: Int, count: Int): Int =
    (candidate + delta).mod(count)

/** How many layouts the recently-used list keeps; far more than anyone enables. */
const val RECENT_LAYOUT_CAP = 16

/**
 * The 🌐 key's order when it goes by recent use (#311): the layout on screen,
 * then the other enabled layouts most recently used first, then any enabled
 * layout never switched to, in switch order. Each enabled layout once, and
 * nothing that is not enabled — the stored list outlives layouts being turned
 * off. A current layout outside the cycle (a per-field override) is left out,
 * so the first entry is then the first stop rather than where the user stands.
 */
fun recentLayoutOrder(recent: List<String>, enabled: List<String>, currentId: String): List<String> =
    (listOf(currentId) + recent + enabled).distinct().filter { it in enabled }

/**
 * The recently-used list after a switch from [from] to [to]: [to] first, [from]
 * right behind it. [from] is put there explicitly because it may never have
 * been recorded — the layout a fresh install opened on, say — and it is exactly
 * the one the next single press has to go back to.
 */
fun rememberLayoutSwitch(recent: List<String>, from: String?, to: String): List<String> =
    (listOfNotNull(to, from) + recent).distinct().take(RECENT_LAYOUT_CAP)
