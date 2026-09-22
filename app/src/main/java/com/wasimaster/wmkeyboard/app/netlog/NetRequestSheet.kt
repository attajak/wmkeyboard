package com.wasimaster.wmkeyboard.app.netlog

import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.core.netlog.NetEntry
import java.text.DateFormat
import java.text.NumberFormat
import java.util.Date

/**
 * One request, in full: which feature made it and why, where it went, how it
 * ended, how much it carried, and what that feature sends. Ends with the two
 * places a user who does not like what they see can go: the feature's own
 * settings, and Data saver when it has a row for this feature.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NetRequestSheet(
    entry: NetEntry,
    look: NetSourceLook,
    ownServer: Boolean,
    onDismiss: () -> Unit,
    onNavigate: (String) -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        NetRequestSheetBody(entry, look, ownServer, onNavigate)
    }
}

/** What the sheet holds, apart from the sheet itself. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun NetRequestSheetBody(
    entry: NetEntry,
    look: NetSourceLook,
    ownServer: Boolean,
    onNavigate: (String) -> Unit,
) {
    val context = LocalContext.current
    val numbers = remember { NumberFormat.getIntegerInstance() }
    val label = stringResource(look.label)
    val stamp = remember(entry.firstMillis, entry.lastMillis) {
        val format = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
        format.format(Date(entry.firstMillis)) to
            android.text.format.DateFormat.getTimeFormat(context).format(Date(entry.lastMillis))
    }
    run {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 16.dp)
                .navigationBarsPadding(),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                NetSourceTile(look, size = HeaderTile)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(label, style = MaterialTheme.typography.titleLarge)
                    Text(
                        stringResource(if (entry.background) R.string.netlog_sheet_why_auto else R.string.netlog_sheet_why_user),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            val badges = entry.incognito || entry.background || ownServer || isLocalHost(entry.host)
            if (badges) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(top = 12.dp),
                ) {
                    if (entry.background) Badge(stringResource(R.string.netlog_badge_auto), BadgeTone.WARNING)
                    if (entry.incognito) Badge(stringResource(R.string.netlog_badge_incognito), BadgeTone.NEUTRAL)
                    if (ownServer) Badge(stringResource(R.string.netlog_badge_own), BadgeTone.GOOD)
                    if (isLocalHost(entry.host)) Badge(stringResource(R.string.netlog_badge_lan), BadgeTone.NEUTRAL)
                }
            }

            Column(Modifier.padding(top = 16.dp)) {
                Fact(stringResource(R.string.netlog_sheet_server), entry.host + if (entry.port > 0) ":${entry.port}" else "")
                Fact(stringResource(R.string.netlog_sheet_request), entry.method + " " + (entry.route ?: "/"), mono = true)
                Fact(
                    stringResource(R.string.netlog_sheet_result),
                    when {
                        entry.failed && entry.error != null -> failureWords(entry)
                        entry.failed -> stringResource(R.string.netlog_result_status, entry.status)
                        else -> stringResource(R.string.netlog_result_ok, entry.status)
                    },
                    error = entry.failed,
                )
                Fact(
                    stringResource(R.string.netlog_sheet_data),
                    stringResource(
                        R.string.netlog_sheet_data_value,
                        Formatter.formatShortFileSize(context, entry.bytesIn),
                        Formatter.formatShortFileSize(context, entry.bytesOut),
                    ),
                )
                Fact(
                    stringResource(R.string.netlog_sheet_took),
                    if (entry.count > 1) {
                        stringResource(R.string.netlog_sheet_took_mean, entry.meanDurationMs.toInt())
                    } else {
                        stringResource(R.string.netlog_sheet_took_value, entry.durationMs.toInt())
                    },
                )
                if (entry.count > 1) {
                    Fact(stringResource(R.string.netlog_sheet_count), numbers.format(entry.count.toLong()))
                    Fact(stringResource(R.string.netlog_sheet_when), stringResource(R.string.netlog_sheet_when_range, stamp.first, stamp.second))
                } else {
                    Fact(stringResource(R.string.netlog_sheet_when), stamp.first)
                }
            }

            Row(
                verticalAlignment = Alignment.Top,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(16.dp))
                    .padding(14.dp),
            ) {
                Icon(
                    Icons.AutoMirrored.Outlined.Send,
                    contentDescription = null,
                    tint = look.accent,
                    modifier = Modifier.size(18.dp).padding(top = 1.dp),
                )
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(stringResource(R.string.netlog_sheet_sent_title), style = MaterialTheme.typography.labelLarge)
                    Text(
                        stringResource(look.sent),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (entry.incognito) {
                        Text(
                            stringResource(R.string.netlog_sheet_why_incognito),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }

            val route = look.route
            if (route != null || look.dataSaver) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                ) {
                    if (route != null) {
                        FilledTonalButton(onClick = { onNavigate(route) }, modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.netlog_sheet_open_settings, label), textAlign = TextAlign.Center)
                        }
                    }
                    if (look.dataSaver) {
                        OutlinedButton(onClick = { onNavigate(DATA_SAVER_ROUTE) }, modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.netlog_sheet_data_saver))
                        }
                    }
                }
            }
        }
    }
}

/** One labelled line of the sheet's table. */
@Composable
private fun Fact(name: String, value: String, mono: Boolean = false, error: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.Top) {
        Text(
            name,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(FactLabelWidth),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = if (mono) FontFamily.Monospace else null,
            color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f),
        )
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
}

private val HeaderTile = 52.dp
private val FactLabelWidth = 112.dp
private const val DATA_SAVER_ROUTE = "datasaver"
