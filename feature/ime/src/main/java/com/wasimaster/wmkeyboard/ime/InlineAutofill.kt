package com.wasimaster.wmkeyboard.ime

import android.content.Context
import android.os.Build
import android.os.Bundle
import android.util.Size
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InlineSuggestion
import android.view.inputmethod.InlineSuggestionInfo
import android.view.inputmethod.InlineSuggestionsRequest
import android.widget.inline.InlinePresentationSpec
import androidx.annotation.RequiresApi
import androidx.autofill.inline.UiVersions
import androidx.autofill.inline.v1.InlineSuggestionUi

/**
 * Inline suggestions: the chips another process draws inside the suggestion
 * strip. Two very different things arrive down this one platform API.
 *
 * A password manager sends *autofill* chips ("wasi@example.com", "Saved
 * password") when a login field is focused. Android System Intelligence sends
 * *platform* chips — smart replies to the message on screen, a clipboard or
 * sticker offer — when it has something to say about the conversation. The
 * response mixes them; [InlineSuggestionInfo.getSource] is what tells them
 * apart, and the keyboard treats the two as separate lanes: separate toggles,
 * separate chip budgets, and separate room on the strip.
 *
 * The keyboard does not build any of these views and never sees their
 * contents. It publishes a *presentation spec* — how large a chip may be and
 * which colours to use — the other process renders each suggestion remotely,
 * and what comes back is an opaque view only that process can populate. That
 * is the whole security model of the API: credentials are never handed to the
 * IME, which is exactly what you want from a keyboard.
 *
 * Android 11 (API 30) and up. Below that, the platform never calls any of
 * this and the strip behaves as it always did.
 */
object InlineAutofill {

    /** The API exists from Android 11 on. */
    val supported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    /** Chips are sized to the strip, so they line up with word suggestions. */
    private const val MIN_CHIP_WIDTH_PX = 100

    /**
     * The widest a single chip may be rendered, as a fraction of the screen.
     *
     * The cap is the whole reason more than one chip is ever visible. The
     * presentation spec's max size is what the sending process lays its chip
     * out against, so handing it the full screen width means the first saved
     * login fills the strip and the other five are a scroll away — the budget
     * of six says a manager with several logins should be able to *show* them
     * (#250).
     *
     * Measured against the screen, not the row, because the request is built
     * in onStartInput — before there is any keyboard UI to ask. The row is the
     * narrower of the two (the chevron, the emoji key and the dismiss cross
     * come out of it first), so half the screen lands at roughly two thirds of
     * the row: a long address ellipsized, with a clear slice of the next chip
     * beside it saying there are more.
     */
    private const val MAX_CHIP_WIDTH_FRACTION = 0.5f

    /**
     * Credential chips take the whole strip while they are up, so they get the
     * wider budget — a manager with several saved logins for a site should be
     * able to show them all.
     */
    const val MAX_AUTOFILL_CHIPS = 6

    /**
     * Smart replies ride *beside* the word candidates rather than replacing
     * them, so they get a smaller budget. Three is what fits next to words
     * without squeezing the strip down to something unusable, and it is what
     * the system's own reply surfaces offer.
     */
    const val MAX_PLATFORM_CHIPS = 3

    /** Chips split by where they came from, each already capped to its budget. */
    data class Lanes(
        val autofill: List<InlineSuggestion>,
        val platform: List<InlineSuggestion>,
    )

    /** The inflated form of [Lanes]: the views the strip actually hosts. */
    data class Chips(
        val autofill: List<View>,
        val platform: List<View>,
    ) {
        val isEmpty: Boolean get() = autofill.isEmpty() && platform.isEmpty()

        companion object {
            val None = Chips(emptyList(), emptyList())
        }
    }

    /**
     * The request handed to the autofill service and to the platform,
     * describing how many chips the strip will take and how big each may be.
     *
     * [stripHeightPx] is the strip's real height so a chip cannot push the
     * keyboard around. The two budgets are added together because the API has
     * one count for the whole response — the split back into lanes happens on
     * the way in, in [split]. A lane whose toggle is off passes 0, so the
     * request never asks for chips the strip would then throw away.
     *
     * Deliberately no colour styling. The keyboard's theme is resolved in
     * Compose and is not reachable from the service, so any colours set here
     * would be a guess — and a wrong guess renders someone's credentials
     * unreadable. The sending app's own chip styling is always legible, which
     * matters more than matching the keys.
     */
    @RequiresApi(Build.VERSION_CODES.R)
    fun request(
        uiExtras: Bundle,
        stripHeightPx: Int,
        maxWidthPx: Int,
        autofillBudget: Int,
        platformBudget: Int,
    ): InlineSuggestionsRequest? {
        val total = autofillBudget + platformBudget
        if (total <= 0) return null

        val style = UiVersions.newStylesBuilder()
            .addStyle(InlineSuggestionUi.newStyleBuilder().build())
            .build()

        val spec = InlinePresentationSpec
            .Builder(
                Size(MIN_CHIP_WIDTH_PX, stripHeightPx),
                Size(chipWidthCapPx(maxWidthPx), stripHeightPx),
            )
            .setStyle(style)
            .build()

        return InlineSuggestionsRequest.Builder(List(total) { spec })
            .setMaxSuggestionCount(total)
            .setExtras(uiExtras)
            .build()
    }

