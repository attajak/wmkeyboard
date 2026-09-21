package com.wasimaster.wmkeyboard.ime.ui

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.wasimaster.wmkeyboard.core.selection.SelectionKind
import com.wasimaster.wmkeyboard.core.selection.SelectionMacros
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * What the Open chip starts, in the order it tries them.
 *
 * Shared by the service, which starts the first one anything answers, and by
 * the bar, which asks the system which app that will be. Building them in one
 * place is what keeps the chip's icon honest about where the tap goes.
 *
 * An address is opened by whatever claims `mailto:`, and plenty of mail apps
 * claim it only for SENDTO, so that is the second try.
 */
internal fun macroOpenIntents(kind: SelectionKind, text: String): List<Intent> =
    if (kind == SelectionKind.EMAIL) {
        listOf(
            Intent(Intent.ACTION_VIEW, Uri.parse("mailto:$text")),
            Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$text")),
        )
    } else {
        listOf(Intent(Intent.ACTION_VIEW, Uri.parse(SelectionMacros.openableUrl(text))))
    }

/** The app the Open chip's link lands in, when that is an app and not a browser. */
internal class MacroLinkApp(val name: String, val icon: ImageBitmap)

/**
 * The app behind the Open chip, resolved off the main thread.
 *
 * Null while nothing is resolved yet, when the chip is not on the row, and
 * whenever the answer is not one specific app: a browser (the plain Open
 * glyph already says "leaves for the web"), or the system's chooser because
 * two apps claim the link and neither is the default.
 *
 * The previous answer is kept while a new one resolves, so a rewrite of the
 * selection (URL decode) does not blink the icon off and back on.
 */
@Composable
internal fun rememberMacroLinkApp(kind: SelectionKind, text: String, wanted: Boolean): MacroLinkApp? {
    val context = LocalContext.current
    val iconPx = with(LocalDensity.current) { 16.dp.roundToPx() }
    val app by produceState<MacroLinkApp?>(null, kind, text, wanted, iconPx) {
        value = if (!wanted) {
            null
        } else {
            withContext(Dispatchers.IO) {
                runCatching { resolveMacroLinkApp(context, macroOpenIntents(kind, text), iconPx) }.getOrNull()
            }
        }
    }
    return app
}

private fun resolveMacroLinkApp(context: Context, intents: List<Intent>, iconPx: Int): MacroLinkApp? {
    val pm = context.packageManager
    // The first intent anything answers is the one the service will start.
    val info = intents.firstNotNullOfOrNull { pm.resolveActivity(it, PackageManager.MATCH_DEFAULT_ONLY) } ?: return null
    val activity = info.activityInfo ?: return null
    // No default: the system's chooser answers, under "android" on AOSP and
    // under an OEM package elsewhere, but always as a ResolverActivity.
    if (activity.packageName == "android" || activity.name.endsWith("ResolverActivity")) return null
    if (activity.packageName in browserPackages(pm)) return null
    val name = activity.applicationInfo.loadLabel(pm).toString()
    val icon = info.loadIcon(pm).toBitmap(iconPx, iconPx).asImageBitmap()
    return MacroLinkApp(name, icon)
}

/**
 * Every installed browser, asked two ways: the category browsers declare, and
 * the host-less web link only a browser claims (an app that handles its own
 * site names the site's host in its filter).
 */
private fun browserPackages(pm: PackageManager): Set<String> {
    val declared = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_BROWSER)
    val generic = Intent(Intent.ACTION_VIEW, Uri.parse("https:")).addCategory(Intent.CATEGORY_BROWSABLE)
    return (pm.queryIntentActivities(declared, 0) + pm.queryIntentActivities(generic, PackageManager.MATCH_ALL))
        .mapNotNullTo(HashSet()) { it.activityInfo?.packageName }
}
