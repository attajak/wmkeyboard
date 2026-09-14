package com.wasimaster.wmkeyboard.core.selection

/**
 * How each chat app spells bold, italic, strikethrough and monospace in what
 * you type, so the bar can wrap a selection in the markers *that* app renders.
 *
 * Two families cover almost everything: the single-marker style WhatsApp
 * started (`*bold*`, `_italic_`, `~strike~`) and Markdown (`**bold**`,
 * `*italic*`, `~~strike~~`). Telegram is its own thing. An app not in the
 * table gets no chips at all: markers an app does not read are worse than no
 * formatting, since they arrive as punctuation.
 */
enum class ChatStyle { BOLD, ITALIC, STRIKE, MONO }

/**
 * One app's markers. [mono] is null for an app that has no inline code;
 * [monoBlock] is the fenced form used when the selection spans lines.
 */
data class ChatMarkup(
    val bold: String,
    val italic: String,
    val strike: String,
    val mono: String?,
    val monoBlock: String? = null,
) {
    fun marker(style: ChatStyle): String? = when (style) {
        ChatStyle.BOLD -> bold
        ChatStyle.ITALIC -> italic
        ChatStyle.STRIKE -> strike
        ChatStyle.MONO -> mono
    }
}

object ChatSyntax {

    private val MARKDOWN = ChatMarkup(bold = "**", italic = "*", strike = "~~", mono = "`", monoBlock = "```")
    private val SINGLE = ChatMarkup(bold = "*", italic = "_", strike = "~", mono = "`")
    private val SINGLE_FENCED = ChatMarkup(bold = "*", italic = "_", strike = "~", mono = "```")
    private val TELEGRAM = ChatMarkup(bold = "**", italic = "__", strike = "~~", mono = "`", monoBlock = "```")

    /** Package name → markers. Only apps whose composer is known to read these. */
    val byPackage: Map<String, ChatMarkup> = mapOf(
        "com.whatsapp" to SINGLE_FENCED,
        "com.whatsapp.w4b" to SINGLE_FENCED,
        "com.viber.voip" to SINGLE_FENCED,
        "com.Slack" to SINGLE,
        "com.google.android.apps.dynamite" to SINGLE,
        "com.facebook.orca" to SINGLE,
        "chat.rocket.android" to SINGLE,
        "ch.threema.app" to ChatMarkup(bold = "*", italic = "_", strike = "~", mono = null),
        "org.telegram.messenger" to TELEGRAM,
        "org.telegram.messenger.web" to TELEGRAM,
        "org.telegram.messenger.beta" to TELEGRAM,
        "org.thunderdog.challegram" to TELEGRAM,
        "com.discord" to MARKDOWN,
        "im.vector.app" to MARKDOWN,
        "io.element.android.x" to MARKDOWN,
        "com.beeper.android" to MARKDOWN,
        "com.reddit.frontpage" to MARKDOWN,
        "com.mattermost.rn" to MARKDOWN,
        "com.zulipmobile" to MARKDOWN,
        "com.github.android" to MARKDOWN,
    )

    fun forPackage(packageName: String?): ChatMarkup? = packageName?.let(byPackage::get)

    /**
     * [text] wrapped in the app's marker for [style], or unwrapped when it
     * already wears it. Surrounding whitespace stays outside the markers,
     * because every app in the table wants the marker against a letter.
     * Null when there is nothing to wrap or the app has no such marker.
     */
    fun toggle(text: String, markup: ChatMarkup, style: ChatStyle): String? {
        val marker = markup.marker(style) ?: return null
        val core = text.trim()
        if (core.isEmpty()) return null
        val lead = text.substring(0, text.indexOf(core))
        val trail = text.substring(lead.length + core.length)
        val block = style == ChatStyle.MONO && core.contains('\n') && markup.monoBlock != null
        val open = if (block) markup.monoBlock + "\n" else marker
        val close = if (block) "\n" + markup.monoBlock else marker
        // A bold `**x**` also starts with the italic `*`; unwrapping italic
        // there would half-strip the bold, so a longer marker of the same
        // table beginning with this one means "wrap", never "unwrap".
        val longer = listOfNotNull(markup.bold, markup.italic, markup.strike, markup.mono)
            .any { it.length > marker.length && it.startsWith(marker) && core.startsWith(it) }
        val wrapped = core.length >= open.length + close.length &&
            core.startsWith(open) && core.endsWith(close) && !longer
        val result = if (wrapped) {
            core.substring(open.length, core.length - close.length)
        } else {
            open + core + close
        }
        return lead + result + trail
    }
}