    /**
     * Sorts a response into its two lanes and trims each to its budget.
     *
     * Source is the split that matters: a chip from the autofill service holds
     * a credential and belongs on a strip of its own, a chip from the platform
     * is a reply and belongs beside the words. Type is the tiebreak *within*
     * the platform lane — a plain reply ("On my way!") earns the room ahead of
     * an action chip, which is a shortcut into another app and can wait for
     * the toolbar. Autofill order is left exactly as the manager sent it,
     * because that ranking is the manager's job.
     */
    @RequiresApi(Build.VERSION_CODES.R)
    fun split(
        suggestions: List<InlineSuggestion>,
        autofillBudget: Int,
        platformBudget: Int,
    ): Lanes {
        val autofill = mutableListOf<InlineSuggestion>()
        val platform = mutableListOf<InlineSuggestion>()
        for (suggestion in suggestions) {
            // A malformed suggestion costs the user that chip, not a crash:
            // everything here crosses a process boundary.
            val info = runCatching { suggestion.info }.getOrNull() ?: continue
            if (info.source == InlineSuggestionInfo.SOURCE_PLATFORM) {
                platform += suggestion
            } else {
                autofill += suggestion
            }
        }
        // Stable, so replies keep the platform's own order among themselves.
        platform.sortBy { if (typeOf(it) == InlineSuggestionInfo.TYPE_ACTION) 1 else 0 }
        return Lanes(
            autofill = autofill.take(autofillBudget.coerceAtLeast(0)),
            platform = platform.take(platformBudget.coerceAtLeast(0)),
        )
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun typeOf(suggestion: InlineSuggestion): String? =
        runCatching { suggestion.info.type }.getOrNull()

    /**
     * The per-chip width ceiling on a screen [screenWidthPx] wide, never below
     * [MIN_CHIP_WIDTH_PX] — a spec whose max is under its own min is rejected
     * by the platform, which would cost the user every chip.
     */
    private fun chipWidthCapPx(screenWidthPx: Int): Int =
        (screenWidthPx * MAX_CHIP_WIDTH_FRACTION).toInt().coerceAtLeast(MIN_CHIP_WIDTH_PX)

    /**
     * Inflates both lanes of [lanes] into views, calling [onReady] once with
     * those that succeeded.
     *
     * Inflation is remote and asynchronous — each view is built by the sending
     * process — so the results are gathered and handed over in one go rather
     * than making the strip flicker in chip by chip. One callback covers both
     * lanes for the same reason: a reply landing a frame before a credential
     * chip would otherwise let the words hold the row and then be yanked out
     * from under the user's thumb. A suggestion that fails to inflate is
     * dropped, since an app that cannot draw a chip should cost the user a
     * missing chip and nothing more.
     */
    @RequiresApi(Build.VERSION_CODES.R)
    fun inflateAll(
        context: Context,
        lanes: Lanes,
        stripHeightPx: Int,
        onReady: (Chips) -> Unit,
    ) {
        if (lanes.autofill.isEmpty() && lanes.platform.isEmpty()) {
            onReady(Chips.None)
            return
        }
        val autofillViews = arrayOfNulls<View>(lanes.autofill.size)
        val platformViews = arrayOfNulls<View>(lanes.platform.size)
        var outstanding = lanes.autofill.size + lanes.platform.size
        val executor = context.mainExecutor
        // WRAP_CONTENT, not the cap: the cap is the ceiling the sender lays
        // out against, and asking for an exact width would render every chip
        // at that width — one fat chip per screen, whatever is written on it.
        // The platform documents WRAP_CONTENT as valid for either dimension
        // and passes it straight to the remote view's LayoutParams, so each
        // chip comes back its own natural size and the row scrolls. Height
        // stays exact so the chips sit on the strip's baseline.
        val size = Size(ViewGroup.LayoutParams.WRAP_CONTENT, stripHeightPx)

        val finish = {
            if (--outstanding == 0) {
                onReady(
                    Chips(
                        autofill = autofillViews.filterNotNull(),
                        platform = platformViews.filterNotNull(),
                    ),
                )
            }
        }

        fun inflateInto(slots: Array<View?>, suggestions: List<InlineSuggestion>) {
            suggestions.forEachIndexed { index, suggestion ->
                runCatching {
                    suggestion.inflate(context, size, executor) { view ->
                        // Ordering matters: chips arrive out of order, and the
                        // sender ranks them, so slot them back by index rather
                        // than appending as they land.
                        slots[index] = view
                        finish()
                    }
                }.onFailure { finish() }
            }
        }

        inflateInto(autofillViews, lanes.autofill)
        inflateInto(platformViews, lanes.platform)
    }
}
