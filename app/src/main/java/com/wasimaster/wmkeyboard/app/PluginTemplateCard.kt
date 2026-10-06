package com.wasimaster.wmkeyboard.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import com.wasimaster.wmkeyboard.core.icons.symbols.automirrored.outlined.ArrowForward
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import com.wasimaster.wmkeyboard.core.plugins.InstalledPlugin
import com.wasimaster.wmkeyboard.core.plugins.PluginPermission
import com.wasimaster.wmkeyboard.core.plugins.PluginThumbnail
import com.wasimaster.wmkeyboard.core.plugins.RenderedUi
import com.wasimaster.wmkeyboard.core.plugins.ui.LocalPluginPanelStyle
import com.wasimaster.wmkeyboard.core.plugins.ui.PluginInputHost
import com.wasimaster.wmkeyboard.core.plugins.ui.PluginWidgetList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** How tall a template's picture may grow before it is cut off. */
private val THUMBNAIL_HEIGHT = 200.dp

/**
 * A template in the projects list: its name and one line about it, over the
 * panel it draws before anyone touches it. The picture is the template's own
 * render, run once through [PluginThumbnail] and drawn by the same widget
 * renderer as the preview and the keyboard, so it is what the plugin looks like
 * rather than a drawing of it. The picture does nothing when pressed except
 * start from the template, and TalkBack reads the row above it instead.
 */
@Composable
internal fun TemplateCard(template: PluginTemplate, onClick: () -> Unit) {
    val context = LocalContext.current
    val name = stringResource(template.nameRes)
    val drawn by produceState<RenderedUi?>(null, template) {
        value = withContext(Dispatchers.IO) {
            val plugin = InstalledPlugin(
                id = "template.${template.name.lowercase()}",
                name = name,
                version = "0.1.0",
                permissions = if (template.storage) listOf(PluginPermission.Storage.wire) else emptyList(),
            )
            PluginThumbnail.render(plugin, template.script(context))
        }
    }
    val style = rememberMaterialPluginStyle()
    Column(Modifier.fillMaxWidth()) {
        WmRow(
            title = name,
            subtitle = stringResource(template.descriptionRes),
            trailing = { Icon(Icons.AutoMirrored.Outlined.ArrowForward, contentDescription = null) },
            onClick = onClick,
        )
        val ui = drawn
        if (ui != null && ui.root.isNotEmpty()) {
            val shape = RoundedCornerShape(12.dp)
            Box(
                Modifier
                    .padding(start = 16.dp, end = 16.dp, bottom = 12.dp)
                    .fillMaxWidth()
                    .heightIn(max = THUMBNAIL_HEIGHT)
                    .clip(shape)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
                    .clearAndSetSemantics {},
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    CompositionLocalProvider(LocalPluginPanelStyle provides style) {
                        PluginWidgetList(widgets = ui.root, host = StillHost, onEvent = {}, onInsert = {}, onCopy = {})
                    }
                }
                Box(Modifier.matchParentSize().pointerInput(onClick) { detectTapGestures { onClick() } })
            }
        }
    }
}

/** Text boxes in a picture: empty, never focused, and not text fields at all. */
private object StillHost : PluginInputHost {
    override fun value(id: String): String = ""

    override fun isFocused(id: String): Boolean = false

    override fun onFocus(id: String) = Unit

    override fun onPaste(id: String) = Unit

    override val onValueChange: ((String, String) -> Unit)? = null
}
