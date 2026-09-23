package com.wasimaster.wmkeyboard.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.dp
import com.wasimaster.wmkeyboard.core.input.composer.composerFor
import com.wasimaster.wmkeyboard.core.clipboard.ClipItem
import com.wasimaster.wmkeyboard.core.layout.Key
import com.wasimaster.wmkeyboard.core.layout.LayoutSpec
import com.wasimaster.wmkeyboard.core.layout.KeyboardLayout
import com.wasimaster.wmkeyboard.core.layout.composerType
import com.wasimaster.wmkeyboard.core.layout.language
import com.wasimaster.wmkeyboard.core.layout.resolveLayout
import com.wasimaster.wmkeyboard.core.layout.script
import com.wasimaster.wmkeyboard.core.settings.DeviceForm
import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import com.wasimaster.wmkeyboard.core.settings.OneHandedMode
import com.wasimaster.wmkeyboard.core.settings.isTelevision
import com.wasimaster.wmkeyboard.ime.FieldKind
import com.wasimaster.wmkeyboard.ime.KeyboardUiState
import com.wasimaster.wmkeyboard.ime.LayoutSet
import com.wasimaster.wmkeyboard.ime.PanelMode
import com.wasimaster.wmkeyboard.ime.compileLayoutSet
import com.wasimaster.wmkeyboard.ime.compileSecondaryGrids
import com.wasimaster.wmkeyboard.ime.ui.KeyPreviewBandMode
import com.wasimaster.wmkeyboard.ime.ui.KeyboardScreen
import com.wasimaster.wmkeyboard.ime.ui.LocalKeyPreviewBand
import com.wasimaster.wmkeyboard.ime.ui.LocalKeyboardPreviewHost
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.math.roundToInt

/**
 * One layout drawn by the keyboard itself, as a picture: the layout cards on a
 * language's screen.
 *
 * This is the real [KeyboardScreen], fed the state the service would build for
 * [layoutId] in a plain text field, so the card is the board the user gets and
 * not an impression of it: their theme and its photo, their fonts, key height,
 * number row, hints, bottom row, toolbar, and whatever the layout itself brings
 * (its own theme, its row heights, a Keyman layer's shape). The grids come from
 * [compileLayoutSet], the function the service compiles them with, so the two
 * cannot drift apart.
 *
 * Laid out at the width of the screen, which is the width the keyboard has when
 * it is up, and drawn scaled down to the width on offer. Scaling the drawn
 * result rather than measuring the keyboard narrower is what keeps a key as
 * tall for its width here as it is under the thumb. The height it reports is
 * the scaled height of the whole board, so a five-row layout draws taller than
 * a four-row one, as it will on screen.
 *
 * It is a picture and nothing more: touches, focus and the screen reader all
 * stop at its edge, so a tap or a swipe over it belongs to whatever holds it —
 * the card's toggle, the carousel's scroll — and TalkBack reads the card's name
 * rather than forty keys.
 */
@Composable
fun LayoutKeyboardPreview(
    settings: KeyboardSettings,
    layoutId: String,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val form = remember(configuration.smallestScreenWidthDp) {
        DeviceForm.of(configuration.smallestScreenWidthDp)
    }
    val television = remember(context) { context.isTelevision() }
    val state = remember(settings, layoutId, form, television) {
        layoutPreviewState(settings, layoutId, form, television)
    }
    // The screen wants a flow, as the service hands it one. Published after
    // the composition that built it, so the collector sees each state once.
    val stateFlow = remember { MutableStateFlow(state) }
    SideEffect { stateFlow.value = state }

    Box(
        modifier = modifier
            .clearAndSetSemantics {}
            // A D-pad or a hardware Tab walking the settings screen must not
            // wander into the preview's toolbar buttons.
            .focusProperties { onEnter = { cancelFocusChange() } }
            .focusGroup(),
    ) {
        ScaledBoard {
            CompositionLocalProvider(
                // Clipped to the card, so a bubble has nowhere to escape to;
                // and no band either, which would be empty board over the keys.
                LocalKeyPreviewBand provides KeyPreviewBandMode.INSIDE,
                LocalKeyboardPreviewHost provides true,
            ) {
                // The card is nowhere near the navigation bar, and the board
                // pads for one all the same: consumed so there is no phantom
                // band along its floor.
                Box(Modifier.consumeWindowInsets(WindowInsets.navigationBars)) {
                    KeyboardScreen(
                        stateFlow = stateFlow,
                        onKey = Inert.key,
                        onSuggestion = Inert.text,
                        onEmoji = Inert.text,
                        onEmojiQueryTap = Inert.none,
                        onPanelChange = Inert.panel,
                        onClipboardItem = Inert.clip,
                        onClipboardPin = Inert.clip,
                        onClipboardDelete = Inert.clip,
                    )
                }
            }
        }
        // Over the board, as a sibling: a sibling on top wins the hit test, so
        // no key, strip or tool under it ever sees a finger. It consumes
        // nothing, so the press and the drag still reach the card and the
        // carousel around it.
        Box(
            Modifier
                .matchParentSize()
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) awaitPointerEvent()
                    }
                },
        )
    }
}

