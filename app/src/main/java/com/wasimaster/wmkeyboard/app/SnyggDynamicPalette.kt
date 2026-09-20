package com.wasimaster.wmkeyboard.app

import android.content.Context
import android.os.Build
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.wasimaster.wmkeyboard.core.theme.SnyggPalette

/**
 * The device's Material You palette, in the shape a FlorisBoard stylesheet
 * names its colours.
 *
 * A stylesheet may write `dynamic-dark-color(surfaceContainerLow)` instead of a
 * hex value, and the two largest packs in circulation write every colour that
 * way. FlorisBoard resolves those against the wallpaper palette; so does this,
 * against the same system colours, so an imported theme lands on the colours
 * the original drew rather than on nothing.
 *
 * Below Android 12 there is no wallpaper palette to read, so the Material
 * baseline stands in. That is not what FlorisBoard would draw — but FlorisBoard
 * has the same problem on the same devices, and it falls back the same way.
 *
 * Lives in `:app` rather than `:core:theme` because the conversion itself is
 * pure JVM on purpose: the parser and the mapper are unit-tested without a
 * `Context`, and reading one here is the whole reason this is a separate file.
 */
fun dynamicSnyggPalette(context: Context): SnyggPalette {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return SnyggPalette.Baseline
    return runCatching {
        SnyggPalette(
            light = dynamicLightColorScheme(context).asRoles(),
            dark = dynamicDarkColorScheme(context).asRoles(),
            fromDevice = true,
        )
    }.getOrDefault(SnyggPalette.Baseline)
}

private fun Color.argb(): Long = toArgb().toLong() and 0xFFFFFFFFL

/**
 * A Compose scheme as the role map the resolver reads.
 *
 * Every role is named explicitly rather than reflected over, so a role
 * Material adds later is a deliberate line here instead of arriving unnoticed
 * under whatever name the library happened to give it.
 */
private fun ColorScheme.asRoles(): Map<String, Long> = SnyggPalette.buildRoles(
    primary = primary.argb(),
    onPrimary = onPrimary.argb(),
    primaryContainer = primaryContainer.argb(),
    onPrimaryContainer = onPrimaryContainer.argb(),
    secondary = secondary.argb(),
    onSecondary = onSecondary.argb(),
    secondaryContainer = secondaryContainer.argb(),
    onSecondaryContainer = onSecondaryContainer.argb(),
    tertiary = tertiary.argb(),
    onTertiary = onTertiary.argb(),
    tertiaryContainer = tertiaryContainer.argb(),
    onTertiaryContainer = onTertiaryContainer.argb(),
    background = background.argb(),
    onBackground = onBackground.argb(),
    surface = surface.argb(),
    onSurface = onSurface.argb(),
    surfaceVariant = surfaceVariant.argb(),
    onSurfaceVariant = onSurfaceVariant.argb(),
    surfaceDim = surfaceDim.argb(),
    surfaceBright = surfaceBright.argb(),
    surfaceContainerLowest = surfaceContainerLowest.argb(),
    surfaceContainerLow = surfaceContainerLow.argb(),
    surfaceContainer = surfaceContainer.argb(),
    surfaceContainerHigh = surfaceContainerHigh.argb(),
    surfaceContainerHighest = surfaceContainerHighest.argb(),
    outline = outline.argb(),
    outlineVariant = outlineVariant.argb(),
    inverseSurface = inverseSurface.argb(),
    inverseOnSurface = inverseOnSurface.argb(),
    inversePrimary = inversePrimary.argb(),
    error = error.argb(),
    onError = onError.argb(),
    errorContainer = errorContainer.argb(),
    onErrorContainer = onErrorContainer.argb(),
    scrim = scrim.argb(),
)
