package com.wasimaster.wmkeyboard.core.prediction

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TypedNumbersTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `only runs of digits of a useful length are learned`() {
        val numbers = TypedNumbers()
        assertFalse(numbers.record("12"))
        assertFalse(numbers.record("12a4"))
        assertFalse(numbers.record("1,234"))
        assertFalse(numbers.record("1".repeat(TypedNumbers.MAX_LENGTH + 1)))
        assertTrue(numbers.record(" 01712345678 "))
        assertEquals(listOf("01712345678"), numbers.complete("0", 5))
    }

    @Test
    fun `the most typed comes first, a longer prefix narrows it`() {
        val numbers = TypedNumbers()
        numbers.record("01712345678", now = 1)
        numbers.record("01811111111", now = 2)
        numbers.record("01712345678", now = 3)
        numbers.record("01799999999", now = 4)
        assertEquals(listOf("01712345678", "01799999999", "01811111111"), numbers.complete("01", 5))
        assertEquals(listOf("01712345678", "01799999999"), numbers.complete("017", 5))
        // The number already typed in full has nothing left to complete,
        // and nothing at all is offered before a digit is typed.
        assertEquals(emptyList<String>(), numbers.complete("01811111111", 5))
        assertEquals(emptyList<String>(), numbers.complete("", 5))
    }

    @Test
    fun `the least recently used falls off past the cap`() {
        val numbers = TypedNumbers()
        for (i in 0..TypedNumbers.MAX_NUMBERS) numbers.record("1${i.toString().padStart(4, '0')}", now = i.toLong())
        assertFalse("10000" in numbers.complete("1", 1000))
        assertEquals(TypedNumbers.MAX_NUMBERS, numbers.complete("1", 1000).size)
    }

    @Test
    fun `survives a save and a reload, and clear forgets`() {
        val file = File(temp.root, "typed_numbers.json")
        TypedNumbers(file).apply {
            record("4111222")
            save()
        }
        val again = TypedNumbers(file)
        assertEquals(listOf("4111222"), again.complete("41", 5))
        again.clear()
        assertTrue(again.isEmpty)
        assertFalse(file.exists())
    }

    @Test
    fun `a run counts only when it stands on its own`() {
        assertEquals("123", TypedNumbers.runBefore("123"))
        assertEquals("0171", TypedNumbers.runBefore("call me on 0171"))
        assertEquals("880", TypedNumbers.runBefore("+880"))
        assertEquals("880", TypedNumbers.runBefore("(880"))
        assertEquals("১২৩", TypedNumbers.runBefore("নম্বর ১২৩"))
        // Glued to a word, or the tail of a grouped number, a time, a date.
        assertNull(TypedNumbers.runBefore("win10"))
        assertNull(TypedNumbers.runBefore("1,234"))
        assertNull(TypedNumbers.runBefore("12:30"))
        assertNull(TypedNumbers.runBefore("0171-234"))
        // Not at a digit at all.
        assertNull(TypedNumbers.runBefore("123 "))
        assertNull(TypedNumbers.runBefore(""))
        assertNull(TypedNumbers.runBefore(null))
        assertNull(TypedNumbers.runBefore("1".repeat(TypedNumbers.MAX_LENGTH + 1)))
    }
}