/**
 * The callbacks every preview hands the keyboard. Nothing reaches them, since
 * the board takes no touches; they are shared instances so the grid sees the
 * same lambda on every composition and keeps skipping.
 */
private object Inert {
    val key: (Key) -> Unit = {}
    val text: (String) -> Unit = {}
    val none: () -> Unit = {}
    val panel: (PanelMode) -> Unit = {}
    val clip: (ClipItem) -> Unit = {}
}

/**
 * The state the service would publish for [layoutId] in a plain text field with
 * nothing typed, on [form].
 *
 * The layout is shown as switched on even while it is not, since that is the
 * only way anyone types on it: a spacebar that names the layout once a second
 * one of the language is on, or the language arrows that appear with a second
 * layout, read the enabled list, and a card should show what the board will
 * look like after the tap, not a board nobody can reach.
 *
 * Floating and one-handed are left out. Both are a frame around the board that
 * needs a window to sit in, and a card of a one-handed board is a card about
 * the frame rather than the layout.
 */
internal fun layoutPreviewState(
    settings: KeyboardSettings,
    layoutId: String,
    form: DeviceForm,
    television: Boolean,
): KeyboardUiState {
    val spec = resolveLayout(settings.customLayouts, layoutId)
    val enabled = if (spec.id in settings.enabledLayoutIds) {
        settings.enabledLayoutIds
    } else {
        settings.enabledLayoutIds + spec.id
    }
    val shown = settings.copy(
        activeLayoutId = spec.id,
        enabledLayoutIds = enabled,
        floatingKeyboard = false,
        oneHandedMode = OneHandedMode.OFF,
    )
    val script = spec.script()
    return KeyboardUiState(
        settings = shown,
        language = spec.language(),
        script = script,
        composer = composerFor(script, spec.composerType()),
        layoutId = spec.id,
        layoutName = spec.name,
        layouts = PreviewLayoutSets.get(spec, form, shown.numberRow, shown.customLayouts, television),
    )
}

/**
 * The compiled grids behind the cards, kept across recompositions and across
 * visits to the screen.
 *
 * A card is rebuilt whenever the settings change — switching a layout on is a
 * settings change — and a carousel scrolled back and forth composes the same
 * cards again and again. Compiling five layers of a Keyman grid each time would
 * be the most expensive thing a card does, for a result that has not changed.
 * Keyed like the service's own cache: by the spec's value, and by the secondary
 * list's identity, which the settings flow keeps until the list changes.
 */
private object PreviewLayoutSets {
    private data class Key(
        val spec: LayoutSpec,
        val form: DeviceForm,
        val numberRow: Boolean,
        val television: Boolean,
    )

    private const val CAPACITY = 32

    private val sets = object : LinkedHashMap<Key, Pair<Map<String, KeyboardLayout>, LayoutSet>>(
        CAPACITY, 0.75f, true,
    ) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<Key, Pair<Map<String, KeyboardLayout>, LayoutSet>>?,
        ): Boolean = size > CAPACITY
    }

    private var secondaryCache: Pair<List<LayoutSpec>, Map<String, KeyboardLayout>>? = null

    @Synchronized
    fun get(
        spec: LayoutSpec,
        form: DeviceForm,
        numberRow: Boolean,
        customs: List<LayoutSpec>,
        television: Boolean,
    ): LayoutSet {
        val secondaries = secondaryCache?.takeIf { it.first === customs }?.second
            ?: compileSecondaryGrids(customs).also { secondaryCache = customs to it }
        val key = Key(spec, form, numberRow, television)
        sets[key]?.let { (grids, set) -> if (grids === secondaries) return set }
        val set = compileLayoutSet(spec, FieldKind.TEXT, form, numberRow, secondaries, television)
        sets[key] = secondaries to set
        return set
    }
}

/**
 * Measures [content] at the screen's width, bounded by the screen's height as
 * the keyboard's window bounds it, and draws it scaled to the width on offer.
 * Reports the scaled size, so the caller's height follows the board's.
 *
 * The scale goes on the placement layer, so anything clipping this node clips
 * the board where it is drawn rather than where it was measured.
 */
@Composable
private fun ScaledBoard(content: @Composable () -> Unit) {
    val configuration = LocalConfiguration.current
    val screenWidthDp = configuration.screenWidthDp
    val screenHeightDp = configuration.screenHeightDp
    Layout(content = content) { measurables, constraints ->
        val fullWidth = screenWidthDp.dp.roundToPx().coerceAtLeast(1)
        val placeable = measurables.first().measure(
            Constraints(
                minWidth = fullWidth,
                maxWidth = fullWidth,
                maxHeight = screenHeightDp.dp.roundToPx().coerceAtLeast(1),
            ),
        )
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else fullWidth
        val scale = width.toFloat() / placeable.width.coerceAtLeast(1)
        val height = (placeable.height * scale).roundToInt()
        layout(width, constraints.constrainHeight(height)) {
            placeable.placeWithLayer(0, 0) {
                scaleX = scale
                scaleY = scale
                transformOrigin = TransformOrigin(0f, 0f)
            }
        }
    }
}
