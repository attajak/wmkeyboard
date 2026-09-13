package com.wasimaster.wmkeyboard.app

import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.withStyle

/** What a folded block shows in place of the lines it hides. */
internal const val FOLD_PLACEHOLDER = " \u2026"

/**
 * One folded block. [start] is the region's own start, which is what a fold is
 * kept by; [hidden] is the text it takes off the screen, from the line break
 * that ends its first line to the line break before its last line, so both
 * `function render()` and its `end` stay in view.
 */
@Immutable
internal data class CodeFold(val start: Int, val hidden: TextRange)

/** The text a region hides when folded, or null for a region with no whole line between its first and its last. */
internal fun hiddenRangeOf(text: String, region: TextRange): TextRange? {
    val firstBreak = text.indexOf('\n', region.min)
    if (firstBreak < 0 || firstBreak >= region.max) return null
    val lastBreak = text.lastIndexOf('\n', (region.max - 1).coerceAtLeast(0))
    if (lastBreak <= firstBreak) return null
    return TextRange(firstBreak, lastBreak)
}

/**
 * The folds to draw for [starts]: every start that is still the start of a
 * region, with the text it hides, leaving out any fold inside another, which
 * the outer one already hides. A start with no region any more is skipped.
 */
internal fun foldsFor(text: String, regions: List<TextRange>, starts: Set<Int>): List<CodeFold> {
    if (starts.isEmpty()) return emptyList()
    val candidates = ArrayList<CodeFold>()
    for (region in regions) {
        if (region.min !in starts) continue
        val hidden = hiddenRangeOf(text, region) ?: continue
        candidates += CodeFold(region.min, hidden)
    }
    val folds = ArrayList<CodeFold>()
    for (fold in candidates.sortedWith(compareBy<CodeFold>({ it.hidden.min }, { -it.hidden.max }))) {
        val last = folds.lastOrNull()
        if (last != null && fold.hidden.min < last.hidden.max) continue
        folds += fold
    }
    return folds
}

/**
 * Offsets in the document to offsets in the folded text the field shows, and
 * back. An offset inside hidden text shows at the end of its placeholder, and
 * a place on a placeholder is the end of the text it hides, so a tap there puts
 * the caret after the fold rather than inside it.
 */
internal class FoldMap(private val folds: List<CodeFold>, private val originalLength: Int) : OffsetMapping {

    /** Where each fold's placeholder begins in the folded text. */
    private val placed = IntArray(folds.size)

    /** How long the folded text is. */
    val transformedLength: Int

    init {
        var removed = 0
        folds.forEachIndexed { index, fold ->
            placed[index] = fold.hidden.min - removed
            removed += fold.hidden.length - FOLD_PLACEHOLDER.length
        }
        transformedLength = originalLength - removed
    }

    override fun originalToTransformed(offset: Int): Int {
        var removed = 0
        for ((index, fold) in folds.withIndex()) {
            if (offset <= fold.hidden.min) return (offset - removed).coerceIn(0, transformedLength)
            if (offset < fold.hidden.max) return placed[index] + FOLD_PLACEHOLDER.length
            removed += fold.hidden.length - FOLD_PLACEHOLDER.length
        }
        return (offset - removed).coerceIn(0, transformedLength)
    }

    override fun transformedToOriginal(offset: Int): Int {
        var removed = 0
        for ((index, fold) in folds.withIndex()) {
            val placeholder = placed[index]
            if (offset <= placeholder) return (offset + removed).coerceIn(0, originalLength)
            if (offset < placeholder + FOLD_PLACEHOLDER.length) return fold.hidden.max
            removed += fold.hidden.length - FOLD_PLACEHOLDER.length
        }
        return (offset + removed).coerceIn(0, originalLength)
    }
}

/** [coloured] with each fold's hidden text replaced by the placeholder in [placeholder] style, every other span kept. */
internal fun foldedText(coloured: AnnotatedString, folds: List<CodeFold>, placeholder: SpanStyle): AnnotatedString {
    if (folds.isEmpty()) return coloured
    return buildAnnotatedString {
        var at = 0
        for (fold in folds) {
            append(coloured.subSequence(at, fold.hidden.min))
            withStyle(placeholder) { append(FOLD_PLACEHOLDER) }
            at = fold.hidden.max
        }
        append(coloured.subSequence(at, coloured.length))
    }
}

/**
 * Fold starts carried through an edit from [old] to [new]: kept where they sit
 * before the change, moved by its length where they sit after it, and dropped
 * where the change covers them.
 */
internal fun remapFoldStarts(starts: Set<Int>, old: String, new: String): Set<Int> {
    if (starts.isEmpty() || old == new) return starts
    val shortest = minOf(old.length, new.length)
    var prefix = 0
    while (prefix < shortest && old[prefix] == new[prefix]) prefix++
    var suffix = 0
    while (suffix < shortest - prefix && old[old.length - 1 - suffix] == new[new.length - 1 - suffix]) suffix++
    val oldEnd = old.length - suffix
    val moved = new.length - old.length
    return starts.mapNotNullTo(HashSet()) { start ->
        when {
            start < prefix -> start
            start >= oldEnd -> start + moved
            else -> null
        }
    }
}

/** The innermost region that holds [caret], counting its first character but not the place after its last. */
internal fun blockAround(regions: List<TextRange>, caret: Int): TextRange? =
    regions.filter { caret >= it.min && caret < it.max }.minByOrNull { it.length }

/** Each line that can fold, with the start of the outermost region beginning on it. */
internal fun foldableStarts(text: String, regions: List<TextRange>, lineStarts: List<Int>): Map<Int, Int> {
    val lines = HashMap<Int, Int>()
    for (region in regions.sortedWith(compareBy<TextRange>({ it.min }, { -it.max }))) {
        if (hiddenRangeOf(text, region) == null) continue
        val line = lineOf(lineStarts, region.min)
        if (line !in lines) lines[line] = region.min
    }
    return lines
}

/**
 * The block Ctrl+Shift+[ folds: the innermost open one that starts on the caret's
 * line or holds the caret, and has a whole line to hide.
 */
internal fun foldToClose(text: String, regions: List<TextRange>, folded: Set<Int>, caret: Int): TextRange? {
    val line = lineRangeAt(text, caret)
    return regions
        .filter { it.min !in folded && (it.min in line.min..line.max || caret in it.min..it.max) && hiddenRangeOf(text, it) != null }
        .minByOrNull { it.length }
}

/** The start of the fold Ctrl+Shift+] opens: the innermost folded block on the caret's line or round the caret. */
internal fun foldToOpen(text: String, regions: List<TextRange>, folded: Set<Int>, caret: Int): Int? {
    val line = lineRangeAt(text, caret)
    return regions
        .filter { it.min in folded && (it.min in line.min..line.max || caret in it.min..it.max) }
        .minByOrNull { it.length }
        ?.min
}
