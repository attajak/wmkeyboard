package com.wasimaster.wmkeyboard.core.transliteration

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Every line of the key maps the settings page draws, run through the engine
 * it describes. A rule changed in [AvroPhonetic] or [HindiPhonetic] without
 * its map fails here rather than teaching the user a key that no longer works.
 */
class PhoneticKeyMapTest {

    private fun check(map: PhoneticKeyMap, consonant: String, t: (String) -> String) {
        for (entry in map.vowels) for (key in entry.keys) {
            // Followed by a consonant, because Hindi lengthens a word-final
            // short vowel and the map shows the vowel itself.
            assertEquals("vowel $key", entry.text + t(consonant), t(key + consonant))
            assertEquals("vowel sign $key", t(consonant) + entry.sign + t(consonant), t(consonant + key + consonant))
        }
        for (entry in map.consonants) for (key in entry.keys) {
            assertEquals("consonant $key", entry.text, t(key))
        }
        for (entry in map.signs) {
            val example = entry.example
            if (example != null) {
                assertEquals("example ${example.first}", example.second, t(example.first))
            } else {
                for (key in entry.keys) assertEquals("sign $key", entry.text, t(key))
            }
        }
    }

    @Test fun avroMapMatchesAvro() = check(PhoneticKeyMaps.avro, "k", AvroPhonetic::transliterate)

    @Test fun hindiMapMatchesHindiPhonetic() = check(PhoneticKeyMaps.hindi, "p", HindiPhonetic::transliterate)

    @Test fun everySpellingOfAnExampleWorks() {
        // The example only spells one of an entry's keys; the rest must write
        // the same word.
        for (entry in PhoneticKeyMaps.avro.signs) {
            val (roman, script) = entry.example ?: continue
            val first = entry.keys.first()
            if (first !in roman) continue
            for (key in entry.keys.drop(1)) {
                assertEquals("${entry.keys} as $key", script, AvroPhonetic.transliterate(roman.replace(first, key)))
            }
        }
    }
}
