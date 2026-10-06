package com.wasimaster.wmkeyboard.app

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Calculate
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Mood
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.QrCode
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material.icons.outlined.Widgets
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wasimaster.wmkeyboard.core.icons.SymbolIcons
import com.wasimaster.wmkeyboard.core.layout.Key
import com.wasimaster.wmkeyboard.core.layout.KeyAction
import com.wasimaster.wmkeyboard.core.layout.LayoutLayer
import com.wasimaster.wmkeyboard.core.layout.LayoutSpec

// Miniature keyboards and feature scenes for the wizard. Every one of them
// answers a question the words on the page cannot: which of three Bengali
// input systems this is, what "emoji key instead of 🌐" does to the bottom
// row, what a smart chip actually looks like when it appears. They are drawn
// rather than shipped as images, so they follow the theme and cost nothing in
// the APK.

/** Height of one key in every miniature here. */
private val MiniKeyHeight: Dp = 22.dp

/** Gap between miniature keys, horizontally and between rows. */
private val MiniKeyGap: Dp = 3.dp

/** Size of a key's letter. Set against a matching line height — see [MiniKeyLabel]. */
private val MiniKeyFontSize: TextUnit = 13.5.sp

/** How many rows of a real layout the layout-picker miniature draws. */
private const val MINI_LAYOUT_ROWS = 4

/**
 * A miniature of a layout's own letter grid, key for key.
 *
 * Reads the [LayoutLayer.LETTERS] rows straight off the spec, so a phonetic
 * layout and a native one look as different here as they do on the keyboard —
 * which is the entire point of showing it while someone chooses between them.
 */
@Composable
internal fun MiniLayoutPreview(spec: LayoutSpec, modifier: Modifier = Modifier) {
    val rows = spec.layers[LayoutLayer.LETTERS.key]?.rows.orEmpty().take(MINI_LAYOUT_ROWS)
    if (rows.isEmpty()) return
    MiniBoard(modifier) {
        for (row in rows) {
            MiniRow {
                for (key in row) {
                    MiniKey(miniKeyLabel(key), Modifier.weight(key.width.coerceIn(0.5f, 5f)))
                }
            }
        }
    }
}

/**
 * What a key shows in a miniature. Only text keys carry their label: an
 * icon-drawn key would show a name like "backspace" at 6sp, and the shapes of
 * the modifier keys read fine as blanks.
 */
private fun miniKeyLabel(key: Key): String = when (key.action) {
    KeyAction.Text -> key.label.take(2)
    else -> ""
}

/** Which part of [MiniKeyboardPreview] the setting under it is about. */
internal enum class MiniKeyHighlight { NONE, GLOBE, NUMBER_ROW }

private val MiniQwertyRows = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")

/**
 * A miniature English board, with the two things the gestures page can change
 * — the digit row and the key left of the spacebar — drawn as they are set.
 *
 * Deliberately not the user's own layout: this preview is about the *shape* of
 * the board, and a phonetic Bengali grid would make the reader work out which
 * of the differences is the one the switch controls.
 */
@Composable
internal fun MiniKeyboardPreview(
    numberRow: Boolean,
    globeAsEmoji: Boolean,
    showGlobeKey: Boolean = true,
    highlight: MiniKeyHighlight = MiniKeyHighlight.NONE,
    modifier: Modifier = Modifier,
) {
    // The digit row's height is held whether or not the digits are there —
    // otherwise flipping the switch under this preview resizes it, and
    // everything below moves out from under the user's thumb. The reserved
    // strip sits *outside* the board, so a board without digits is simply a
    // shorter board rather than one with an empty shelf inside it.
    Column(
        verticalArrangement = Arrangement.Bottom,
        modifier = modifier.height(MiniBoardHeight),
    ) {
        MiniBoard {
            if (numberRow) {
                MiniRow(highlighted = highlight == MiniKeyHighlight.NUMBER_ROW) {
                    for (digit in "1234567890") {
                        MiniKey(digit.toString(), Modifier.weight(1f))
                    }
                }
            }
            MiniQwertyBody(globeAsEmoji, highlight, showGlobeKey)
        }
    }
}

