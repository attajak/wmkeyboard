package com.wasimaster.wmkeyboard.ime

import android.content.Context
import androidx.core.content.edit
import com.wasimaster.wmkeyboard.core.directboot.DirectBoot

/**
 * The languages already told that a word list they do not have would make
 * glide typing (#219) or a phonetic layout (#239) work better.
 *
 * On disk rather than in the service's own memory, because the promise the
 * chip makes is "once per language" and the system stops the IME process soon
 * after the keyboard goes away: an in-memory set kept that promise per
 * process, so the chip came back every time the keyboard did — reported as
 * the chip appearing "frequently" (discussion #364).
 *
 * Its own `SharedPreferences` file, for [PersistentSurfacePrefs]' reasons:
 * this records what the user has already been shown rather than anything they
 * set, it has no business in a backup, and the settings class is near its
 * argument ceiling. Device-protected storage, because the keyboard types on
 * the lock screen, where the credential-protected one cannot be opened.
 */
class WordListOfferPrefs(context: Context) {

    private val prefs = DirectBoot.deviceContext(context)
        .getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    private val told: MutableSet<String> =
        HashSet(prefs.getStringSet(KEY_TOLD, emptySet()).orEmpty())

    /**
     * Records [key] as told and answers whether that was news — the same
     * contract as `MutableSet.add`, so the call sites read as they did when
     * this was a plain set. The stored copy is rewritten only when it was.
     */
    fun add(key: String): Boolean {
        if (!told.add(key)) return false
        prefs.edit { putStringSet(KEY_TOLD, HashSet(told)) }
        return true
    }

    private companion object {
        const val FILE_NAME = "word_list_offers"
        const val KEY_TOLD = "told"
    }
}
