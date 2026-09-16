package com.wasimaster.wmkeyboard.app

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * The settings app's own colour scheme and shape scale.
 *
 * Stock `lightColorScheme()` / `darkColorScheme()` is Material's baseline
 * purple, which every unthemed Compose app on the phone also wears — after
 * [WmTypography] it was the app's last "this is a sample project" tell. The
 * scheme below is built from the launcher icon instead, so the app and its icon
 * are the same object: the icon's ground is a deep indigo (`#2F3474` → `#191C42`)
 * and its keyboard body sweeps blue to violet (`#5D9BFF` → `#A78BFA`), which in
 * CIELAB is a hue band from roughly 280° to 307°. Every accent palette here is
 * pinned at **300°**, the middle of that band, and the neutrals at **296°**, the
 * ground's own hue, so even the greys carry a trace of the brand rather than
 * being flat AOSP grey.
 *
 * Tones are generated the way Material generates them — one hue and chroma per
 * palette, sampled at fixed lightnesses — but in CIELAB rather than HCT, with
 * chroma clamped down per tone until the colour fits sRGB. `L*` **is** the tone
 * in both systems, so the contrast guarantees Material's tone pairs are built on
 * (a 40 on a 100, a 90 on a 10) hold exactly as they do in a generated M3 scheme;
 * only the chroma rolls off slightly differently. The generator lives in the
 * commit that added this file; the values are checked in because a palette that
 * is regenerated on every build is a palette nobody can review a diff of.
 *
 * Chroma per palette, in Lab units: primary 62, secondary 22, tertiary 33,
 * neutral 5.5, neutral-variant 11 — Material's own 48/16/24/4/8 in HCT, scaled
 * by the ~1.45 ratio between the two chroma scales at these lightnesses.
 * Tertiary is pushed to 345° (rose) rather than the mechanical primary + 60°,
 * which would land it at 0° on top of the error red.
 *
 * **Error roles are deliberately not overridden.** Material's baseline error
 * palette is what a user reads as "this is the dangerous one" across every app
 * on the device, and a brand-tinted red buys nothing in exchange for making a
 * destructive confirmation less legible as one.
 *
 * The user can still hand the whole thing over to Monet — see the `dynamicColor`
 * setting — but that now starts **off**, because a dynamic scheme repaints the
 * app in the wallpaper's hue and erases exactly what this file is for.
 */

// ---- palettes ----------------------------------------------------------

// Primary — hue 300°, chroma 62. The icon's blue-to-violet sweep, averaged.
private val Primary10 = Color(0xFF001257)
private val Primary20 = Color(0xFF162481)
private val Primary30 = Color(0xFF39399C)
private val Primary40 = Color(0xFF5751B7)
private val Primary80 = Color(0xFFCBBEFF)
private val Primary90 = Color(0xFFE6DEFF)

// Secondary — same hue, chroma 22. The muted twin, for the quieter accents.
private val Secondary10 = Color(0xFF1B1835)
private val Secondary20 = Color(0xFF312C4C)
private val Secondary30 = Color(0xFF484264)
private val Secondary40 = Color(0xFF615A7D)
private val Secondary80 = Color(0xFFCAC1EA)
private val Secondary90 = Color(0xFFE6DEFF)

// Tertiary — hue 345°, chroma 33. Rose: distinct from both indigo and the
// error red, so a third accent never reads as a warning.
private val Tertiary10 = Color(0xFF3C0028)
private val Tertiary20 = Color(0xFF55193D)
private val Tertiary30 = Color(0xFF6F3155)
private val Tertiary40 = Color(0xFF89496D)
private val Tertiary80 = Color(0xFFFAB1D7)
private val Tertiary90 = Color(0xFFFFD8EB)

// Neutral — hue 296° (the icon's ground), chroma 5.5. Backgrounds and rows.
private val Neutral0 = Color(0xFF000000)
private val Neutral4 = Color(0xFF0F0D16)
private val Neutral6 = Color(0xFF14121A)
private val Neutral10 = Color(0xFF1C1B22)
private val Neutral12 = Color(0xFF201F26)
private val Neutral17 = Color(0xFF2A2931)
private val Neutral20 = Color(0xFF302F37)
private val Neutral22 = Color(0xFF35343C)
private val Neutral24 = Color(0xFF393840)
private val Neutral87 = Color(0xFFDAD9E3)
private val Neutral90 = Color(0xFFE3E1EC)
private val Neutral92 = Color(0xFFE8E7F1)
private val Neutral94 = Color(0xFFEEEDF7)
private val Neutral95 = Color(0xFFF1EFFA)
private val Neutral96 = Color(0xFFF4F2FD)
private val Neutral98 = Color(0xFFF9F9FF)
private val Neutral100 = Color(0xFFFFFFFF)

// Neutral variant — same hue, chroma 11. Outlines and the surfaces that need
// to read as a different material from the rows sitting on them.
private val Variant30 = Color(0xFF474556)
private val Variant50 = Color(0xFF777588)
private val Variant60 = Color(0xFF918FA2)
private val Variant80 = Color(0xFFC7C4D9)
private val Variant90 = Color(0xFFE3E0F5)

// ---- schemes -----------------------------------------------------------

