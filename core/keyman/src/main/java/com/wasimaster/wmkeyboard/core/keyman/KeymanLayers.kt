package com.wasimaster.wmkeyboard.core.keyman

import com.wasimaster.wmkeyboard.core.layout.LayoutLayer

/**
 * Keyman's layer names, both ways, and what a layer name means to the rules.
 *
 * A converted layout keeps every layer a Keyman keyboard defines. Three of them
 * have homes of our own — `default` is our letters, `numeric` our `?123` page
 * and `symbol` the page after it — and every other one keeps its Keyman id
 * behind [PREFIX]. The prefix is not decoration: fifteen keyboards in the corpus
 * call a layer `symbols` and one calls one `number`, both of which are names our
 * own layers answer to, and the second is the keypad a number field opens.
 */
object KeymanLayers {

    /** Keyman's name for the base layer, which `&layer` starts on. */
    const val DEFAULT: String = "default"

    /** What every Keyman layer without a home of ours is keyed under. */
    const val PREFIX: String = "k:"

    /** Keyman's shift layer, drawn while shift is on. */
    const val SHIFT: String = PREFIX + "shift"

    /** Keyman's caps layer, drawn while caps lock is on. */
    const val CAPS: String = PREFIX + "caps"

    /** Every Ctrl and Alt bit of a Keyman modifier mask, either hand. */
    const val CTRL_ALT: Int = 0x0001 or 0x0002 or 0x0004 or 0x0008 or 0x0020 or 0x0040

    /** The key in `LayoutSpec.layers` for Keyman layer [keymanId]. */
    fun specKey(keymanId: String): String = when (keymanId) {
        DEFAULT -> LayoutLayer.LETTERS.key
        "numeric" -> LayoutLayer.SYMBOLS.key
        "symbol" -> LayoutLayer.SYMBOLS_SHIFTED.key
        else -> PREFIX + keymanId
    }

    /** Keyman's own name for the layer keyed [specKey], for `&layer` and `layer()`. */
    fun keymanId(specKey: String): String = when (specKey) {
        LayoutLayer.LETTERS.key -> DEFAULT
        LayoutLayer.SYMBOLS.key -> "numeric"
        LayoutLayer.SYMBOLS_SHIFTED.key -> "symbol"
        else -> specKey.removePrefix(PREFIX)
    }

    /**
     * The modifiers a key on Keyman layer [keymanId] is pressed with, read the
     * way KeymanWeb reads them: by what the name *contains*, so `rightalt-shift`
     * is right Alt with shift, `leftctrl` is the left control key, and a layer
     * named for something else entirely (`ሀ-layer`, `numeric`) holds nothing.
     * A `caps` layer also sets caps lock, which KeymanWeb carries in its state
     * flags rather than its modifiers; the rules see the two together.
     */
    fun modifiers(keymanId: String): Int {
        var mask = 0
        if ("shift" in keymanId) mask = mask or KmxFormat.K_SHIFTFLAG
        var ctrl = false
        if ("leftctrl" in keymanId) {
            mask = mask or KmxFormat.LCTRLFLAG
            ctrl = true
        }
        if ("rightctrl" in keymanId) {
            mask = mask or KmxFormat.RCTRLFLAG
            ctrl = true
        }
        if ("ctrl" in keymanId && !ctrl) mask = mask or KmxFormat.K_CTRLFLAG
        var alt = false
        if ("leftalt" in keymanId) {
            mask = mask or KmxFormat.LALTFLAG
            alt = true
        }
        if ("rightalt" in keymanId) {
            mask = mask or KmxFormat.RALTFLAG
            alt = true
        }
        if ("alt" in keymanId && !alt) mask = mask or KmxFormat.K_ALTFLAG
        if ("caps" in keymanId) mask = mask or KmxFormat.CAPITALFLAG
        return mask
    }
}
