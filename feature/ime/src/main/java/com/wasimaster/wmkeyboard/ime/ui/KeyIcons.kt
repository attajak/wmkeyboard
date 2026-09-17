package com.wasimaster.wmkeyboard.ime.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.automirrored.outlined.Backspace
import androidx.compose.material.icons.automirrored.outlined.KeyboardReturn
import androidx.compose.material.icons.automirrored.outlined.KeyboardTab
import androidx.compose.material.icons.automirrored.outlined.Redo
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AlternateEmail
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ContentCut
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.EmojiEmotions
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Highlight
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.KeyboardHide
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Numbers
import androidx.compose.material.icons.outlined.Phone
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SpaceBar
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Named-icon registry that lets any [com.wasimaster.wmkeyboard.core.layout.Key]
 * draw a vector glyph — as the key's main label ([Key.icon]) or as its corner
 * hint ([Key.iconHint]) — instead of a character.
 *
 * The [Key] type is a plain `@Serializable` value with no Compose dependency,
 * so it stores the icon as a *name*; this object turns that name into the
 * actual [ImageVector] at render time. Keeping the mapping here (in the ime/ui
 * layer) is what keeps `core/layout` free of Compose types.
 *
 * Names are matched case-insensitively; a handful of [aliases] cover the common
 * synonyms. An unknown name resolves to null, and the renderer falls back to
 * drawing the text label — so a typo degrades gracefully rather than crashing.
 */
object KeyIcons {

    /**
     * Canonical name → vector. The iteration order is the order an icon picker
     * would present them in, so the most generally useful glyphs lead.
     */
    val catalog: Map<String, ImageVector> by lazy {
        linkedMapOf(
        // Editing / navigation.
        "backspace" to Icons.AutoMirrored.Outlined.Backspace,
        "forward_delete" to KeyboardIcons.ForwardDelete,
        "enter" to Icons.AutoMirrored.Outlined.KeyboardReturn,
        "tab" to Icons.AutoMirrored.Outlined.KeyboardTab,
        "space" to Icons.Outlined.SpaceBar,
        "shift" to KeyboardIcons.Shift,
        "shift_on" to KeyboardIcons.ShiftFilled,
        "shift_lock" to KeyboardIcons.ShiftLock,
        "undo" to Icons.AutoMirrored.Outlined.Undo,
        "redo" to Icons.AutoMirrored.Outlined.Redo,
        // Arrows.
        "arrow_up" to Icons.Outlined.ArrowUpward,
        "arrow_down" to Icons.Outlined.ArrowDownward,
        "arrow_left" to Icons.AutoMirrored.Outlined.ArrowBack,
        "arrow_right" to Icons.AutoMirrored.Outlined.ArrowForward,
        "chevron_up" to Icons.Outlined.KeyboardArrowUp,
        "chevron_down" to Icons.Outlined.KeyboardArrowDown,
        "chevron_left" to Icons.Outlined.ChevronLeft,
        "chevron_right" to Icons.Outlined.ChevronRight,
        // Actions.
        "search" to Icons.Outlined.Search,
        "send" to Icons.AutoMirrored.Outlined.Send,
        "check" to Icons.Outlined.Check,
        "close" to Icons.Outlined.Close,
        "add" to Icons.Outlined.Add,
        "remove" to Icons.Outlined.Remove,
        "copy" to Icons.Outlined.ContentCopy,
        "cut" to Icons.Outlined.ContentCut,
        "paste" to Icons.Outlined.ContentPaste,
        // Its bundled name, Delete, is taken: `delete` has always meant backspace.
        "trash" to Icons.Outlined.Delete,
        // Content / tools.
        "emoji" to Icons.Outlined.EmojiEmotions,
        "language" to Icons.Outlined.Language,
        "translate" to Icons.Outlined.Translate,
        "mic" to Icons.Outlined.Mic,
        "image" to Icons.Outlined.Image,
        "link" to Icons.Outlined.Link,
        "at" to Icons.Outlined.AlternateEmail,
        "phone" to Icons.Outlined.Phone,
        "numbers" to Icons.Outlined.Numbers,
        "calendar" to Icons.Outlined.CalendarMonth,
        "star" to Icons.Outlined.Star,
        "heart" to Icons.Outlined.FavoriteBorder,
        // Keyboard / app chrome.
        "keyboard" to Icons.Outlined.Keyboard,
        "keyboard_hide" to Icons.Outlined.KeyboardHide,
        "settings" to Icons.Outlined.Settings,
        "menu" to Icons.Outlined.Menu,
        "more" to Icons.Outlined.MoreHoriz,
        "light_mode" to Icons.Outlined.LightMode,
        "dark_mode" to Icons.Outlined.DarkMode,
        "highlight" to Icons.Outlined.Highlight,
        "incognito" to KeyboardIcons.Incognito,
        )
    }

