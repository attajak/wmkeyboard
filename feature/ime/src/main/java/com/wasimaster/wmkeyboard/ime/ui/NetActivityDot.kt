package com.wasimaster.wmkeyboard.ime.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wasimaster.wmkeyboard.core.netlog.NetLog
import com.wasimaster.wmkeyboard.core.ui.toolAccentColor
import com.wasimaster.wmkeyboard.ime.R

/**
 * Draws [content] (the toolbar row) and, when [enabled], a small dot at its top
 * end while the keyboard has a request under way: Settings › Privacy › Network
 * activity › Show activity on the keyboard.
 *
 * The dot wears the colour of the tool making the request, so a GIF search
 * lights it in the GIF key's colour. Press and hold it to read the server's
 * name. Nothing is drawn while idle, so the row costs nothing extra when the
 * keyboard is quiet, and nothing at all when the setting is off.
 *
 * A wrapper rather than code inside the toolbar: that call chain sits against
 * the JVM's method-size ceiling, and this keeps it out of it.
 */
@Composable
internal fun WithNetActivityDot(
    enabled: Boolean,
    toolColors: Map<com.wasimaster.wmkeyboard.core.settings.ToolbarTool, Long>,
    content: @Composable () -> Unit,
) {
    if (!enabled) {
        content()
        return
    }
    Box {
        content()
        val inFlight by NetLog.inFlight.collectAsStateWithLifecycle()
        val latest = inFlight.lastOrNull()
        var reading by remember { mutableStateOf(false) }
        val kb = LocalKbTheme.current
        AnimatedVisibility(
            visible = latest != null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopEnd).padding(top = 3.dp, end = 4.dp),
        ) {
            // Held so the last colour and host stay on screen while it fades.
            val shown = remember(latest) { latest } ?: return@AnimatedVisibility
            val color = shown.source.tool?.let { toolAccentColor(it, toolColors) } ?: kb.accent
            val spoken = stringResource(R.string.ime_netlog_dot_desc, shown.host)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .semantics { contentDescription = spoken }
                    .pointerInput(Unit) {
                        detectTapGestures(onPress = {
                            reading = true
                            tryAwaitRelease()
                            reading = false
                        })
                    }
                    .then(
                        if (reading) Modifier.background(kb.popup, RoundedCornerShape(8.dp)).padding(horizontal = 6.dp, vertical = 2.dp)
                        else Modifier,
                    ),
            ) {
                if (reading) {
                    Text(
                        shown.host,
                        color = kb.popupText,
                        fontSize = 10.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.width(4.dp))
                }
                Box(Modifier.size(DotSize).background(color, CircleShape))
            }
        }
    }
}

private val DotSize = 6.dp
