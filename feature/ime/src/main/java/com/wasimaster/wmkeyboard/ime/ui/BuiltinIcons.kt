package com.wasimaster.wmkeyboard.ime.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.automirrored.outlined.Backspace
import androidx.compose.material.icons.automirrored.outlined.FactCheck
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.KeyboardReturn
import androidx.compose.material.icons.automirrored.outlined.KeyboardTab
import androidx.compose.material.icons.automirrored.outlined.LastPage
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.automirrored.outlined.Redo
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.automirrored.outlined.StickyNote2
import androidx.compose.material.icons.automirrored.outlined.TextSnippet
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.automirrored.outlined.VolumeOff
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.Accessibility
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Air
import androidx.compose.material.icons.outlined.AlternateEmail
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.AspectRatio
import androidx.compose.material.icons.outlined.AudioFile
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.BatterySaver
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Calculate
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.Cameraswitch
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Compress
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ContentCut
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Crop
import androidx.compose.material.icons.outlined.CurrencyExchange
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Dialpad
import androidx.compose.material.icons.outlined.DirectionsCar
import androidx.compose.material.icons.outlined.DocumentScanner
import androidx.compose.material.icons.outlined.Done
import androidx.compose.material.icons.outlined.DragHandle
import androidx.compose.material.icons.outlined.Draw
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.EmojiEmotions
import androidx.compose.material.icons.outlined.EmojiFlags
import androidx.compose.material.icons.outlined.EmojiNature
import androidx.compose.material.icons.outlined.EmojiObjects
import androidx.compose.material.icons.outlined.EmojiPeople
import androidx.compose.material.icons.outlined.EmojiSymbols
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Fastfood
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.FirstPage
import androidx.compose.material.icons.outlined.FitnessCenter
import androidx.compose.material.icons.outlined.FlashAuto
import androidx.compose.material.icons.outlined.FlashOff
import androidx.compose.material.icons.outlined.FlashOn
import androidx.compose.material.icons.outlined.FlashlightOn
import androidx.compose.material.icons.outlined.Flight
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FormatShapes
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.material.icons.outlined.Functions
import androidx.compose.material.icons.outlined.GifBox
import androidx.compose.material.icons.outlined.GridOn
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material.icons.outlined.Highlight
import androidx.compose.material.icons.outlined.HighlightAlt
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.ImageSearch
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.KeyboardDoubleArrowDown
import androidx.compose.material.icons.outlined.KeyboardDoubleArrowLeft
import androidx.compose.material.icons.outlined.KeyboardDoubleArrowRight
import androidx.compose.material.icons.outlined.KeyboardDoubleArrowUp
import androidx.compose.material.icons.outlined.KeyboardHide
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.MailOutline
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Numbers
import androidx.compose.material.icons.outlined.OpenWith
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Password
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.Payments
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material.icons.outlined.Pets
import androidx.compose.material.icons.outlined.Phone
import androidx.compose.material.icons.outlined.Phonelink
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.PictureInPictureAlt
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.QrCode2
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.School
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.ShoppingCart
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material.icons.outlined.SkipPrevious
import androidx.compose.material.icons.outlined.SmartButton
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material.icons.outlined.SpaceBar
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Spellcheck
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.material.icons.outlined.SportsSoccer
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material.icons.outlined.Straighten
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material.icons.outlined.TextFormat
import androidx.compose.material.icons.outlined.Thermostat
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.TimerOff
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material.icons.outlined.TravelExplore
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Umbrella
import androidx.compose.material.icons.outlined.VerticalSplit
import androidx.compose.material.icons.outlined.Vibration
import androidx.compose.material.icons.outlined.VideoFile
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material.icons.outlined.ViewAgenda
import androidx.compose.material.icons.outlined.ViewHeadline
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.outlined.VpnKey
import androidx.compose.material.icons.outlined.WaterDrop
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.material.icons.outlined.WbTwilight
import androidx.compose.material.icons.outlined.Widgets
import androidx.compose.material.icons.outlined.WorkOutline
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * The icons the icon picker offers, keyed by a stable name.
 *
 * These are the Material Symbols the app already bundles — the picker
 * deliberately does not expose the whole `material-icons-extended` set, which
 * is thousands of glyphs the R8 shrinker would then have to keep. Everything
 * here is referenced somewhere in the app already, so offering it costs no
 * extra bytes.
 *
 * The key is the Material symbol name and is what a per-slot override stores
 * (`b:<name>`), so renaming one drops that override back to the default —
 * treat the keys as the wire format they are.
 */
