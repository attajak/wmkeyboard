package com.wasimaster.wmkeyboard.app

import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.TextRange
import com.wasimaster.wmkeyboard.core.layout.json.JsonCompletionItem
import com.wasimaster.wmkeyboard.core.layout.json.JsonCompletionKind
import com.wasimaster.wmkeyboard.core.layout.json.JsonDocEntry
import com.wasimaster.wmkeyboard.core.layout.json.JsonTree
import com.wasimaster.wmkeyboard.core.layout.json.JsonValueSource
import com.wasimaster.wmkeyboard.core.layout.json.LayoutJsonCheck
import com.wasimaster.wmkeyboard.core.layout.json.LayoutJsonCompletion
import com.wasimaster.wmkeyboard.core.layout.json.LayoutJsonLookup
import com.wasimaster.wmkeyboard.core.layout.json.LayoutJsonRoot

/**
 * A layout or panel document, as the code field needs it: [JsonCode]'s colours,
 * brackets and printer, and on top of them the schema's suggestions, checks,
 * folds and the explanation of the name at the caret.
 *
 * Every question reads one parse through [JsonTree.of], so the checks after a
 * pause, the suggestions after a keystroke and the doc strip after a caret move
 * share the tree of the text they are all looking at.
 */
@Immutable
internal class LayoutJsonLanguage(
    private val root: LayoutJsonRoot,
    private val values: JsonValueSource,
) : CodeLanguage by JsonCode {

    override fun diagnostics(source: String): List<CodeDiagnostic> {
        val document = JsonTree.of(source)
        val shape = LayoutJsonCheck.check(document, root, values).map { it.toCodeDiagnostic() }
        val layout = LayoutJsonCheck.findings(document, root).map { found ->
            CodeDiagnostic(
                range = TextRange(found.start, found.end),
                severity = LayoutJsonCheck.severityOf(found.finding).toCodeSeverity(),
                note = found.finding.text,
            )
        }
        return (shape + layout).sortedBy { it.range.min }
    }

    override fun completions(source: String, caret: Int, explicit: Boolean): CodeCompletions? {
        val found = LayoutJsonCompletion.at(JsonTree.of(source), caret, root, values, explicit) ?: return null
        return CodeCompletions(TextRange(found.start, found.end), found.items.map { it.toCodeCompletion() })
    }

    override fun foldRegions(source: String): List<TextRange> =
        LayoutJsonLookup.foldRegions(JsonTree.of(source)).map { TextRange(it.start, it.end) }

    /** The property or action at [caret], for the strip under the code. */
    fun docAt(source: String, caret: Int): JsonDocEntry? = LayoutJsonLookup.at(JsonTree.of(source), caret, root)
}

private fun JsonCompletionItem.toCodeCompletion(): CodeCompletion = CodeCompletion(
    label = label,
    kind = when (kind) {
        JsonCompletionKind.PROPERTY -> CodeCompletionKind.FIELD
        JsonCompletionKind.MAP_KEY -> CodeCompletionKind.MODULE
        JsonCompletionKind.VALUE -> CodeCompletionKind.VALUE
        JsonCompletionKind.ENUM -> CodeCompletionKind.CONSTANT
        JsonCompletionKind.KEYWORD -> CodeCompletionKind.KEYWORD
        JsonCompletionKind.SNIPPET -> CodeCompletionKind.SNIPPET
    },
    insert = insert,
    caret = caret,
    detail = detail,
    reopen = reopen,
)
