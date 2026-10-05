package com.wasimaster.wmkeyboard.core.tools

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * How "%" reads next to the four operators. A trailing percentage added to
 * or taken from a value is a share of that value — the tip-and-discount
 * reading every pocket calculator uses — while on its own, or against × and
 * ÷, it stays a plain hundredth. Infix "%" is still modulo.
 */
class CalcPercentTest {

    private fun eval(expression: String) = CalcEngine.evaluate(expression)

    @Test
    fun addedAndSubtractedPercentsAreSharesOfTheLeftSide() {
        assertEquals(120.0, eval("100+20%"), 1e-9)
        assertEquals(80.0, eval("100-20%"), 1e-9)
        assertEquals(1200.0, eval("1500-20%"), 1e-9)
        assertEquals(4500.0 * 1.15, eval("4500+15%"), 1e-9)
        // Minus sign as the keyboard's own U+2212, and spaces around it.
        assertEquals(80.0, eval("100 − 20 %"), 1e-9)
    }

    @Test
    fun theShareFollowsTheRunningValue() {
        // 100+10 is 110, and the 20% is 22 of that, not 20 of the first term.
        assertEquals(132.0, eval("100+10+20%"), 1e-9)
        // Two in a row compound: 120, then a tenth off.
        assertEquals(108.0, eval("100+20%-10%"), 1e-9)
    }

    @Test
    fun aPercentOnItsOwnIsStillAHundredth() {
        assertEquals(0.2, eval("20%"), 1e-9)
        assertEquals(0.2, eval("(20%)"), 1e-9)
        // Parentheses are the escape hatch: the share reading needs the "%"
        // to sit directly on the term being added.
        assertEquals(100.2, eval("100+(20%)"), 1e-9)
    }

    @Test
    fun timesAndDividedByKeepTheHundredthReading() {
        assertEquals(20.0, eval("100*20%"), 1e-9)
        assertEquals(20.0, eval("100×20%"), 1e-9)
        assertEquals(500.0, eval("100/20%"), 1e-9)
        // The "%" trails arithmetic of its own, so it is not a share either.
        assertEquals(100.6, eval("100+20%*3"), 1e-9)
    }

    @Test
    fun infixPercentIsStillModulo() {
        assertEquals(1.0, eval("10%3"), 1e-9)
        // Modulo binds tighter than the sum, so this is 100 + (10 mod 3).
        assertEquals(101.0, eval("100+10%3"), 1e-9)
        assertEquals(2.0, eval("17 mod 5"), 1e-9)
    }
}
