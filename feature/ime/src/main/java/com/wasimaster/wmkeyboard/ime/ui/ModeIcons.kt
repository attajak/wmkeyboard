package com.wasimaster.wmkeyboard.ime.ui

import androidx.compose.material.icons.Icons
import com.wasimaster.wmkeyboard.core.icons.symbols.automirrored.outlined.MenuBook
import com.wasimaster.wmkeyboard.core.icons.symbols.automirrored.outlined.Article
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Bolt
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.CalendarMonth
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Call
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.ChatBubbleOutline
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Cloud
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Code
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.EditNote
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.FavoriteBorder
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.FitnessCenter
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Flight
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Forum
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Group
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Home
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Language
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Lock
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.MailOutline
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.MusicNote
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Palette
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Payments
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.PersonOutline
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.PhotoCamera
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.School
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Science
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Search
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Shield
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.ShoppingCart
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.SportsEsports
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.StarOutline
import com.wasimaster.wmkeyboard.core.icons.symbols.automirrored.outlined.StickyNote2
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Terminal
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Tune
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Videocam
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.VpnKey
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.WorkOutline
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * The icons a keyboard mode can wear, as stable ids. `KeyboardMode.icon`
 * stores the id, never the vector, so the stored modes survive an icon being
 * swapped for a better-looking one — and an id that no longer exists falls
 * back to [DefaultModeIcon] instead of crashing.
 *
 * Ordered as the picker shows them: the ones that fit a mode ("chat",
 * "writing", "passwords") first, then the general-purpose ones.
 */
object ModeIcons {
    val catalog: List<Pair<String, ImageVector>> = listOf(
        "tune" to Icons.Outlined.Tune,
        "chat" to Icons.Outlined.ChatBubbleOutline,
        "forum" to Icons.Outlined.Forum,
        "write" to Icons.Outlined.EditNote,
        "note" to Icons.AutoMirrored.Outlined.StickyNote2,
        "article" to Icons.AutoMirrored.Outlined.Article,
        "book" to Icons.AutoMirrored.Outlined.MenuBook,
        "mail" to Icons.Outlined.MailOutline,
        "lock" to Icons.Outlined.Lock,
        "shield" to Icons.Outlined.Shield,
        "key" to Icons.Outlined.VpnKey,
        "web" to Icons.Outlined.Language,
        "search" to Icons.Outlined.Search,
        "code" to Icons.Outlined.Code,
        "terminal" to Icons.Outlined.Terminal,
        "work" to Icons.Outlined.WorkOutline,
        "school" to Icons.Outlined.School,
        "science" to Icons.Outlined.Science,
        "home" to Icons.Outlined.Home,
        "person" to Icons.Outlined.PersonOutline,
        "group" to Icons.Outlined.Group,
        "call" to Icons.Outlined.Call,
        "shopping" to Icons.Outlined.ShoppingCart,
        "money" to Icons.Outlined.Payments,
        "calendar" to Icons.Outlined.CalendarMonth,
        "game" to Icons.Outlined.SportsEsports,
        "music" to Icons.Outlined.MusicNote,
        "video" to Icons.Outlined.Videocam,
        "camera" to Icons.Outlined.PhotoCamera,
        "palette" to Icons.Outlined.Palette,
        "travel" to Icons.Outlined.Flight,
        "fitness" to Icons.Outlined.FitnessCenter,
        "cloud" to Icons.Outlined.Cloud,
        "bolt" to Icons.Outlined.Bolt,
        "star" to Icons.Outlined.StarOutline,
        "favorite" to Icons.Outlined.FavoriteBorder,
    )

    private val byId: Map<String, ImageVector> = catalog.toMap()

    /** What a mode with no icon of its own shows. */
    val DefaultModeIcon: ImageVector = Icons.Outlined.Tune

    /** The vector for [id], or [DefaultModeIcon] for null and unknown ids. */
    fun icon(id: String?): ImageVector = id?.let { byId[it] } ?: DefaultModeIcon

    /**
     * The vector for [id], or null when there is none.
     *
     * For the callers whose own fallback is not a mode's: a snippet folder with
     * no icon of its own draws a folder, not [DefaultModeIcon].
     */
    fun iconOrNull(id: String?): ImageVector? = id?.let { byId[it] }
}
