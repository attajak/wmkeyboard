package com.wasimaster.wmkeyboard.core.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The television overlay, and the line it draws between a capability and a
 * preference — which is the only thing standing between "sensible on a TV" and
 * "quietly overrides what a phone user chose".
 */
class TelevisionDefaultsTest {

    @Test fun everythingElseGetsBackTheVerySameInstance() {
        val settings = KeyboardSettings()
        assertSame(settings, settings.applyTelevision(television = false))
    }

    @Test fun aTelevisionCannotGlideSplitOrFloat() {
        // Stored true across the board: a phone's settings, restored onto a TV
        // box from the same account. None of them has an input device here.
        val phone = KeyboardSettings(
            gestureTyping = true,
            splitKeyboard = true,
            floatingKeyboard = true,
            oneHandedMode = OneHandedMode.LEFT,
        )
        val tv = phone.applyTelevision(television = true)
        assertFalse(tv.gestureTyping)
        assertFalse(tv.splitKeyboard)
        assertFalse(tv.floatingKeyboard)
        assertEquals(OneHandedMode.OFF, tv.oneHandedMode)
        assertFalse("nothing to vibrate", tv.haptics.enabled)
        assertFalse("nothing covering the key", tv.popup.enabled)
    }

    @Test fun aTelevisionRingsTheKeysSoTheRemoteCanReachThem() {
        assertTrue(KeyboardSettings().applyTelevision(television = true).hardwareKeyboard.dpadKeyNavigation)
    }

    @Test fun aTvUserWhoSwitchedTheRingOffKeepsItOff() {
        val chosen = KeyboardSettings(
            hardwareKeyboard = HardwareKeyboardSettings(
                dpadKeyNavigation = false,
                dpadKeyNavigationUntouched = false,
            ),
        )
        assertFalse(chosen.applyTelevision(television = true).hardwareKeyboard.dpadKeyNavigation)
    }

    @Test fun aTelevisionShowsTheDigitsAndSizesTheBoardForTheRoom() {
        val tv = KeyboardSettings().applyTelevision(television = true)
        assertTrue(tv.numberRow)
        assertEquals(TvKeyHeightDp, tv.keyHeightDp)
        assertEquals(TvNumberRowHeightDp, tv.numberRowHeightDp)
        assertEquals(TvKeyboardWidthPercent, tv.keyboardWidthPercent)
    }

    /**
     * The other half of the rule: sizing is a *default*, so a number the user
     * picked survives. Untouched-ness is read from the DataStore key's
     * presence, exactly as `DeviceFormDefaults` reads it.
     */
    @Test fun aTvUserWhoSizedTheBoardKeepsTheirNumbers() {
        val sized = KeyboardSettings(
            keyHeightDp = 56,
            numberRowHeightDp = 50,
            keyboardWidthPercent = 90,
            numberRow = false,
            layoutBehavior = LayoutBehaviorSettings(
                numberRowUntouched = false,
                keyHeightUntouched = false,
                keyboardWidthUntouched = false,
            ),
        )
        val tv = sized.applyTelevision(television = true)
        assertEquals(56, tv.keyHeightDp)
        assertEquals(50, tv.numberRowHeightDp)
        assertEquals(90, tv.keyboardWidthPercent)
        assertFalse(tv.numberRow)
        // …while the capabilities still go, because no slider makes a TV
        // touch-sensitive.
        assertFalse(tv.gestureTyping)
    }
}