/** The three letter rows and the bottom row, shared by every full miniature. */
@Composable
private fun MiniQwertyBody(
    globeAsEmoji: Boolean,
    highlight: MiniKeyHighlight,
    showGlobeKey: Boolean = true,
) {
    for ((index, row) in MiniQwertyRows.withIndex()) {
        MiniRow {
            // The bottom letter row keeps shift and backspace, so the
            // miniature is recognisable as a keyboard rather than as three
            // rows of letters.
            if (index == 2) MiniKey("", Modifier.weight(1.5f))
            for (letter in row) {
                MiniKey(letter.toString(), Modifier.weight(1f))
            }
            if (index == 2) MiniKey("", Modifier.weight(1.5f))
        }
    }
    MiniRow {
        MiniKey("", Modifier.weight(1.5f))
        // Hidden, the key's width goes to the spacebar, as it does on the
        // keyboard (issue #139).
        if (showGlobeKey) {
            MiniKeyIcon(
                icon = if (globeAsEmoji) Icons.Outlined.Mood else Icons.Outlined.Language,
                highlighted = highlight == MiniKeyHighlight.GLOBE,
                modifier = Modifier.weight(1f),
            )
        }
        MiniKey("", Modifier.weight(if (showGlobeKey) 4f else 5f))
        MiniKey("", Modifier.weight(1f))
        MiniKey("", Modifier.weight(1.5f))
    }
}

/**
 * Height of a full miniature with its digit row: five key rows, the gaps
 * between them, and the board's own padding. Held constant so the digit-row
 * switch cannot move the page under the reader.
 */
private val MiniBoardHeight: Dp = MiniKeyHeight * 5 + MiniKeyGap * 4 + 12.dp

/** The board these miniatures sit on: one rounded, tinted panel. */
@Composable
private fun MiniBoard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(
        verticalArrangement = Arrangement.spacedBy(MiniKeyGap),
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .padding(6.dp),
    ) { content() }
}

@Composable
private fun MiniRow(
    highlighted: Boolean = false,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(MiniKeyGap),
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (highlighted) {
                    Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.18f))
                } else {
                    Modifier
                },
            ),
        content = content,
    )
}

@Composable
private fun MiniKey(label: String, modifier: Modifier = Modifier) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .height(MiniKeyHeight)
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.surface),
    ) {
        if (label.isNotEmpty()) MiniKeyLabel(label)
    }
}

/**
 * A key's letter, sitting in the middle of the key rather than near its floor.
 *
 * Centring the `Text` is not enough on its own: what gets centred is the line
 * box, and a line box carries the font's ascent and descent whether or not the
 * glyph uses them. At 9sp that leftover space is a visible drop. Trimming the
 * line box to the glyph and dropping the platform's extra font padding is what
 * actually puts an "a" in the centre of the key.
 */
@Composable
private fun MiniKeyLabel(label: String) {
    Text(
        label,
        maxLines = 1,
        overflow = TextOverflow.Clip,
        color = MaterialTheme.colorScheme.onSurface,
        style = LocalTextStyle.current.copy(
            fontSize = MiniKeyFontSize,
            lineHeight = MiniKeyFontSize,
            platformStyle = PlatformTextStyle(includeFontPadding = false),
            lineHeightStyle = LineHeightStyle(
                alignment = LineHeightStyle.Alignment.Center,
                trim = LineHeightStyle.Trim.Both,
            ),
        ),
    )
}

@Composable
private fun MiniKeyIcon(icon: ImageVector, highlighted: Boolean, modifier: Modifier = Modifier) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .height(MiniKeyHeight)
            .clip(RoundedCornerShape(4.dp))
            .background(
                if (highlighted) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.surface,
            ),
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (highlighted) MaterialTheme.colorScheme.onPrimary
            else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(14.dp),
        )
    }
}

// ---- discover-page illustrations ----

