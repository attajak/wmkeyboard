package com.wasimaster.wmkeyboard.core.settings

import androidx.annotation.StringRes
import com.wasimaster.wmkeyboard.core.prediction.OctopusKind
import com.wasimaster.wmkeyboard.settings.R

/**
 * Where a floating word is drawn relative to the key it belongs to.
 *
 * The BlackBerry Z10 offered "In-Letter", "In-Column" and off; this is the
 * same choice made about geometry rather than about surfaces, because the
 * suggestion strip stays either way.
 */
enum class OctopusPlacement(@StringRes val labelRes: Int) {
    /**
     * Floating in the gap above the key, straddling its top edge — what both
     * the Z10 and the Octopus tweak did. The keyboard does not get any taller;
     * the words live in the space the rows already leave between them.
     */
    FLOAT(R.string.core_settings_octopus_placement_float_label),

    /**
     * Each row reserves a lane of its own, so nothing ever overlaps a key or a
     * neighbouring word. Honest and calm, at the cost of a taller keyboard.
     */
    STRIP(R.string.core_settings_octopus_placement_strip_label),

    /**
     * Inside the key, above its letter. Nothing can collide with anything, but
     * the word and the letter share a very small box.
     */
    IN_KEY(R.string.core_settings_octopus_placement_in_key_label),
}

/**
 * How readily a flick up off a key is taken as picking its word rather than as
 * the start of a glide stroke.
 *
 * **Direction decides, not speed.** Speed was the first thing tried and it was
 * wrong: a flick ends by decelerating as the finger leaves the screen, so any
 * rule about how fast the last few milliseconds were rejects exactly the
 * gesture it is meant to accept. What separates the two reliably is where the
 * stroke went — up, and more or less straight.
 *
 * So a tier is a cone and a straightness. Inside the cone and straight enough,
 * the word is taken however long the stroke took; outside either, it is a
 * glide. This does make a glide that opens straight upward off a key carrying a
 * word harder to start, and that is the deliberate trade: a board with words on
 * the keys is a board whose owner asked for them.
 */
enum class OctopusFlickSensitivity(
    @StringRes val labelRes: Int,
    /** Half-width of the upward cone, in degrees. */
    val coneDegrees: Float,
    /** How much longer than the straight line the stroke may wander. */
    val maxDetour: Float,
) {
    /** For heavy gliders: an unmistakably vertical, unmistakably straight flick. */
    STRICT(R.string.core_settings_octopus_flick_strict_label, 25f, 1.2f),

    /** The default. */
    BALANCED(R.string.core_settings_octopus_flick_balanced_label, 35f, 1.4f),

    /** Nearly anything upward. For boards where the words matter more than the swipes. */
    RELAXED(R.string.core_settings_octopus_flick_relaxed_label, 50f, 1.7f),
}

/**
 * What the keys carry while a glide stroke is being drawn (issue #166).
 *
 * The board answers a different question under a moving finger than it does
 * under a still one, and until now it had no way to be told so: silencing the
 * stroke meant switching completions off in [OctopusSettings.kinds], which also
 * emptied the keys while tap typing. Two surfaces, one lever. This is the
 * stroke's own lever, and [OctopusSettings.kinds] is left to say what a *typed*
 * buffer may offer.
 *
 * Whatever is chosen here, the board at the lift is unchanged: a finished glide
 * publishes the next-word offer, because the word it was reading has landed.
 */
enum class OctopusDuringGlide(@StringRes val labelRes: Int) {
    /**
     * Nothing floats until the finger lifts. The default, and what the glide
     * typer who asked for this wanted: mid-stroke a completion cannot be taken
     * and a correction cannot be judged, so the words are movement under a
     * moving finger and nothing else.
     */
    NOTHING(R.string.core_settings_octopus_glide_nothing_label),

    /**
     * The readings the decoder is still holding, each above the key that
     * reaches it: carry on to the P and `hello` becomes `help`. The one thing
     * on the keyboard that can *show the way* to an alternate rather than list
     * it, and what the board did before this setting existed.
     *
     * Still subject to [OctopusSettings.kinds]: these carry on the word being
     * drawn, so they are completions, and a board that forbids completions
     * stays bare (#209).
     */
    ALTERNATES(R.string.core_settings_octopus_glide_alternates_label),

