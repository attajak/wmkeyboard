package com.wasimaster.wmkeyboard.ime.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.wasimaster.wmkeyboard.core.layout.KeyAction
import com.wasimaster.wmkeyboard.core.theme.BackgroundBitmapCache
import com.wasimaster.wmkeyboard.core.theme.KeyTextureScale
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The active theme's key textures, decoded once for the whole board.
 *
 * Keys read this through [LocalKeyTextures] and pick their class's bitmap by
 * [KeyAction] inside the draw cache — never through [KeyVisual] or the key
 * grid's memo, so a texture costs zero per-keystroke work and the
 * exhaustive-or-stale contract on `rememberKeyGrid` is untouched.
 *
 * The bitmaps come from [BackgroundBitmapCache], which buckets sizes and holds
 * an LRU under the IME's memory posture; the decode targets below keep the
 * whole set around a megabyte.
 */
@Immutable
class KeyTextures(
    val normal: ImageBitmap?,
    val modifier: ImageBitmap?,
    val enter: ImageBitmap?,
    val space: ImageBitmap?,
    val pressed: ImageBitmap?,
    /** Painted inside the key-preview bubble; no fallback from the key slots. */
    val popup: ImageBitmap?,
    val scale: KeyTextureScale,
    val opacity: Float,
    /**
     * Single-key textures, keyed as `ThemeSpec.keyOverrides` is (issue #107).
     * Empty for every theme that gives no one key a texture of its own, which
     * is what keeps [forKey] a map miss away from the class slots below.
     */
    val perKey: Map<String, ImageBitmap> = emptyMap(),
) {
    val isEmpty: Boolean
        get() = normal == null && modifier == null && enter == null &&
            space == null && pressed == null && popup == null && perKey.isEmpty()

    /**
     * The texture a key draws: its own, when the theme gave this one key one,
     * and otherwise its class's, with the same fallbacks the colour picks in
     * `keyVisual` use — enter falls to modifier falls to normal, space falls
     * to normal. Null means "colour only".
     *
     * [overrideId] is the key's `keyOverrideId`; null asks for the class
     * answer outright, which is what a key with no override has.
     */
    fun forKey(action: KeyAction, overrideId: String? = null): ImageBitmap? {
        if (overrideId != null && perKey.isNotEmpty()) {
            perKey[overrideId]?.let { return it }
        }
        return when {
            action == KeyAction.Enter -> enter ?: modifier ?: normal
            action == KeyAction.Space -> space ?: normal
            action != KeyAction.Text -> modifier ?: normal
            else -> normal
        }
    }

    companion object {
        val EMPTY = KeyTextures(null, null, null, null, null, null, KeyTextureScale.CROP, 1f)
    }
}

/** Provided beside [LocalKbTheme]; [KeyTextures.EMPTY] when the theme has none. */
val LocalKeyTextures = staticCompositionLocalOf { KeyTextures.EMPTY }

/**
 * One key's texture, prepared for its size and shape inside `drawWithCache`'s
 * cache block: the clip path, the source-crop window, or the tile brush are
 * all built there, so the press-path draw only issues the draw call.
 */
sealed class KeyTexturePaint {

    abstract fun draw(scope: DrawScope, alpha: Float)

    /** TILE: the bitmap repeats at its natural pixel size, as a shader. */
    private class Tiled(private val outline: Outline, private val brush: Brush) :
        KeyTexturePaint() {
        override fun draw(scope: DrawScope, alpha: Float) = with(scope) {
            drawOutline(outline, brush, alpha = alpha)
        }
    }

    /** CROP and STRETCH: the bitmap maps onto the key, clipped to its shape. */
    private class Fitted(
        private val clip: Path,
        private val bitmap: ImageBitmap,
        private val srcOffset: IntOffset,
        private val srcSize: IntSize,
        private val dstSize: IntSize,
    ) : KeyTexturePaint() {
        override fun draw(scope: DrawScope, alpha: Float) = with(scope) {
            clipPath(clip) {
                drawImage(
                    image = bitmap,
                    srcOffset = srcOffset,
                    srcSize = srcSize,
                    dstOffset = IntOffset.Zero,
                    dstSize = dstSize,
                    alpha = alpha,
                )
            }
        }
    }

