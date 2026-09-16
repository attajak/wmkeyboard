package com.wasimaster.wmkeyboard.ime

import org.junit.Assert.assertEquals
import org.junit.Test

class ShiftCaseTest {

    @Test fun offLeavesTheSuggestionAsTheEngineProducedIt() {
        assertEquals("hello", displayCaseForShift("hello", ShiftState.OFF))
        assertEquals("Hello", displayCaseForShift("Hello", ShiftState.OFF))
    }

    @Test fun onTitleCasesAndCapsLockUppercases() {
        assertEquals("Hello", displayCaseForShift("hello", ShiftState.ON))
        assertEquals("HELLO", displayCaseForShift("hello", ShiftState.CAPS_LOCK))
    }

    @Test fun emailsAndEmptyStringsAreLeftAlone() {
        assertEquals("john.doe@gmail.com", displayCaseForShift("john.doe@gmail.com", ShiftState.CAPS_LOCK))
        assertEquals("", displayCaseForShift("", ShiftState.ON))
    }

    @Test fun aPickInheritsTheCapitalOfTheWordItReplaces() {
        // Issue #212: glide "van" at the start of a sentence, take "can" off
        // the strip. Auto-capitalize wrote the capital and the commit spent
        // the shift, so only the word standing there can still say it.
        assertEquals("Can", caseLike("can", "Van"))
        assertEquals("can", caseLike("can", "van"))
    }

    @Test fun aShoutedWordIsReplacedByAShout() {
        assertEquals("CAN", caseLike("can", "VAN"))
        // One letter is a capital, not a shout.
        assertEquals("A", caseLike("a", "I"))
    }

    @Test fun nothingReplacedAndNothingCasedSayNothing() {
        assertEquals("can", caseLike("can", null))
        assertEquals("can", caseLike("can", ""))
        // No letters to read a case off.
        assertEquals("can", caseLike("can", "42"))
        // Never capitalize an address.
        assertEquals("john@gmail.com", caseLike("john@gmail.com", "Van"))
        assertEquals("", caseLike("", "Van"))
    }

    @Test fun lettersAreCountedByCodePoint() {
        // Osage is a cased script outside the BMP, where `Char.isLetter()` is
        // false for either half of a surrogate pair: a per-Char test reads
        // every Osage word as having no letters at all and can tell its
        // capitals from its small letters in neither direction.
        val shouted = "\uD801\uDCB0\uD801\uDCB1"
        val small = "\uD801\uDCD8\uD801\uDCD9"
        assertEquals("CAN", caseLike("can", shouted))
        assertEquals("can", caseLike("can", small))
    }
}
