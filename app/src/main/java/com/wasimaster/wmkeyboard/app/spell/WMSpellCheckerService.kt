package com.wasimaster.wmkeyboard.app.spell

import android.content.Context
import android.os.UserManager
import android.service.textservice.SpellCheckerService
import android.view.textservice.SentenceSuggestionsInfo
import android.view.textservice.SuggestionsInfo
import android.view.textservice.TextInfo
import com.wasimaster.wmkeyboard.core.dictionaries.DictionaryStore
import com.wasimaster.wmkeyboard.core.directboot.DirectBoot
import com.wasimaster.wmkeyboard.core.prediction.MappedTrie
import com.wasimaster.wmkeyboard.core.prediction.PackedTrie
import com.wasimaster.wmkeyboard.core.prediction.SuggestionEngine
import com.wasimaster.wmkeyboard.core.prediction.UserLexicon
import com.wasimaster.wmkeyboard.core.prediction.WordSource
import com.wasimaster.wmkeyboard.core.transliteration.BengaliPhoneticIndex
import java.io.File
import java.text.BreakIterator
import java.util.Locale

/**
 * The keyboard's word lists as Android's spell checker (#511): the red
 * underline in other apps, and the suggestions a tap on it offers, come from
 * the same lists the strip reads.
 *
 * Chosen by the user under the system's spell checker setting; nothing here
 * runs until they do. A session answers for one language, the one the system
 * asked for: the downloaded list when there is one, English's bundled list,
 * and the user's learned words on top. A language with no list on the phone
 * answers "no opinion" for every word rather than underlining all of them.
 */
class WMSpellCheckerService : SpellCheckerService() {

    override fun createSession(): Session = WMSpellSession(this)

    private class WMSpellSession(private val context: Context) : Session() {

        /** Null when this session's language has no word list to check with. */
        private var engine: SuggestionEngine? = null

        override fun onCreate() {
            engine = engineFor(context, languageOf(locale))
        }

        override fun onGetSuggestions(textInfo: TextInfo, suggestionsLimit: Int): SuggestionsInfo =
            check(textInfo.text.orEmpty(), suggestionsLimit).withIds(textInfo)

        override fun onGetSentenceSuggestionsMultiple(
            textInfos: Array<out TextInfo>,
            suggestionsLimit: Int,
        ): Array<SentenceSuggestionsInfo> = Array(textInfos.size) { i ->
            val info = textInfos[i]
            val text = info.text.orEmpty()
            val words = BreakIterator.getWordInstance(Locale.ROOT).apply { setText(text) }
            val results = ArrayList<SuggestionsInfo>()
            val offsets = ArrayList<Int>()
            val lengths = ArrayList<Int>()
            var start = words.first()
            var end = words.next()
            while (end != BreakIterator.DONE) {
                val word = text.substring(start, end)
                if (word.any(Char::isLetter)) {
                    results += check(word, suggestionsLimit).withIds(info)
                    offsets += start
                    lengths += end - start
                }
                start = end
                end = words.next()
            }
            SentenceSuggestionsInfo(results.toTypedArray(), offsets.toIntArray(), lengths.toIntArray())
        }

        private fun check(raw: String, limit: Int): SuggestionsInfo {
            val word = raw.trim().trim('\'', '’')
            val engine = engine
            if (engine == null || word.isEmpty() || !word.any(Char::isLetter) || word.any(Char::isDigit)) {
                return SuggestionsInfo(0, EMPTY)
            }
            val ranked = synchronized(engine) {
                if (engine.isKnownWord(word)) return SuggestionsInfo(SuggestionsInfo.RESULT_ATTR_IN_THE_DICTIONARY, EMPTY)
                val fix = engine.shouldAutocorrect(word)
                (listOfNotNull(fix) + engine.suggest(word.lowercase(), previousWord = null, limit = limit + 2))
                    .filterNot { it.equals(word, ignoreCase = true) }
                    .distinct()
                    .take(limit.coerceAtLeast(1))
            }
            var attrs = SuggestionsInfo.RESULT_ATTR_LOOKS_LIKE_TYPO
            if (ranked.isNotEmpty()) attrs = attrs or SuggestionsInfo.RESULT_ATTR_HAS_RECOMMENDED_SUGGESTIONS
            return SuggestionsInfo(attrs, ranked.toTypedArray())
        }

        private fun SuggestionsInfo.withIds(info: TextInfo): SuggestionsInfo =
            apply { setCookieAndSequence(info.cookie, info.sequence) }
    }

    private companion object {
        val EMPTY = emptyArray<String>()

        fun languageOf(locale: String?): String =
            locale.orEmpty().replace('_', '-').substringBefore('-').lowercase()
                .ifEmpty { Locale.getDefault().language }

        fun engineFor(context: Context, langId: String): SuggestionEngine? {
            val unlocked = context.getSystemService(UserManager::class.java)?.isUserUnlocked != false
            val downloaded: WordSource? = if (unlocked) {
                MappedTrie.open(DictionaryStore.downloadedFile(context.filesDir, langId))
            } else {
                null
            }
            val bundled = { DictionaryStore.ensureBundled(DirectBoot.deviceContext(context), langId)?.let { MappedTrie.open(it) } }
            val list = downloaded ?: (if (langId == "en") bundled() else null) ?: return null
            val lexicon = UserLexicon(if (unlocked) File(context.filesDir, "learning/user_lexicon.json") else null)
            return if (langId == "en") {
                SuggestionEngine(list, BengaliPhoneticIndex(emptyList()), lexicon)
            } else {
                SuggestionEngine(PackedTrie.EMPTY, BengaliPhoneticIndex(emptyList()), lexicon).apply {
                    englishSources = false
                    customDictionary = list
                }
            }.apply { primaryLanguageId = langId }
        }
    }
}
