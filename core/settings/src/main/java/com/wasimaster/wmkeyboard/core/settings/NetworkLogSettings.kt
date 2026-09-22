package com.wasimaster.wmkeyboard.core.settings

/**
 * The network activity log (Settings › Privacy › Network activity).
 *
 * Nested for the usual reason (see [CameraSettings]): one slot in
 * [KeyboardSettings] however many switches live here. The DataStore keys stay
 * flat.
 */
data class NetworkLogSettings(
    /**
     * Keep a record of every request the keyboard makes. On by default: the
     * record holds addresses and sizes, never what was typed, and a log that has
     * to be switched on before the request you are wondering about is a log that
     * is always empty when you open it.
     */
    val keep: Boolean = true,
    /**
     * Draw a small dot at the edge of the keyboard's toolbar while a request is
     * under way, in the colour of the tool making it. Off by default, like
     * everything that adds to the keyboard's chrome.
     */
    val showOnKeyboard: Boolean = false,
)
