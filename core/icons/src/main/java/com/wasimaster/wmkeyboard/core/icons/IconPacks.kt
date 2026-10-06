package com.wasimaster.wmkeyboard.core.icons

import android.content.Context
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import kotlinx.serialization.Serializable

/**
 * An installed icon pack: metadata plus the slots it replaces.
 *
 * [slots] holds slot ids only, never file names. The file backing a slot is
 * always `<slotId>.svg` inside the pack's directory — derived, never taken
 * from a manifest — so a hostile pack has no way to name a path at all.
 *
 * An entry may also be a variant key, `<slotId>@<variant>` (see
 * [IconSlots.VARIANT_SEPARATOR]): an alternative look for that slot, which the
 * user can pick per slot. Its file is derived the same way, and
 * [IconSlots.isKnownKey] vets it before it ever gets that far.
 */
@Serializable
data class IconPack(
    val id: String,
    val name: String,
    val author: String = "",
    val description: String = "",
    /** The pack author's own version string; shown, never parsed. */
    val version: String = "",
    val slots: List<String> = emptyList(),
    val createdAt: Long = 0L,
) {
    /** True for the built-as-you-go pack that per-slot SVG imports land in. */
    val isMine: Boolean get() = id == IconPackStore.MINE_ID
}

/**
 * One line of user-facing text this module produces, as a resource plus the
 * values that fill its placeholders.
 *
 * Nothing here holds a `Context`. [IconPackFile.import] runs off the main
 * thread and unit tests drive it with no Android around it, so a line put into
 * words that early would keep the language the device had when the file was
 * read. The screen that shows the line calls [resolve], and the words follow
 * the language the user reads them in.
 *
 * Set [stringRes] for a plain line, or [pluralsRes] together with [quantity]
 * when the wording depends on a count. [args] fills the placeholders in order,
 * and for a plural line the count is normally its first entry as well. Two
 * arguments is the whole budget: a line that needs more is a line that is too
 * long to read.
 */
data class IconText(
    @get:StringRes val stringRes: Int = 0,
    @get:PluralsRes val pluralsRes: Int = 0,
    val quantity: Int = 0,
    val args: List<Any> = emptyList(),
) {

    /**
     * The finished line, in the language [context] is configured for.
     *
     * The arguments go in one by one rather than spread out of [args]: a spread
     * copies the array on every call, and the build reports that as a
     * performance finding.
     */
    fun resolve(context: Context): String {
        val a = args
        if (pluralsRes != 0) {
            return when (a.size) {
                0 -> context.resources.getQuantityString(pluralsRes, quantity)
                1 -> context.resources.getQuantityString(pluralsRes, quantity, a[0])
                else -> context.resources.getQuantityString(pluralsRes, quantity, a[0], a[1])
            }
        }
        return when (a.size) {
            0 -> context.getString(stringRes)
            1 -> context.getString(stringRes, a[0])
            else -> context.getString(stringRes, a[0], a[1])
        }
    }
}

/** Why an import ended the way it did, so the caller can say something useful. */
sealed interface IconImportResult {
    /**
     * [repairs] describes what was dropped: an unknown slot id, or an SVG that
     * would not parse. The confirmation dialog can then be honest about a pack
     * that only partly survived. Each line arrives unresolved, so the dialog
     * calls [IconText.resolve] on it.
     */
    data class Imported(val pack: IconPack, val repairs: List<IconText>) : IconImportResult

    /** A ZIP, but not ours: no manifest, or the wrong format tag. */
    data object NotAnIconPack : IconImportResult

    data object TooManyPacks : IconImportResult

    /** Unreadable, or nothing usable inside. */
    data object Failed : IconImportResult
}