    companion object {
        fun of(
            bitmap: ImageBitmap,
            textures: KeyTextures,
            outline: Outline,
            size: Size,
        ): KeyTexturePaint = when (textures.scale) {
            KeyTextureScale.TILE -> Tiled(
                outline,
                ShaderBrush(ImageShader(bitmap, TileMode.Repeated, TileMode.Repeated)),
            )
            KeyTextureScale.STRETCH, KeyTextureScale.CROP -> {
                val clip = Path().apply { addOutline(outline) }
                val bw = bitmap.width
                val bh = bitmap.height
                val dst = IntSize(
                    max(1, size.width.roundToInt()),
                    max(1, size.height.roundToInt()),
                )
                val (srcOffset, srcSize) = if (textures.scale == KeyTextureScale.STRETCH) {
                    IntOffset.Zero to IntSize(bw, bh)
                } else {
                    // Centre-crop: the largest window of the bitmap with the
                    // key's aspect ratio, so nothing distorts.
                    val scale = max(size.width / bw, size.height / bh)
                    val w = (size.width / scale).roundToInt().coerceIn(1, bw)
                    val h = (size.height / scale).roundToInt().coerceIn(1, bh)
                    IntOffset((bw - w) / 2, (bh - h) / 2) to IntSize(w, h)
                }
                Fitted(clip, bitmap, srcOffset, srcSize, dst)
            }
        }
    }
}

/** Longest edge a per-key texture decodes to. A key is a few dozen dp. */
private const val KEY_TEXTURE_PX = 256

/** The space bar is the one wide key, so its texture keeps more width. */
private const val SPACE_TEXTURE_W = 1024
private const val SPACE_TEXTURE_H = 320

/** The preview bubble is wider than a key but far smaller than the board. */
private const val POPUP_TEXTURE_W = 512
private const val POPUP_TEXTURE_H = 256

/**
 * Decodes the theme's key textures off the main thread, re-running only when
 * one of the paths (which snap at the crossfade midpoint) or the fit
 * parameters change. The first frames of a texture theme draw colour-only
 * while the decode lands — the same progressive appearance the background
 * image has always had.
 */
@Composable
fun rememberKeyTextures(kb: KbTheme): KeyTextures {
    // The single-key textures, pulled out first so the "nothing to decode"
    // shortcut below sees them: a theme may dress one key and no class at all.
    val perKeyPaths = kb.keyOverrideTexturePaths()
    val hasAny = kb.keyTexture != null || kb.keyTextureModifier != null ||
        kb.keyTextureEnter != null || kb.keyTextureSpace != null ||
        kb.keyTexturePressed != null || kb.popupTexture != null ||
        perKeyPaths.isNotEmpty()
    if (!hasAny) return KeyTextures.EMPTY
    val textures by produceState(
        initialValue = KeyTextures.EMPTY,
        kb.keyTexture,
        kb.keyTextureModifier,
        kb.keyTextureEnter,
        kb.keyTextureSpace,
        kb.keyTexturePressed,
        kb.popupTexture,
        kb.keyTextureScale,
        kb.keyTextureOpacity,
        perKeyPaths,
    ) {
        suspend fun load(path: String?, w: Int = KEY_TEXTURE_PX, h: Int = KEY_TEXTURE_PX) =
            path?.let { BackgroundBitmapCache.load(it, 0f, w, h)?.asImageBitmap() }
        value = KeyTextures(
            normal = load(kb.keyTexture),
            modifier = load(kb.keyTextureModifier),
            enter = load(kb.keyTextureEnter),
            space = load(kb.keyTextureSpace, SPACE_TEXTURE_W, SPACE_TEXTURE_H),
            pressed = load(kb.keyTexturePressed),
            popup = load(kb.popupTexture, POPUP_TEXTURE_W, POPUP_TEXTURE_H),
            scale = kb.keyTextureScale,
            opacity = kb.keyTextureOpacity,
            perKey = buildMap {
                for ((id, path) in perKeyPaths) {
                    // The space bar is the one wide key, here as in the class
                    // slots: a square decode on it reads as a smear.
                    val wide = id == SPACE_OVERRIDE_ID
                    val bitmap = if (wide) {
                        load(path, SPACE_TEXTURE_W, SPACE_TEXTURE_H)
                    } else {
                        load(path)
                    }
                    bitmap?.let { put(id, it) }
                }
            },
        )
    }
    return textures
}

/**
 * The theme's single-key texture paths, keyed by override id — a plain map so
 * `produceState` re-decodes on a value change rather than on every recomposed
 * theme instance.
 */
private fun KbTheme.keyOverrideTexturePaths(): Map<String, String> =
    if (keyOverrides.isEmpty()) {
        emptyMap()
    } else {
        buildMap {
            for ((id, override) in keyOverrides) override.texture?.let { put(id, it) }
        }
    }

/** The override id of the space bar; `keyOverrideId` spells it this way. */
private const val SPACE_OVERRIDE_ID = "SPACE"