/** Height every discover card's illustration is drawn at. */
internal val DiscoverPreviewHeight: Dp = 62.dp

/**
 * The picture on a discover card: a tiny staged scene of the feature doing its
 * thing — an answer chip appearing over the keys, a code arriving, a waveform
 * moving under a dictation.
 *
 * Drawn from the same primitives as the keyboard miniatures rather than
 * shipped as GIFs. A dozen animated images would be a megabyte of APK that
 * cannot follow the theme, and a still screenshot of a keyboard at 62 dp is
 * unreadable — what carries at this size is one moving element and one word.
 */
@Composable
internal fun DiscoverPreview(
    id: String,
    accent: Color,
    animate: Boolean,
    modifier: Modifier = Modifier,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .fillMaxWidth()
            .height(DiscoverPreviewHeight)
            .clip(RoundedCornerShape(12.dp))
            .background(accent.copy(alpha = 0.12f)),
    ) {
        when (id) {
            "chips" -> ChipScene("12*8=", "96", accent, animate)
            "hotwords" -> ChipScene("translate", "⇱", accent, animate)
            "otp" -> ChipScene("code", "482 913", accent, animate)
            "toolbox" -> ToolboxScene(accent, animate)
            "clipboard" -> ClipboardScene(accent, animate)
            "snippets" -> SnippetScene(accent, animate)
            "photos" -> PhotoBoardScene(accent, animate)
            "modes" -> PerAppScene(accent, animate)
            "whisper" -> WaveScene(accent, animate)
            "fancy" -> FancyScene(accent, animate)
            "ai" -> AiScene(accent, animate)
            else -> ToggleScene(accent, animate)
        }
    }
}

/** Typed text on the left, the chip the keyboard offers for it on the right. */
@Composable
private fun ChipScene(typed: String, chip: String, accent: Color, animate: Boolean) {
    val pulse = pulseAlpha(animate)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(typed, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box(
            modifier = Modifier
                .alpha(pulse)
                .clip(RoundedCornerShape(8.dp))
                .background(accent)
                .padding(horizontal = 8.dp, vertical = 3.dp),
        ) {
            Text(chip, fontSize = 11.sp, color = Color.White, maxLines = 1)
        }
    }
}

/**
 * The toolbox: the grid key on the toolbar, and the tools it opens.
 *
 * Named glyphs rather than the ten identical dots this replaced — the pitch is
 * "seventy different things live behind one key", and ten dots said "there is
 * a grid", which is not the same claim.
 */
