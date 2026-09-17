package com.wasimaster.wmkeyboard.app.updates

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.common.R as CommonR

/**
 * A release's own notes, in a dialog.
 *
 * The notes used to be pasted onto the update card as one run of text. They
 * are the long-form changelog now, which is headings and bullets over several
 * screens, and a card on the settings home screen is the wrong place to put
 * that: it pushed every other setting off the screen and it was still raw
 * markdown when it got there.
 *
 * [notes] follows [AppUpdater.releaseNotes]: null while the fetch is in
 * flight, empty once it has come back with nothing. [version] is null when the
 * release did not name one, which is what [UpdateState.Available.versionName]
 * being nullable means; the title drops the number rather than printing a gap.
 */
@Composable
internal fun ReleaseNotesDialog(
    version: String?,
    notes: String?,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (version.isNullOrBlank()) {
                    stringResource(R.string.update_action_notes)
                } else {
                    stringResource(R.string.update_notes_title, version)
                },
            )
        },
        text = {
            when {
                // The dialog opens the moment the button is pressed, before
                // the fetch has landed. An empty dialog with a bar in it says
                // the press was heard; waiting to open says nothing.
                notes == null -> LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                notes.isBlank() -> Text(
                    stringResource(R.string.update_notes_empty),
                    style = MaterialTheme.typography.bodyMedium,
                )

                else -> ReleaseNotesBody(notes)
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(CommonR.string.common_close))
            }
        },
    )
}

/**
 * The parsed notes, scrolled.
 *
 * A dialog's own body does not scroll, and these notes are longer than any
 * phone, so the scroller is here rather than left to the caller.
 */
@Composable
private fun ReleaseNotesBody(notes: String) {
    val blocks = remember(notes) { ReleaseNotesMarkdown.parse(notes) }
    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
        blocks.forEachIndexed { index, block ->
            // Space above rather than below, so the first block sits flush
            // against the title and the last leaves no gap over the button.
            if (index > 0) Spacer(Modifier.height(if (block is NotesBlock.Heading) 16.dp else 8.dp))
            NotesBlockText(block)
        }
    }
}

@Composable
private fun NotesBlockText(block: NotesBlock) {
    when (block) {
        is NotesBlock.Heading -> Text(
            block.spans.annotated(),
            // Only two sizes for six levels: the notes use one heading level,
            // and inventing four more type sizes for headings nobody writes
            // would be a scale this dialog never shows.
            style = if (block.level <= 2) {
                MaterialTheme.typography.titleMedium
            } else {
                MaterialTheme.typography.titleSmall
            },
        )

        is NotesBlock.Paragraph -> Text(
            block.spans.annotated(),
            style = MaterialTheme.typography.bodyMedium,
        )

        is NotesBlock.Bullet -> Row(modifier = Modifier.fillMaxWidth()) {
            Text("•", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.width(8.dp))
            Text(block.spans.annotated(), style = MaterialTheme.typography.bodyMedium)
        }

        is NotesBlock.Code -> Text(
            block.text,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            modifier = Modifier.padding(start = 8.dp),
        )

        NotesBlock.Rule -> HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
    }
}

/**
 * Spans as one styled string.
 *
 * A link is a [LinkAnnotation.Url] rather than a click handler of our own, so
 * the platform opens it and a screen reader announces it as a link.
 */
@Composable
private fun List<NotesSpan>.annotated(): AnnotatedString {
    val linkColor = MaterialTheme.colorScheme.primary
    val linkStyles = TextLinkStyles(
        style = SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline),
    )
    return buildAnnotatedString {
        for (span in this@annotated) {
            val style = SpanStyle(
                fontWeight = if (span.bold) FontWeight.Bold else null,
                fontStyle = if (span.italic) FontStyle.Italic else null,
                fontFamily = if (span.code) FontFamily.Monospace else null,
            )
            if (span.link != null) {
                withLink(LinkAnnotation.Url(span.link, linkStyles)) {
                    withStyle(style) { append(span.text) }
                }
            } else {
                withStyle(style) { append(span.text) }
            }
        }
    }
}
