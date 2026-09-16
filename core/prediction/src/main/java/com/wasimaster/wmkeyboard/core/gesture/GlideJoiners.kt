package com.wasimaster.wmkeyboard.core.gesture

/**
 * The punctuation that sits *inside* a word and that no finger can draw.
 *
 * The glide grid is letters only — `keySpelling` admits letters and combining
 * marks and nothing else, so `LayoutSet.glideKeys` never puts a hyphen, a dot
 * or an at sign on it. A dictionary entry is free to hold them all the same:
 * `f-droid` in the personal lexicon, `e-mail` or `in-depth` in an imported word
 * list, `wasi@example.com` learned from something the user typed. Before this,
 * the decoder met the joiner's trie edge, found no key for it, and threw the
 * whole subtree away — so the word was in the dictionary, showed up in
 * completion and autocorrect, and could not be swiped (issue #230).
 *
 * The decoder steps over these characters instead: it descends the edge without
 * spending any of the stroke, so `f-droid` is drawn as `fdroid` and comes back
 * spelled with its hyphen. That is the same shape the reporter asked for —
 * indexed stripped, emitted whole — without a second index to build, keep in
 * memory or hold in step with the first.
 *
 * **The apostrophe is deliberately not here.** It has its own route onto the
 * grid (`GestureSettings.apostropheKey`, which puts `'` and `’` on a key the
 * user picks) and its own repair afterwards (`Apostrophes.fixExplicit`), built
 * that way because the contractions are exactly where a free skip does damage:
 * the shipped English list holds 89 of them, and `its`/`it's`, `were`/`we're`,
 * `ill`/`i'll`, `shed`/`she'd`, `cant`/`can't` are all one stroke. Letting them
 * through here would decide those by frequency, which is the guess that route
 * exists to refuse. When the user *has* chosen an apostrophe key the character
 * is on the grid, [isJoiner] never runs for it, and nothing here interferes.
 */
object GlideJoiners {

    /**
     * Whether [codePoint] is a joiner: punctuation a word may be spelled with
     * that a stroke passes straight through.
     *
     * Deliberately a short closed list rather than a Unicode category test. A
     * category would sweep in the quote marks, the brackets and every dash-like
     * character a word list might carry by accident, and each one it admits is
     * a subtree the decoder walks on every stroke for a word nobody swipes.
     */
    fun isJoiner(codePoint: Int): Boolean = when (codePoint) {
        '-'.code, HYPHEN, NON_BREAKING_HYPHEN -> true
        '.'.code, '@'.code, '_'.code, '&'.code, '+'.code, '/'.code -> true
        else -> false
    }

    /** U+2010 HYPHEN — what a list typeset rather than typed spells `-` as. */
    private const val HYPHEN = 0x2010

    /** U+2011 NON-BREAKING HYPHEN. */
    private const val NON_BREAKING_HYPHEN = 0x2011
}
