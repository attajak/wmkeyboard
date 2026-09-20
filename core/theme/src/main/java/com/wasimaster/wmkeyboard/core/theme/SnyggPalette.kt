package com.wasimaster.wmkeyboard.core.theme

/**
 * The Material colour roles a snygg stylesheet can name.
 *
 * FlorisBoard lets a value be `dynamic-light-color(primary)` or
 * `dynamic-dark-color(surfaceContainerLow)`, which it resolves against the
 * device's own Material You palette at draw time. Two of the largest theme
 * packs in circulation — FloriStyle and Gboardish — write **every** colour that
 * way, so a converter that cannot resolve them imports those themes as an
 * empty shell: the user picks a theme called "Midnight" and gets the app's
 * stock dark grey.
 *
 * So the roles are resolved here instead, once, at import. The palette is
 * handed in rather than read: this file is pure JVM so that the whole
 * conversion stays unit-testable, and `:app` passes the device palette (see
 * `dynamicSnyggPalette`) on Android 12 and up. Off that, [Baseline] stands in.
 *
 * ### Resolving once is not the same as resolving live
 *
 * FlorisBoard re-resolves when the wallpaper changes. A [ThemeSpec] holds
 * literal colours, so a converted theme is a snapshot of the palette the device
 * had at import. That is the honest trade: the alternative is a theme with no
 * colours at all. The import reports it as [FlexUnsupported.DYNAMIC_COLOR] so
 * the user knows their theme will not follow a new wallpaper on its own.
 */