object BuiltinIcons {

    val catalog: Map<String, ImageVector> = linkedMapOf(
        "Accessibility" to Icons.Outlined.Accessibility,
        "Add" to Icons.Outlined.Add,
        "Air" to Icons.Outlined.Air,
        "AlternateEmail" to Icons.Outlined.AlternateEmail,
        "Apps" to Icons.Outlined.Apps,
        "ArrowBack" to Icons.AutoMirrored.Outlined.ArrowBack,
        "ArrowDownward" to Icons.Outlined.ArrowDownward,
        "ArrowDropDown" to Icons.Outlined.ArrowDropDown,
        "ArrowForward" to Icons.AutoMirrored.Outlined.ArrowForward,
        "ArrowUpward" to Icons.Outlined.ArrowUpward,
        "Article" to Icons.AutoMirrored.Outlined.Article,
        "AspectRatio" to Icons.Outlined.AspectRatio,
        "AudioFile" to Icons.Outlined.AudioFile,
        "AutoAwesome" to Icons.Outlined.AutoAwesome,
        "AutoStories" to Icons.Outlined.AutoStories,
        "Backspace" to Icons.AutoMirrored.Outlined.Backspace,
        "BarChart" to Icons.Outlined.BarChart,
        "BatterySaver" to Icons.Outlined.BatterySaver,
        "Bolt" to Icons.Outlined.Bolt,
        "Calculate" to Icons.Outlined.Calculate,
        "CalendarMonth" to Icons.Outlined.CalendarMonth,
        "Call" to Icons.Outlined.Call,
        "Cameraswitch" to Icons.Outlined.Cameraswitch,
        "ChatBubbleOutline" to Icons.Outlined.ChatBubbleOutline,
        "Check" to Icons.Outlined.Check,
        "CheckCircle" to Icons.Outlined.CheckCircle,
        "ChevronLeft" to Icons.Outlined.ChevronLeft,
        "ChevronRight" to Icons.Outlined.ChevronRight,
        "Close" to Icons.Outlined.Close,
        "Cloud" to Icons.Outlined.Cloud,
        "Code" to Icons.Outlined.Code,
        "Compress" to Icons.Outlined.Compress,
        "ContentCopy" to Icons.Outlined.ContentCopy,
        "ContentCut" to Icons.Outlined.ContentCut,
        "ContentPaste" to Icons.Outlined.ContentPaste,
        "Crop" to Icons.Outlined.Crop,
        "CurrencyExchange" to Icons.Outlined.CurrencyExchange,
        "DarkMode" to Icons.Outlined.DarkMode,
        "Delete" to Icons.Outlined.Delete,
        "DeleteSweep" to Icons.Outlined.DeleteSweep,
        "Description" to Icons.Outlined.Description,
        "Dialpad" to Icons.Outlined.Dialpad,
        "DirectionsCar" to Icons.Outlined.DirectionsCar,
        "DocumentScanner" to Icons.Outlined.DocumentScanner,
        "Done" to Icons.Outlined.Done,
        "DragHandle" to Icons.Outlined.DragHandle,
        "Draw" to Icons.Outlined.Draw,
        "Edit" to Icons.Outlined.Edit,
        "EditNote" to Icons.Outlined.EditNote,
        "EmojiEmotions" to Icons.Outlined.EmojiEmotions,
        "EmojiFlags" to Icons.Outlined.EmojiFlags,
        "EmojiNature" to Icons.Outlined.EmojiNature,
        "EmojiObjects" to Icons.Outlined.EmojiObjects,
        "EmojiPeople" to Icons.Outlined.EmojiPeople,
        "EmojiSymbols" to Icons.Outlined.EmojiSymbols,
        "Explore" to Icons.Outlined.Explore,
        "Extension" to Icons.Outlined.Extension,
        "FactCheck" to Icons.AutoMirrored.Outlined.FactCheck,
        "Fastfood" to Icons.Outlined.Fastfood,
        "FavoriteBorder" to Icons.Outlined.FavoriteBorder,
        "FileDownload" to Icons.Outlined.FileDownload,
        "FileOpen" to Icons.Outlined.FileOpen,
        "FileUpload" to Icons.Outlined.FileUpload,
        "FirstPage" to Icons.Outlined.FirstPage,
        "FitnessCenter" to Icons.Outlined.FitnessCenter,
        "FlashAuto" to Icons.Outlined.FlashAuto,
        "FlashlightOn" to Icons.Outlined.FlashlightOn,
        "FlashOff" to Icons.Outlined.FlashOff,
        "FlashOn" to Icons.Outlined.FlashOn,
        "Flight" to Icons.Outlined.Flight,
        "Folder" to Icons.Outlined.Folder,
        "FormatShapes" to Icons.Outlined.FormatShapes,
        "Forum" to Icons.Outlined.Forum,
        "Fullscreen" to Icons.Outlined.Fullscreen,
        "Functions" to Icons.Outlined.Functions,
        "GifBox" to Icons.Outlined.GifBox,
        "GridOn" to Icons.Outlined.GridOn,
        "GridView" to Icons.Outlined.GridView,
        "Group" to Icons.Outlined.Group,
        "HelpOutline" to Icons.AutoMirrored.Outlined.HelpOutline,
        "Highlight" to Icons.Outlined.Highlight,
        "HighlightAlt" to Icons.Outlined.HighlightAlt,
        "Home" to Icons.Outlined.Home,
        "Image" to Icons.Outlined.Image,
        "ImageSearch" to Icons.Outlined.ImageSearch,
        "Info" to Icons.Outlined.Info,
        "InsertDriveFile" to Icons.AutoMirrored.Outlined.InsertDriveFile,
        "Keyboard" to Icons.Outlined.Keyboard,
        "KeyboardArrowDown" to Icons.Outlined.KeyboardArrowDown,
        "KeyboardArrowLeft" to Icons.AutoMirrored.Outlined.KeyboardArrowLeft,
        "KeyboardArrowRight" to Icons.AutoMirrored.Outlined.KeyboardArrowRight,
        "KeyboardArrowUp" to Icons.Outlined.KeyboardArrowUp,
        "KeyboardDoubleArrowDown" to Icons.Outlined.KeyboardDoubleArrowDown,
        "KeyboardDoubleArrowLeft" to Icons.Outlined.KeyboardDoubleArrowLeft,
        "KeyboardDoubleArrowRight" to Icons.Outlined.KeyboardDoubleArrowRight,
        "KeyboardDoubleArrowUp" to Icons.Outlined.KeyboardDoubleArrowUp,
        "KeyboardHide" to Icons.Outlined.KeyboardHide,
        "KeyboardReturn" to Icons.AutoMirrored.Outlined.KeyboardReturn,
        "KeyboardTab" to Icons.AutoMirrored.Outlined.KeyboardTab,
        "Language" to Icons.Outlined.Language,
        "LastPage" to Icons.AutoMirrored.Outlined.LastPage,
        "LightMode" to Icons.Outlined.LightMode,
        "Link" to Icons.Outlined.Link,
        "Lock" to Icons.Outlined.Lock,
        "MailOutline" to Icons.Outlined.MailOutline,
        "Menu" to Icons.Outlined.Menu,
        "MenuBook" to Icons.AutoMirrored.Outlined.MenuBook,
        "Mic" to Icons.Outlined.Mic,
        "MoreHoriz" to Icons.Outlined.MoreHoriz,
        "MoreVert" to Icons.Outlined.MoreVert,
        "MusicNote" to Icons.Outlined.MusicNote,
        "Notifications" to Icons.Outlined.Notifications,
        "Numbers" to Icons.Outlined.Numbers,
        "OpenInNew" to Icons.AutoMirrored.Outlined.OpenInNew,
        "OpenWith" to Icons.Outlined.OpenWith,
        "Palette" to Icons.Outlined.Palette,
        "Password" to Icons.Outlined.Password,
        "Pause" to Icons.Outlined.Pause,
        "Payments" to Icons.Outlined.Payments,
        "PersonOutline" to Icons.Outlined.PersonOutline,
        "Pets" to Icons.Outlined.Pets,
        "Phone" to Icons.Outlined.Phone,
        "Phonelink" to Icons.Outlined.Phonelink,
        "PhotoCamera" to Icons.Outlined.PhotoCamera,
        "PictureAsPdf" to Icons.Outlined.PictureAsPdf,
        "PictureInPictureAlt" to Icons.Outlined.PictureInPictureAlt,
        "PlayArrow" to Icons.Outlined.PlayArrow,
        "Public" to Icons.Outlined.Public,
        "PushPin" to Icons.Outlined.PushPin,
        "QrCode2" to Icons.Outlined.QrCode2,
        "QrCodeScanner" to Icons.Outlined.QrCodeScanner,
        "Redo" to Icons.AutoMirrored.Outlined.Redo,
        "Refresh" to Icons.Outlined.Refresh,
        "Remove" to Icons.Outlined.Remove,
        "Save" to Icons.Outlined.Save,
        "Schedule" to Icons.Outlined.Schedule,
        "School" to Icons.Outlined.School,
        "Science" to Icons.Outlined.Science,
        "Search" to Icons.Outlined.Search,
        "Security" to Icons.Outlined.Security,
        "SelectAll" to Icons.Outlined.SelectAll,
        "Send" to Icons.AutoMirrored.Outlined.Send,
        "Settings" to Icons.Outlined.Settings,
        "Share" to Icons.Outlined.Share,
        "Shield" to Icons.Outlined.Shield,
        "ShoppingCart" to Icons.Outlined.ShoppingCart,
        "SkipNext" to Icons.Outlined.SkipNext,
        "SkipPrevious" to Icons.Outlined.SkipPrevious,
        "SmartButton" to Icons.Outlined.SmartButton,
        "Smartphone" to Icons.Outlined.Smartphone,
        "SpaceBar" to Icons.Outlined.SpaceBar,
        "Speed" to Icons.Outlined.Speed,
        "Spellcheck" to Icons.Outlined.Spellcheck,
        "SportsEsports" to Icons.Outlined.SportsEsports,
        "SportsSoccer" to Icons.Outlined.SportsSoccer,
        "Star" to Icons.Outlined.Star,
        "StarBorder" to Icons.Outlined.StarBorder,
        "StarOutline" to Icons.Outlined.StarOutline,
        "StickyNote2" to Icons.AutoMirrored.Outlined.StickyNote2,
        "Straighten" to Icons.Outlined.Straighten,
        "SwapHoriz" to Icons.Outlined.SwapHoriz,
        "SwapVert" to Icons.Outlined.SwapVert,
        "Terminal" to Icons.Outlined.Terminal,
        "TextFields" to Icons.Outlined.TextFields,
        "TextFormat" to Icons.Outlined.TextFormat,
        "TextSnippet" to Icons.AutoMirrored.Outlined.TextSnippet,
        "Thermostat" to Icons.Outlined.Thermostat,
        "Timer" to Icons.Outlined.Timer,
        "TimerOff" to Icons.Outlined.TimerOff,
        "TouchApp" to Icons.Outlined.TouchApp,
        "Translate" to Icons.Outlined.Translate,
        "TravelExplore" to Icons.Outlined.TravelExplore,
        "Tune" to Icons.Outlined.Tune,
        "Umbrella" to Icons.Outlined.Umbrella,
        "Undo" to Icons.AutoMirrored.Outlined.Undo,
        "VerticalSplit" to Icons.Outlined.VerticalSplit,
        "Vibration" to Icons.Outlined.Vibration,
        "Videocam" to Icons.Outlined.Videocam,
        "VideoFile" to Icons.Outlined.VideoFile,
        "ViewAgenda" to Icons.Outlined.ViewAgenda,
        "ViewHeadline" to Icons.Outlined.ViewHeadline,
        "VisibilityOff" to Icons.Outlined.VisibilityOff,
        "VolumeOff" to Icons.AutoMirrored.Outlined.VolumeOff,
        "VolumeUp" to Icons.AutoMirrored.Outlined.VolumeUp,
        "VpnKey" to Icons.Outlined.VpnKey,
        "WaterDrop" to Icons.Outlined.WaterDrop,
        "WbSunny" to Icons.Outlined.WbSunny,
        "WbTwilight" to Icons.Outlined.WbTwilight,
        "Widgets" to Icons.Outlined.Widgets,
        "WorkOutline" to Icons.Outlined.WorkOutline,
    )

    /** Names in picker order. */
    val names: List<String> = catalog.keys.toList()

    /** The vector for [name], or null when the name is blank or unknown. */
    fun byName(name: String?): ImageVector? = name?.takeIf { it.isNotBlank() }?.let { catalog[it] }

    /**
     * "EmojiEmotions" → "Emoji emotions": the picker's search text and tooltip.
     * Derived rather than stored so the catalog stays one line per icon.
     */
    fun label(name: String): String {
        if (name.isEmpty()) return name
        val spaced = buildString {
            name.forEachIndexed { index, c ->
                if (index > 0 && c.isUpperCase() && !name[index - 1].isUpperCase()) append(' ')
                append(c)
            }
        }
        return spaced.first().uppercase() + spaced.drop(1).lowercase()
    }
}
