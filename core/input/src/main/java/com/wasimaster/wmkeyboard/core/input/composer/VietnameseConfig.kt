package com.wasimaster.wmkeyboard.core.input.composer

/**
 * Vietnamese options the composer reads at call time, so the composer itself
 * stays a parameter-less singleton — the arrangement [CjkConfig] has for
 * Pinyin, pushed from the same settings block in the service.
 */
object VietnameseConfig {

    /**
     * Whether a tone key may tone only a syllable Vietnamese actually spells.
     *
     * Off — the default — Telex marks whatever the vowel run allows, so `fas`
     * becomes `fá`. That is free tone marking, and it is what makes a Telex
     * keyboard usable for a word the rules have no opinion about. On, the tone
     * key falls through to the letter it is drawn as and `fas` stays `fas`:
     * `f` is not an onset, so there was never a word there to tone.
     *
     * It is off by default because it changes what the keys do, and a Telex
     * typist who has learned the loose behaviour would be surprised by it. The
     * strict reading is the one that has to be asked for.
     */
    @Volatile
    var strictTones: Boolean = false
}
