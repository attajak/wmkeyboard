package com.wasimaster.wmkeyboard.ime.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** "Keep suggestions in place" (#513): a word's slot depends on its rank alone. */
class FixedSlotOrderTest {

    @Test fun theCentredOrderIsUnchangedWhenEverySlotIsFull() {
        assertEquals(listOf("b", "a", "c"), fixedSlotOrder(listOf("a", "b", "c"), 3, 1))
        assertEquals(listOf("b", "a", "c", "d"), fixedSlotOrder(listOf("a", "b", "c", "d"), 4, 1))
    }

    @Test fun thePrimaryStaysInTheMiddleWithFewerWords() {
        assertEquals(listOf("b", "a", null), fixedSlotOrder(listOf("a", "b"), 3, 1))
        assertEquals(listOf(null, "a", null), fixedSlotOrder(listOf("a"), 3, 1))
    }

    @Test fun aLeadingSlotTakenMovesThePrimaryToTheRowsFirst() {
        // A keyword chip in the strip's first slot: the words get the other
        // two, and the primary still sits in the strip's second.
        assertEquals(listOf("a", "b"), fixedSlotOrder(listOf("a", "b", "c"), 2, 0))
    }

    @Test fun leftAlignedWhenThePrimaryIsNotCentred() {
        assertEquals(listOf("a", "b", null), fixedSlotOrder(listOf("a", "b"), 3, 0))
    }
}