@Composable
private fun ToolboxScene(accent: Color, animate: Boolean) {
    val pulse = pulseAlpha(animate)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // The key it all comes out of.
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .alpha(pulse)
                .size(24.dp)
                .clip(RoundedCornerShape(7.dp))
                .background(accent),
        ) {
            Icon(
                Icons.Outlined.Widgets,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(15.dp),
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            for (row in ToolboxSceneGlyphs) {
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    for (glyph in row) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(17.dp)
                                .clip(RoundedCornerShape(50))
                                .background(accent.copy(alpha = 0.25f)),
                        ) {
                            Icon(
                                glyph,
                                contentDescription = null,
                                tint = accent,
                                modifier = Modifier.size(11.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** The tools the toolbox scene shows, two rows of four. */
private val ToolboxSceneGlyphs: List<List<ImageVector>> = listOf(
    listOf(
        Icons.Outlined.Translate,
        Icons.Outlined.Calculate,
        SymbolIcons.GifBox,
        Icons.Outlined.QrCode,
    ),
    listOf(
        Icons.Outlined.Mic,
        Icons.Outlined.Palette,
        Icons.Outlined.ContentPaste,
        Icons.Outlined.MoreHoriz,
    ),
)

/**
 * The clipboard: a stack of things you copied, with the newest one holding the
 * chip the keyboard pulled out of it.
 *
 * The card the user is being sold is not "a list exists" — it is that a copied
 * message becomes a tappable code — so the code is what the picture shows.
 */
@Composable
private fun ClipboardScene(accent: Color, animate: Boolean) {
    val pulse = pulseAlpha(animate)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            modifier = Modifier
                .alpha(pulse)
                .clip(RoundedCornerShape(6.dp))
                .background(accent)
                .padding(horizontal = 8.dp, vertical = 3.dp),
        ) {
            Text("2FA 8391", fontSize = 10.sp, color = Color.White, maxLines = 1)
        }
        // The older entries, receding.
        repeat(2) { row ->
            Box(
                modifier = Modifier
                    .width((78 - row * 20).dp)
                    .height(9.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(accent.copy(alpha = 0.4f - row * 0.15f)),
            )
        }
    }
}

/**
 * Pattern snippets: a short trigger on the left, the block of text it turns
 * into on the right. The arrow is the whole feature.
 */
@Composable
private fun SnippetScene(accent: Color, animate: Boolean) {
    val pulse = pulseAlpha(animate)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(accent.copy(alpha = 0.35f))
                .padding(horizontal = 7.dp, vertical = 3.dp),
        ) {
            Text("/addr", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
        }
        Text("→", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(
            verticalArrangement = Arrangement.spacedBy(3.dp),
            modifier = Modifier.alpha(pulse),
        ) {
            TextLine(width = 50.dp, color = accent, alpha = 1f)
            TextLine(width = 42.dp, color = accent, alpha = 1f)
            TextLine(width = 34.dp, color = accent, alpha = 1f)
        }
    }
}

/**
 * Photo backgrounds: translucent keys over a picture, with a second picture
 * sliding in behind them — the rotation is half of what that card sells.
 */
@Composable
private fun PhotoBoardScene(accent: Color, animate: Boolean) {
    val pulse = pulseAlpha(animate)
    Box(contentAlignment = Alignment.Center) {
        // The next photo in the rotation, peeking out behind the current one.
        Box(
            modifier = Modifier
                .offset(x = 10.dp, y = (-7).dp)
                .alpha(pulse)
                .size(width = 92.dp, height = 40.dp)
                .clip(RoundedCornerShape(7.dp))
                .background(
                    Brush.linearGradient(
                        listOf(accent.copy(alpha = 0.75f), Color(0xFF5C6BC0).copy(alpha = 0.75f)),
                    ),
                ),
        )
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(width = 100.dp, height = 44.dp)
                .clip(RoundedCornerShape(7.dp))
                .background(
                    Brush.linearGradient(
                        listOf(accent, Color(0xFFFFB74D)),
                    ),
                ),
        ) {
            MiniGhostKeys(rows = 2, perRow = 6)
        }
    }
}

/**
 * Per-app keyboards: two boards, each wearing a different app's colours, with
 * that app's glyph on it. One board could not say "a different one per app".
 */
@Composable
private fun PerAppScene(accent: Color, animate: Boolean) {
    val pulse = pulseAlpha(animate)
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        PerAppBoard(Icons.AutoMirrored.Outlined.Chat, accent, 1f)
        PerAppBoard(Icons.Outlined.Code, Color(0xFF26A69A), pulse)
    }
}

@Composable
private fun PerAppBoard(app: ImageVector, tint: Color, alpha: Float) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp),
        modifier = Modifier.alpha(alpha),
    ) {
        Icon(app, contentDescription = null, tint = tint, modifier = Modifier.size(12.dp))
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(width = 46.dp, height = 30.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(tint.copy(alpha = 0.55f)),
        ) {
            MiniGhostKeys(rows = 2, perRow = 4)
        }
    }
}

/** Blank key shapes, for the scenes that draw a board rather than a feature. */
@Composable
private fun MiniGhostKeys(rows: Int, perRow: Int) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        repeat(rows) {
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                repeat(perRow) {
                    Box(
                        modifier = Modifier
                            .size(width = 12.dp, height = 9.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(Color.White.copy(alpha = 0.55f)),
                    )
                }
            }
        }
    }
}