/** The app in daylight. Roles left out keep Material's baseline — see the file KDoc. */
internal val WmLightColors: ColorScheme = lightColorScheme(
    primary = Primary40,
    onPrimary = Neutral100,
    primaryContainer = Primary90,
    onPrimaryContainer = Primary10,
    inversePrimary = Primary80,
    secondary = Secondary40,
    onSecondary = Neutral100,
    secondaryContainer = Secondary90,
    onSecondaryContainer = Secondary10,
    tertiary = Tertiary40,
    onTertiary = Neutral100,
    tertiaryContainer = Tertiary90,
    onTertiaryContainer = Tertiary10,
    background = Neutral98,
    onBackground = Neutral10,
    surface = Neutral98,
    onSurface = Neutral10,
    surfaceVariant = Variant90,
    onSurfaceVariant = Variant30,
    surfaceTint = Primary40,
    inverseSurface = Neutral20,
    inverseOnSurface = Neutral95,
    outline = Variant50,
    outlineVariant = Variant80,
    scrim = Neutral0,
    surfaceBright = Neutral98,
    surfaceDim = Neutral87,
    surfaceContainerLowest = Neutral100,
    surfaceContainerLow = Neutral96,
    surfaceContainer = Neutral94,
    surfaceContainerHigh = Neutral92,
    surfaceContainerHighest = Neutral90,
)

/** The app after dark. */
internal val WmDarkColors: ColorScheme = darkColorScheme(
    primary = Primary80,
    onPrimary = Primary20,
    primaryContainer = Primary30,
    onPrimaryContainer = Primary90,
    inversePrimary = Primary40,
    secondary = Secondary80,
    onSecondary = Secondary20,
    secondaryContainer = Secondary30,
    onSecondaryContainer = Secondary90,
    tertiary = Tertiary80,
    onTertiary = Tertiary20,
    tertiaryContainer = Tertiary30,
    onTertiaryContainer = Tertiary90,
    background = Neutral6,
    onBackground = Neutral90,
    surface = Neutral6,
    onSurface = Neutral90,
    surfaceVariant = Variant30,
    onSurfaceVariant = Variant80,
    surfaceTint = Primary80,
    inverseSurface = Neutral90,
    inverseOnSurface = Neutral20,
    outline = Variant60,
    outlineVariant = Variant30,
    scrim = Neutral0,
    surfaceBright = Neutral24,
    surfaceDim = Neutral6,
    surfaceContainerLowest = Neutral4,
    surfaceContainerLow = Neutral10,
    surfaceContainer = Neutral12,
    surfaceContainerHigh = Neutral17,
    surfaceContainerHighest = Neutral22,
)

/**
 * The AMOLED pass over a dark scheme: the whole surface family driven down to
 * black and near-black, not just the page behind everything.
 *
 * This used to set `background` and `surface` alone, which is two of the eleven
 * roles Material actually draws a dark screen out of. Every settings row is a
 * `surfaceContainer`, dialogs and menus are `surfaceContainerHigh`, sheets are
 * `surfaceContainerLow` — so the page went true black and every card, bar and
 * dialog on it stayed the ordinary dark-grey, which on an OLED panel is the one
 * arrangement that looks worse than not having the mode at all: grey slabs
 * floating on black with no light source to justify them.
 *
 * The containers are not flattened to black with the background. Material's
 * elevation in dark themes is carried by these tones and nothing else — with
 * every role at `#000000` a row, the bar and the page become one undivided void
 * and the app loses its structure. They step 0 → 4 → 6 → 10 → 13 instead, which
 * on an OLED panel is still black for power purposes (four of five tones are
 * under 5% luminance) while leaving the edges of things visible.
 */
internal fun ColorScheme.amoled(): ColorScheme = copy(
    background = Neutral0,
    surface = Neutral0,
    surfaceDim = Neutral0,
    surfaceContainerLowest = Neutral0,
    surfaceContainerLow = Neutral4,
    surfaceContainer = Neutral6,
    surfaceContainerHigh = Neutral10,
    surfaceContainerHighest = Color(0xFF222128),
    surfaceBright = Neutral17,
)

// ---- shapes ------------------------------------------------------------

/**
 * The app's shape scale.
 *
 * Every Material component that has a corner reads `MaterialTheme.shapes` and
 * nothing else — buttons, cards, dialogs, menus, sheets, chips, text fields,
 * segmented buttons, the FAB — so one override here reaches all of them, which
 * is why this is the cheapest identity lever in the app.
 *
 * The scale is rounder than Material's 4/8/12/16/28 at every size below the
 * largest, to match the settings rows, whose 24 dp outer corners were already
 * hand-rolled in `SettingsGroup` long before this existed, and the launcher
 * icon's own geometry. `extraLarge` stays at 28 dp: it is what dialogs and
 * bottom sheets use, and Material's value there is already the round end of the
 * scale.
 *
 * The row corners in `SettingsGroup` are deliberately **not** migrated to these
 * tokens. A group draws a run of rows as one slab — 24 dp on the outside of the
 * run, 6 dp where two rows meet — which is a relationship between adjacent rows
 * rather than a size on the scale, and there is no token that means "the inside
 * of a stack".
 */
internal val WmShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp),
)
