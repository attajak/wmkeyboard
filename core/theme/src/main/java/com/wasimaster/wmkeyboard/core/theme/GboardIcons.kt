package com.wasimaster.wmkeyboard.core.theme

/**
 * Which of a Gboard theme's own key glyphs becomes which icon slot here.
 *
 * A Gboard theme replaces an icon by naming its drawable in a rule of its own:
 *
 * ```css
 * .icon_key_del { image_ref: "icon_del.png"; image_height: 25; }
 * ```
 *
 * 264 of the 491 themes in the Rboard repository do this, so for more than half
 * of them the glyphs *are* the theme — a converted Pokéball theme with Google's
 * own arrow on the enter key is not the theme anyone picked. They used to be
 * reported as [GboardUnsupported.KEY_ICONS] and dropped, because this app's
 * icon system took vectors only; it takes raster now (issue #504), so they come
 * across as [ThemeSpec.keyIcons].
 *
 * ### The names are Gboard's drawable names, and the ids are ours
 *
 * The left-hand side is a CSS class the themes in the repository actually carry
 * — every spelling below was counted across all 45 published packs, except the
 * action-key siblings (`..._go`, `..._done`, `..._next`, `..._previous`) and
 * `icon_key_globe`, which follow Gboard's own naming for actions no theme in the
 * repository happens to restyle. Listing them costs nothing and is the
 * difference between a theme's Done key matching its Send key or not.
 *
 * The right-hand side is a `core.icons.IconSlots` id, as a plain string:
 * `:core:icons` depends on `:core:settings`, which depends on this module, so
 * the two cannot see each other. `GboardIconSlotsTest` in `:app` — which sees
 * both — asserts every id here is one [IconSlots] knows, so a typo or a renamed
 * slot fails the build instead of silently dropping a glyph.
 *
 * ### Deliberately absent
 *
 * `icon_key_space` and `.icon.for-space-branding`: the space bar here draws its
 * language name, not a glyph, so there is no slot to put one in.
 * `icon_g_icon` is Gboard's own logo on its search key, which this keyboard has
 * no counterpart for.
 */
object GboardIcons {

    /**
     * Class name → slot id, most specific first.
     *
     * Two classes may name the same slot (`icon_key_ime_action_enter` and
     * `icon_key_enter` both being the return key), and the first one a theme
     * actually sets wins — which is why the order is part of the table rather
     * than incidental to it.
     */
    val SLOTS: List<Pair<String, String>> = listOf(
        "icon_key_del" to "key.backspace",
        "icon_key_shift_off" to "key.shift",
        "icon_key_shift_on" to "key.shift_on",
        "icon_key_shift_locked" to "key.shift_lock",
        "icon_key_ime_action_enter" to "key.enter",
        "icon_key_enter" to "key.enter",
        "icon_key_ime_action_search" to "key.enter_search",
        "icon_key_search" to "key.enter_search",
        "icon_key_ime_action_send" to "key.enter_send",
        "icon_key_send" to "key.enter_send",
        "icon_key_ime_action_go" to "key.enter_go",
        "icon_key_ime_action_next" to "key.enter_next",
        "icon_key_ime_action_previous" to "key.enter_previous",
        "icon_key_ime_action_done" to "key.enter_done",
        "icon_key_emoji" to "key.emoji",
        "icon_key_main_category_smiley" to "key.emoji",
        "icon_key_globe" to "key.globe",
        "icon_key_language_switch" to "key.globe",
        "icon_key_mic" to "tool.voice",
        "icon_key_voice" to "tool.voice",
        "icon_key_clipboard" to "tool.clipboard",
        "icon_key_settings" to "tool.settings",
    )

    /** Every class above, for telling a glyph rule that landed from one that did not. */
    val CLASSES: Set<String> = SLOTS.mapTo(LinkedHashSet()) { it.first }

    /**
     * A class name with Gboard's day/night suffix taken off.
     *
     * A theme that ships two emoji glyphs writes
     * `.icon_key_main_category_smiley_dark_theme` and its light twin. Both are
     * the same icon as far as this app is concerned — a theme is one of day or
     * night here — so both fold onto the base name and whichever the theme
     * states is used.
     */
    fun normalize(className: String): String = className
        .removeSuffix("_dark_theme")
        .removeSuffix("_light_theme")

    /**
     * Largest glyph worth carrying into a theme.
     *
     * Mirrors `RasterIcons.MAX_SOURCE_BYTES`, which this module cannot see for
     * the reason above; the two are pinned together by `GboardIconSlotsTest`.
     * Anything past it would be read here and then refused by the icon
     * pipeline, which is worse than not reading it — the theme would claim a
     * glyph it does not draw.
     */
    const val MAX_ICON_BYTES = 512 * 1024
}
