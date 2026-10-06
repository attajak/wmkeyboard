package com.wasimaster.wmkeyboard.ime.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.ErrorOutline
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.ExpandLess
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.ExpandMore
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Language
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Search
import com.wasimaster.wmkeyboard.core.tools.AiPhase
import com.wasimaster.wmkeyboard.core.tools.AiToolActivity
import com.wasimaster.wmkeyboard.core.tools.AiToolSource
import com.wasimaster.wmkeyboard.core.tools.AiTools
import com.wasimaster.wmkeyboard.ime.R

/**
 * The tools a model called for one answer, drawn over that answer: what it
 * searched for and the results it was handed, the pages it read, and the calls
 * that failed and why (#470).
 *
 * Folded to one line by default — "Searched the web for “…”", or "3 tool
 * calls" — because the answer is what the user came for. A tap unfolds it.
 * While a call is running that one line says what is happening right now,
 * with a spinner, which is the whole of what "Using a tool" used to say.
 *
 * Shared by the keyboard's AI panel (chat and actions) and the chat screen in
 * the settings app, like [ChatMarkdown], so colours come in rather than off a
 * theme. Links come out through [onOpen] and [onCopy] for the same reason: the
 * keyboard has no selection handles to offer and opens a browser itself, the
 * app has its own clipboard and URI handler. [onOpen] is only ever handed an
 * http or https address; anything else a model wrote can be copied, never
 * opened.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChatToolUses(
    uses: List<AiToolActivity>,
    colors: ChatMarkdownColors,
    errorColor: Color,
    onOpen: (String) -> Unit,
    onCopy: (String) -> Unit,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = 12.sp,
) {
    if (uses.isEmpty()) return
    var expanded by remember { mutableStateOf(false) }
    val running = uses.lastOrNull { it.running }
    val failed = uses.count { it.failed }
    val lineHeight = fontSize * LINE_HEIGHT
    val header = when {
        running != null -> toolLine(running)
        uses.size == 1 -> toolLine(uses.single())
        failed > 0 -> stringResource(
            R.string.ime_ai_chat_tool_summary_with_failed,
            pluralStringResource(R.plurals.ime_ai_chat_tool_calls, uses.size, uses.size),
            pluralStringResource(R.plurals.ime_ai_chat_tool_failed_count, failed, failed),
        )
        else -> pluralStringResource(R.plurals.ime_ai_chat_tool_calls, uses.size, uses.size)
    }
    val allFailed = running == null && failed == uses.size
    val headerInk = if (allFailed) errorColor else colors.dim
    Column(modifier = modifier) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .clickable(
                    onClickLabel = stringResource(
                        if (expanded) R.string.ime_ai_chat_tool_hide_desc else R.string.ime_ai_chat_tool_show_desc,
                    ),
                ) { expanded = !expanded }
                .padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (running != null) {
                CircularProgressIndicator(
                    color = colors.dim,
                    strokeWidth = 1.5.dp,
                    modifier = Modifier.size(11.dp),
                )
            } else {
                Icon(
                    when {
                        allFailed -> Icons.Outlined.ErrorOutline
                        uses.all { it.tool == AiTools.WEB_FETCH } -> Icons.Outlined.Language
                        else -> Icons.Outlined.Search
                    },
                    contentDescription = null,
                    tint = headerInk,
                    modifier = Modifier.size(13.dp),
                )
            }
            Spacer(Modifier.width(5.dp))
            Text(
                header,
                color = headerInk,
                fontSize = fontSize,
                lineHeight = lineHeight,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Icon(
                if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                contentDescription = null,
                tint = headerInk,
                modifier = Modifier.size(15.dp),
            )
        }
        if (expanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, top = 2.dp, bottom = 4.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                for (use in uses) {
                    ToolUseDetail(
                        use = use,
                        // A single call's line is the header already.
                        showLine = uses.size > 1,
                        colors = colors,
                        errorColor = errorColor,
                        fontSize = fontSize,
                        onOpen = onOpen,
                        onCopy = onCopy,
                    )
                }
            }
        }
    }
}

@Composable
private fun ToolUseDetail(
    use: AiToolActivity,
    showLine: Boolean,
    colors: ChatMarkdownColors,
    errorColor: Color,
    fontSize: TextUnit,
    onOpen: (String) -> Unit,
    onCopy: (String) -> Unit,
) {
    val small = fontSize * SMALL
    if (showLine) {
        Text(
            toolLine(use),
            color = if (use.failed) errorColor else colors.text,
            fontSize = fontSize,
            lineHeight = fontSize * LINE_HEIGHT,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
    when {
        use.failed -> Text(
            use.error,
            color = errorColor.copy(alpha = errorColor.alpha * 0.85f),
            fontSize = small,
            lineHeight = small * LINE_HEIGHT,
        )
        use.running -> Unit
        use.tool == AiTools.WEB_SEARCH && use.sources.isEmpty() -> Text(
            stringResource(R.string.ime_ai_chat_tool_no_results),
            color = colors.dim,
            fontSize = small,
            lineHeight = small * LINE_HEIGHT,
        )
        else -> for (source in use.sources) {
            // A page read is shown by its whole address: which page, exactly,
            // is the thing to check. A search result only needs its site.
            SourceLine(
                source = source,
                fullAddress = use.tool == AiTools.WEB_FETCH,
                colors = colors,
                fontSize = fontSize,
                onOpen = onOpen,
                onCopy = onCopy,
            )
        }
    }
}

/** One page: its title, and where it is. Tap opens it, a long press copies the address. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SourceLine(
    source: AiToolSource,
    fullAddress: Boolean,
    colors: ChatMarkdownColors,
    fontSize: TextUnit,
    onOpen: (String) -> Unit,
    onCopy: (String) -> Unit,
) {
    val openable = AiToolActivity.openableUrl(source.url)
    val small = fontSize * SMALL
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .combinedClickable(
                onClickLabel = stringResource(
                    if (openable != null) R.string.ime_ai_chat_tool_open_desc else R.string.ime_ai_chat_tool_copy_desc,
                ),
                onClick = { if (openable != null) onOpen(openable) else onCopy(source.url) },
                onLongClick = { onCopy(source.url) },
            )
            .padding(vertical = 2.dp, horizontal = 2.dp),
    ) {
        Text(
            source.title.ifBlank { source.domain },
            color = colors.text,
            fontSize = small,
            lineHeight = small * LINE_HEIGHT,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            if (fullAddress) source.url else source.domain,
            color = colors.dim,
            fontSize = small * 0.92f,
            lineHeight = small * LINE_HEIGHT,
            maxLines = if (fullAddress) 3 else 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * One call in a line: what is happening while it runs, what happened once it
 * has, and what did not happen when it failed.
 */
