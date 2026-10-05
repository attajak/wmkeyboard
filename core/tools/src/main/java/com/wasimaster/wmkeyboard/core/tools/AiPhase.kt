package com.wasimaster.wmkeyboard.core.tools

import androidx.annotation.StringRes
import com.wasimaster.wmkeyboard.tools.R

/**
 * How far one AI request has got. A cloud request spends most of its life
 * waiting, and an anonymous spinner can't tell "your Wi-Fi is gone" from "the
 * model is still reading a 2000-word field" — so the panel reports the step
 * instead, and each step has exactly one place that sets it:
 *
 *  - [PREPARING]  the service, before the call (also the on-device model load)
 *  - [CONNECTING] the service, on entering the request
 *  - [WAITING]    [AiClient] streaming, once response headers arrive
 *  - [THINKING]   the service, when a partial is reasoning with no answer yet
 *  - [USING_TOOL] [AiClient], while a tool the model asked for is running
 *
 * Answer text arriving ends the phases altogether: the panel switches to the
 * streaming result view.
 */
enum class AiPhase {
    /** Reading the field, building the prompt, loading the on-device model. */
    PREPARING,

    /** Opening the connection to the provider and writing the request. */
    CONNECTING,

    /** Request accepted — the model is reading the prompt, nothing back yet. */
    WAITING,

    /** Reasoning tokens are arriving; no answer text has started. */
    THINKING,

    /**
     * The model asked for a tool and the tool is running (#470). Its own step
     * because it is the one wait nothing else explains: the request has
     * already succeeded, the model is not thinking, and the keyboard is off
     * searching the web on its behalf.
     */
    USING_TOOL,
    ;

    /** Step label for the panel's progress readout. */
    @get:StringRes
    val labelRes: Int
        get() = when (this) {
            PREPARING -> R.string.core_tools_ai_phase_preparing
            CONNECTING -> R.string.core_tools_ai_phase_connecting
            WAITING -> R.string.core_tools_ai_phase_waiting
            THINKING -> R.string.core_tools_ai_phase_thinking
            USING_TOOL -> R.string.core_tools_ai_phase_using_tool
        }

    /**
     * How far along a determinate progress bar should sit. Deliberately not
     * evenly spaced: waiting is by far the longest step in practice, so it
     * gets the widest band rather than looking stalled a quarter of the way in.
     */
    val fraction: Float
        get() = when (this) {
            PREPARING -> 0.08f
            CONNECTING -> 0.25f
            WAITING -> 0.55f
            THINKING -> 0.8f
            // Between waiting and thinking: the request landed, and what
            // follows the tool is another request, so the bar must not look
            // nearly finished here.
            USING_TOOL -> 0.65f
        }
}
