package com.wasimaster.wmkeyboard.ime

/**
 * What a glided text-expansion trigger will type in its place (#205): a
 * snippet's text or a personal-dictionary shortcut's phrase, in each casing a
 * stroke can ask of it.
 *
 * A glided trigger expands on lift (#170), so a pill that showed only "fk"
 * promised a word the lift never types. The draw sites show "fk = FUTO
 * Keyboard" instead. All three casings are worked out up front because the
 * capitals are only settled where the word is drawn (see
 * [KeyboardUiState.glideCase]), and a snippet's own casing style is not
 * something those sites can apply for themselves.
 */
data class GlideExpansion(
    val plain: String,
    val capitalized: String,
    val shouted: String,
) {
    /**
     * The expansion for [trigger] as it is being shown, by the rule the commit
     * cases it with, flattened to one short line for a pill or a chip.
     */
    fun previewFor(trigger: String): String {
        val letters = trigger.filter(Char::isLetter)
        val text = when {
            letters.length > 1 && letters.all(Char::isUpperCase) -> shouted
            letters.firstOrNull()?.isUpperCase() == true -> capitalized
            else -> plain
        }
        return glideExpansionPreview(text)
    }
}

/**
 * [text] on one line and cut to [GLIDE_EXPANSION_PREVIEW_MAX] code points. A
 * snippet can be a whole paragraph, and the pill has one line above a finger.
 */
internal fun glideExpansionPreview(text: String): String {
    val flat = text.trim().replace(PreviewWhitespace, " ")
    if (flat.codePointCount(0, flat.length) <= GLIDE_EXPANSION_PREVIEW_MAX) return flat
    return flat.substring(0, flat.offsetByCodePoints(0, GLIDE_EXPANSION_PREVIEW_MAX)).trimEnd() + "…"
}

internal const val GLIDE_EXPANSION_PREVIEW_MAX = 40

private val PreviewWhitespace = Regex("\\s+")