@Composable
internal fun toolLine(use: AiToolActivity): String {
    val target = use.target
    val page = when {
        use.tool != AiTools.WEB_FETCH -> ""
        use.sources.isNotEmpty() -> use.sources.first().domain
        target.isNotEmpty() -> AiToolActivity.domainOf(target)
        else -> ""
    }
    return when (use.tool) {
        AiTools.WEB_SEARCH -> when {
            target.isEmpty() && use.running -> stringResource(AiPhase.USING_TOOL.labelRes)
            target.isEmpty() -> stringResource(
                R.string.ime_ai_chat_tool_use_failed,
                stringResource(R.string.ime_ai_chat_tool_name_search),
            )
            use.running -> stringResource(R.string.ime_ai_chat_tool_searching, target)
            use.failed -> stringResource(R.string.ime_ai_chat_tool_search_failed, target)
            else -> stringResource(R.string.ime_ai_chat_tool_searched, target)
        }
        AiTools.WEB_FETCH -> when {
            page.isEmpty() && use.running -> stringResource(AiPhase.USING_TOOL.labelRes)
            page.isEmpty() -> stringResource(
                R.string.ime_ai_chat_tool_use_failed,
                stringResource(R.string.ime_ai_chat_tool_name_fetch),
            )
            use.running -> stringResource(R.string.ime_ai_chat_tool_reading, page)
            use.failed -> stringResource(R.string.ime_ai_chat_tool_read_failed, page)
            else -> stringResource(R.string.ime_ai_chat_tool_read, page)
        }
        // A tool the model made up: there is nothing to say but its name.
        else -> if (use.running) {
            stringResource(AiPhase.USING_TOOL.labelRes)
        } else {
            stringResource(R.string.ime_ai_chat_tool_use_failed, use.tool)
        }
    }
}

/**
 * Opens one page from the keyboard, as the Wikipedia panel opens an article:
 * a new task, since an input method has none of its own. Only ever handed an
 * address [AiToolActivity.openableUrl] passed.
 */
internal fun openToolLink(context: Context, url: String) {
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

private const val LINE_HEIGHT = 1.35f
private const val SMALL = 0.92f