/** Dictation: bars that rise and fall like a level meter. */
@Composable
private fun WaveScene(accent: Color, animate: Boolean) {
    val transition = rememberInfiniteTransition(label = "wave")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "wavePhase",
    )
    val moving = if (animate) phase else 0.5f
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.height(34.dp),
    ) {
        // Each bar is a fixed fraction of one animated phase, so they breathe
        // together rather than marching — one timer, seven heights.
        listOf(0.35f, 0.7f, 1f, 0.55f, 0.9f, 0.45f, 0.25f).forEach { scale ->
            Box(
                modifier = Modifier
                    .width(5.dp)
                    .fillMaxHeight((0.25f + moving * scale * 0.75f).coerceAtMost(1f))
                    .clip(RoundedCornerShape(3.dp))
                    .background(accent),
            )
        }
    }
}

/** Styled text: the fancy-text strip, and the AI rewrite. */
@Composable
private fun FancyScene(accent: Color, animate: Boolean) {
    val pulse = pulseAlpha(animate)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("Aa", fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("→", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box(
            modifier = Modifier
                .alpha(pulse)
                .clip(RoundedCornerShape(8.dp))
                .background(accent)
                .padding(horizontal = 8.dp, vertical = 3.dp),
        ) {
            Text("𝓐𝓪", fontSize = 15.sp, color = Color.White)
        }
    }
}

/**
 * The AI tools: a rough line of text being rewritten into a clean one, with
 * the wand doing it.
 *
 * Its own scene rather than the fancy-text one in another colour — the two
 * cards sit in the same grid, and two identical pictures say the two features
 * are the same feature.
 */
@Composable
private fun AiScene(accent: Color, animate: Boolean) {
    val pulse = pulseAlpha(animate)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Before: a short, ragged draft.
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            TextLine(width = 46.dp, color = MaterialTheme.colorScheme.onSurfaceVariant, alpha = 0.35f)
            TextLine(width = 30.dp, color = MaterialTheme.colorScheme.onSurfaceVariant, alpha = 0.35f)
        }
        Icon(
            Icons.Outlined.AutoAwesome,
            contentDescription = null,
            tint = accent,
            modifier = Modifier
                .size(18.dp)
                .alpha(pulse),
        )
        // After: longer, even, and in the tool's own colour.
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            TextLine(width = 52.dp, color = accent, alpha = pulse)
            TextLine(width = 44.dp, color = accent, alpha = pulse)
        }
    }
}

/** One bar standing in for a line of text. */
@Composable
private fun TextLine(width: Dp, color: Color, alpha: Float) {
    Box(
        modifier = Modifier
            .alpha(alpha)
            .width(width)
            .height(6.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(color),
    )
}

/** Two switches on the bar: incognito and autocorrect. */
@Composable
private fun ToggleScene(accent: Color, animate: Boolean) {
    val pulse = pulseAlpha(animate)
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        repeat(2) { index ->
            val on = index == 0
            Box(
                contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart,
                modifier = Modifier
                    .alpha(if (on) pulse else 1f)
                    .size(width = 34.dp, height = 18.dp)
                    .clip(RoundedCornerShape(50))
                    .background(
                        if (on) accent else MaterialTheme.colorScheme.surfaceContainerHighest,
                    )
                    .border(1.dp, accent.copy(alpha = 0.5f), RoundedCornerShape(50))
                    .padding(2.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(14.dp)
                        .clip(RoundedCornerShape(50))
                        .background(
                            if (on) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                )
            }
        }
    }
}

/**
 * The one animation every scene shares: a slow breath on whatever the feature
 * actually produces. Held at full opacity when the user has asked for less
 * motion, so the scene still reads as a picture rather than disappearing.
 */
@Composable
private fun pulseAlpha(animate: Boolean): Float {
    if (!animate) return 1f
    val transition = rememberInfiniteTransition(label = "pulse")
    val alpha by transition.animateFloat(
        initialValue = 0.45f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1100), RepeatMode.Reverse),
        label = "pulseAlpha",
    )
    return alpha
}