    /**
     * What would follow the word the stroke is reading, above the key that
     * starts it — the next word, offered before the current one has landed.
     *
     * For a glide typer this is the point of the whole feature: a next-word
     * offer that arrives while the finger is still moving can be flicked
     * without breaking rhythm, where the strip's own next-word offer costs a
     * look and a reach. The prediction is conditioned on the stroke's leading
     * reading, so it moves as that reading moves and then becomes, unchanged,
     * the board the lift publishes.
     *
     * Subject to [OctopusSettings.kinds] the same way: a board that forbids
     * next words stays bare.
     */
    NEXT_WORD(R.string.core_settings_octopus_glide_next_word_label),
}

/**
 * The octopus: a predicted word drawn over the key you would press next to
 * reach it, picked by flicking up on that key (discussion #102).
 *
 * Its own object rather than ten more fields on [KeyboardSettings], which is
 * near the JVM's limit on constructor parameters — and because these only ever
 * make sense together.
 *
 * Off by default. It is a second prediction surface on a keyboard that already
 * has one, and it wants to be found rather than met.
 */
data class OctopusSettings(
    val enabled: Boolean = false,
    val placement: OctopusPlacement = OctopusPlacement.FLOAT,
    /**
     * How many words may float at once: [SPARSE_DENSITY] is the real Z10, a
     * quiet board with three or four offers, and [MAX_DENSITY] is the Octopus
     * tweak's one-per-key. Above [DENSE_FROM] the trie is fanned to fill keys
     * the ranked candidates left empty, and the quietening score floor is
     * lifted, since filling the board is the point of asking for it.
     */
    val density: Int = SPARSE_DENSITY,
    /** Which kinds of prediction the user allows onto the keys. */
    val kinds: Set<OctopusKind> = OctopusKind.entries.toSet(),
    /**
     * What the keys carry while a glide is being drawn, which is a separate
     * question from [kinds] (#166). [OctopusDuringGlide.NOTHING] by default:
     * the stroke's own board is the one a glide typer reported as clutter, and
     * the board that matters to them is the next-word one at the lift, which
     * this setting does not touch.
     */
    val duringGlide: OctopusDuringGlide = OctopusDuringGlide.NOTHING,
    /** A flick up off a key commits its word. */
    val flickCommits: Boolean = true,
    /** Tapping the floating word itself commits it. */
    val tapCommits: Boolean = true,
    val flickSensitivity: OctopusFlickSensitivity = OctopusFlickSensitivity.BALANCED,
    /** Word size, as a multiple of the corner hint's 10sp base. */
    val fontScale: Float = 1.0f,
    /**
     * Hide a key's corner hint while it is carrying a word. Per key, not per
     * board: a key with nothing to say keeps its hint.
     *
     * Off by default: on a real board the word sits clear of the corner, so
     * hiding the hint gave up something for a collision that was not happening.
     */
    val suppressHints: Boolean = false,
    /**
     * Also float words above characters only a long press reaches. Off by
     * default, because a press of that key would not produce the word, and a
     * key that promises something a press does not deliver is worse than a key
     * that promises nothing.
     */
    val longPressKeys: Boolean = false,
    /**
     * How many words one key may carry, stacked away from the key: the best
     * nearest the key, the next above it (#136). One by default, which is what
     * both reference boards drew and all a phone-sized key has room for; a
     * tablet's tall keys can take a second and a third without the stacks of
     * neighbouring rows meeting. [density] still caps the board as a whole.
     */
    val wordsPerKey: Int = 1,
) {
    /** Whether this board should fan the tries to fill the keys it can. */
    val dense: Boolean get() = density >= DENSE_FROM

    companion object {
        val WORDS_PER_KEY_RANGE = 1..3

        const val SPARSE_DENSITY = 6
        const val MIN_DENSITY = 3

        /** One per letter key on a Latin board. */
        const val MAX_DENSITY = 26

        /**
         * Where "a few good words" turns into "fill the board". Below this the
         * ranked candidates alone are enough and the fan-out is not worth its
         * trie walks.
         */
        const val DENSE_FROM = 9

        val FONT_SCALE_RANGE = 0.6f..1.6f
    }
}