    /** Common synonyms mapped onto their canonical [catalog] entry. */
    private val aliases: Map<String, String> by lazy {
        mapOf(
        "delete" to "backspace",
        "return" to "enter",
        "caps" to "shift_lock",
        "capslock" to "shift_lock",
        "caps_lock" to "shift_lock",
        "shift_filled" to "shift_on",
        "up" to "arrow_up",
        "down" to "arrow_down",
        "left" to "arrow_left",
        "right" to "arrow_right",
        "done" to "check",
        "clear" to "close",
        "plus" to "add",
        "minus" to "remove",
        "clipboard" to "paste",
        "globe" to "language",
        "voice" to "mic",
        "photo" to "image",
        "picture" to "image",
        "email" to "at",
        "mail" to "at",
        "favorite" to "heart",
        "hide" to "keyboard_hide",
        "gear" to "settings",
        "bulb" to "highlight",
        )
    }

    /**
     * Canonical name → the [aliases] that resolve to it, so the icon picker can
     * match a search against what a drawing is usually called and not only
     * against the short name a layout file stores: "clipboard" finds `paste`,
     * "globe" finds `language` (issue #223).
     */
    private val aliasesByCanonical: Map<String, List<String>> by lazy {
        aliases.entries.groupBy({ it.value }, { it.key })
    }

    /** The alias names that draw [name], for the picker's search. */
    fun aliasesFor(name: String): List<String> =
        aliasesByCanonical[name.trim().lowercase()].orEmpty()

    /**
     * The app's own bundled glyphs ([BuiltinIcons]) by lowercased name, so a key
     * can wear any icon the settings already draw — `EmojiEmotions`, `Translate`
     * — at no size cost (issue #187). Looked up after [catalog] and [aliases],
     * which keep their short names.
     */
    private val builtinByLowerName: Map<String, ImageVector> by lazy {
        BuiltinIcons.catalog.mapKeys { it.key.lowercase() }
    }

    /**
     * Every icon name a layout can use: canonical names, aliases, then the
     * bundled app icons that are not already one of those under the same name.
     */
    val names: List<String> by lazy {
        catalog.keys.toList() + aliases.keys +
            BuiltinIcons.names.filter { it.lowercase() !in catalog && it.lowercase() !in aliases }
    }

    /**
     * What the layout editor's icon picker offers, in order: the key glyphs under
     * their short names first, then each bundled app icon that is not the same
     * drawing under another name. One entry per drawing, so the grid never shows
     * the same glyph twice.
     *
     * A bundled name that is also an alias is left out: `Delete` already means
     * backspace and `Done` already means check to every layout written so far,
     * so offering them would store a name that draws something else. The trash
     * glyph stays reachable as `trash`.
     */
    val pickerEntries: List<Pair<String, ImageVector>> by lazy {
        val drawn = catalog.values.toHashSet()
        catalog.toList() + BuiltinIcons.catalog.filter { (name, vector) ->
            val key = name.lowercase()
            vector !in drawn && key !in catalog && key !in aliases
        }.toList()
    }

    /**
     * The vector for [name], or null if the name is blank or unrecognised (the
     * caller then draws the text label). Case- and whitespace-insensitive.
     */
    fun byName(name: String?): ImageVector? {
        // Before [catalog] is touched, which is the point of the check being
        // here rather than at the call site: every key on the board asks this
        // on every grid build, almost always with no icon name at all, and
        // building the catalogue means constructing seventy ImageVectors —
        // path data parsed and node trees built — on whichever thread got
        // here first. That was the keyboard's first frame. Lazy plus this
        // early return means a board of ordinary keys never builds it.
        if (name.isNullOrBlank()) return null
        val key = name.trim().lowercase()
        catalog[key]?.let { return it }
        aliases[key]?.let { alias -> return catalog[alias] }
        return builtinByLowerName[key]
    }
}