data class SnyggPalette(
    /** Role name (normalized, lowercase) to ARGB, for the light scheme. */
    val light: Map<String, Long>,
    /** Role name (normalized, lowercase) to ARGB, for the dark scheme. */
    val dark: Map<String, Long>,
) {

    /**
     * One role, or null when this palette has no answer for that name.
     *
     * Null matters: a role this build does not know must leave the property
     * unset — falling back to some other role would paint a surface a colour
     * the stylesheet never asked for, which is the failure mode the whole
     * mapper is built to avoid.
     */
    fun resolve(night: Boolean, role: String): Long? =
        (if (night) dark else light)[normalizeRole(role)]

    companion object {

        /** Lowercased, separators dropped, so `surfaceContainerLow` == `surface-container-low`. */
        fun normalizeRole(role: String): String =
            role.trim().lowercase().filter { it.isLetterOrDigit() }

        /**
         * The Material 3 baseline scheme, used when the device has no dynamic
         * palette (below Android 12) or when the conversion runs off-device.
         *
         * These are the published baseline tokens, not values read from any
         * particular implementation. A theme resolved against them is not the
         * one FlorisBoard would draw on a Material You phone, but it is a
         * coherent Material theme rather than a pile of nulls.
         */
        val Baseline: SnyggPalette = SnyggPalette(
            light = buildRoles(
                primary = 0xFF6750A4, onPrimary = 0xFFFFFFFF,
                primaryContainer = 0xFFEADDFF, onPrimaryContainer = 0xFF21005D,
                secondary = 0xFF625B71, onSecondary = 0xFFFFFFFF,
                secondaryContainer = 0xFFE8DEF8, onSecondaryContainer = 0xFF1D192B,
                tertiary = 0xFF7D5260, onTertiary = 0xFFFFFFFF,
                tertiaryContainer = 0xFFFFD8E4, onTertiaryContainer = 0xFF31111D,
                background = 0xFFFEF7FF, onBackground = 0xFF1D1B20,
                surface = 0xFFFEF7FF, onSurface = 0xFF1D1B20,
                surfaceVariant = 0xFFE7E0EC, onSurfaceVariant = 0xFF49454F,
                surfaceDim = 0xFFDED8E1, surfaceBright = 0xFFFEF7FF,
                surfaceContainerLowest = 0xFFFFFFFF, surfaceContainerLow = 0xFFF7F2FA,
                surfaceContainer = 0xFFF3EDF7, surfaceContainerHigh = 0xFFECE6F0,
                surfaceContainerHighest = 0xFFE6E0E9,
                outline = 0xFF79747E, outlineVariant = 0xFFCAC4D0,
                inverseSurface = 0xFF322F35, inverseOnSurface = 0xFFF5EFF7,
                inversePrimary = 0xFFD0BCFF,
                error = 0xFFB3261E, onError = 0xFFFFFFFF,
                errorContainer = 0xFFF9DEDC, onErrorContainer = 0xFF410E0B,
                scrim = 0xFF000000,
            ),
            dark = buildRoles(
                primary = 0xFFD0BCFF, onPrimary = 0xFF381E72,
                primaryContainer = 0xFF4F378B, onPrimaryContainer = 0xFFEADDFF,
                secondary = 0xFFCCC2DC, onSecondary = 0xFF332D41,
                secondaryContainer = 0xFF4A4458, onSecondaryContainer = 0xFFE8DEF8,
                tertiary = 0xFFEFB8C8, onTertiary = 0xFF492532,
                tertiaryContainer = 0xFF633B48, onTertiaryContainer = 0xFFFFD8E4,
                background = 0xFF141218, onBackground = 0xFFE6E0E9,
                surface = 0xFF141218, onSurface = 0xFFE6E0E9,
                surfaceVariant = 0xFF49454F, onSurfaceVariant = 0xFFCAC4D0,
                surfaceDim = 0xFF141218, surfaceBright = 0xFF3B383E,
                surfaceContainerLowest = 0xFF0F0D13, surfaceContainerLow = 0xFF1D1B20,
                surfaceContainer = 0xFF211F26, surfaceContainerHigh = 0xFF2B2930,
                surfaceContainerHighest = 0xFF36343B,
                outline = 0xFF938F99, outlineVariant = 0xFF49454F,
                inverseSurface = 0xFFE6E0E9, inverseOnSurface = 0xFF322F35,
                inversePrimary = 0xFF6750A4,
                error = 0xFFF2B8B5, onError = 0xFF601410,
                errorContainer = 0xFF8C1D18, onErrorContainer = 0xFFF9DEDC,
                scrim = 0xFF000000,
            ),
        )

        /**
         * A role map from the tokens that name it.
         *
         * Spelled out as named parameters rather than a map literal so that a
         * role added later has to be given a value in both schemes: a map would
         * let one half silently miss it, and a missing role is a property the
         * import drops.
         */
        @Suppress("LongParameterList")
        fun buildRoles(
            primary: Long, onPrimary: Long, primaryContainer: Long, onPrimaryContainer: Long,
            secondary: Long, onSecondary: Long, secondaryContainer: Long, onSecondaryContainer: Long,
            tertiary: Long, onTertiary: Long, tertiaryContainer: Long, onTertiaryContainer: Long,
            background: Long, onBackground: Long,
            surface: Long, onSurface: Long, surfaceVariant: Long, onSurfaceVariant: Long,
            surfaceDim: Long, surfaceBright: Long,
            surfaceContainerLowest: Long, surfaceContainerLow: Long, surfaceContainer: Long,
            surfaceContainerHigh: Long, surfaceContainerHighest: Long,
            outline: Long, outlineVariant: Long,
            inverseSurface: Long, inverseOnSurface: Long, inversePrimary: Long,
            error: Long, onError: Long, errorContainer: Long, onErrorContainer: Long,
            scrim: Long,
        ): Map<String, Long> = buildMap {
            fun role(name: String, value: Long) = put(normalizeRole(name), value)
            role("primary", primary)
            role("onPrimary", onPrimary)
            role("primaryContainer", primaryContainer)
            role("onPrimaryContainer", onPrimaryContainer)
            role("secondary", secondary)
            role("onSecondary", onSecondary)
            role("secondaryContainer", secondaryContainer)
            role("onSecondaryContainer", onSecondaryContainer)
            role("tertiary", tertiary)
            role("onTertiary", onTertiary)
            role("tertiaryContainer", tertiaryContainer)
            role("onTertiaryContainer", onTertiaryContainer)
            role("background", background)
            role("onBackground", onBackground)
            role("surface", surface)
            role("onSurface", onSurface)
            role("surfaceVariant", surfaceVariant)
            role("onSurfaceVariant", onSurfaceVariant)
            role("surfaceTint", primary)
            role("surfaceDim", surfaceDim)
            role("surfaceBright", surfaceBright)
            role("surfaceContainerLowest", surfaceContainerLowest)
            role("surfaceContainerLow", surfaceContainerLow)
            role("surfaceContainer", surfaceContainer)
            role("surfaceContainerHigh", surfaceContainerHigh)
            role("surfaceContainerHighest", surfaceContainerHighest)
            role("outline", outline)
            role("outlineVariant", outlineVariant)
            role("inverseSurface", inverseSurface)
            role("inverseOnSurface", inverseOnSurface)
            role("inversePrimary", inversePrimary)
            role("error", error)
            role("onError", onError)
            role("errorContainer", errorContainer)
            role("onErrorContainer", onErrorContainer)
            role("scrim", scrim)
        }
    }
}
