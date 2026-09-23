package com.wasimaster.wmkeyboard.ime

import android.text.InputType
import android.view.inputmethod.InputConnection
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

/**
 * The caret magnifier's line (discussion #303): what the field hands back
 * around its selection, cut to the one line the bubble draws, with the caret
 * on the end of the selection that is moving.
 */
class CaretMagnifierTest {

    @Test
    fun `a collapsed caret sits between before and after`() {
        val line = magnifierLine("hello wo", "", "rld", focusAtEnd = true)
        assertEquals(MagnifierLine("hello world", caret = 8, selStart = 8, selEnd = 8), line)
    }

    @Test
    fun `the line is cut at the breaks on both sides`() {
        val line = magnifierLine("first\nsecond li", "", "ne\nthird", focusAtEnd = true)
        assertEquals(MagnifierLine("second line", caret = 9, selStart = 9, selEnd = 9), line)
    }

    @Test
    fun `a caret right after a break starts the line`() {
        val line = magnifierLine("above\n", "", "below", focusAtEnd = true)
        assertEquals(MagnifierLine("below", caret = 0, selStart = 0, selEnd = 0), line)
    }

    @Test
    fun `a selection growing forward puts the caret at its end`() {
        val line = magnifierLine("say ", "hello", " world", focusAtEnd = true)
        assertEquals(MagnifierLine("say hello world", caret = 9, selStart = 4, selEnd = 9), line)
    }

    @Test
    fun `a selection growing backward puts the caret at its start`() {
        val line = magnifierLine("say ", "hello", " world", focusAtEnd = false)
        assertEquals(MagnifierLine("say hello world", caret = 4, selStart = 4, selEnd = 9), line)
    }

    @Test
    fun `a selection across lines shows the moving end's line`() {
        val line = magnifierLine("one ", "two\nthree", " four", focusAtEnd = true)
        assertEquals(MagnifierLine("three four", caret = 5, selStart = 0, selEnd = 5), line)
    }

    @Test
    fun `a long selection keeps only the part next to the caret`() {
        val selected = "a".repeat(100) + "tail"
        val line = magnifierLine("start ", selected, " end", focusAtEnd = true, context = 10)
        assertEquals("aaaaaatail end", line.text)
        assertEquals(10, line.caret)
        assertEquals(0, line.selStart)
        assertEquals(10, line.selEnd)
    }

    @Test
    fun `the context cap never splits a surrogate pair`() {
        // Each 😀 is two chars: a cap of 3 from the end lands inside the first.
        val line = magnifierLine("😀😀", "", "", focusAtEnd = true, context = 3)
        assertEquals("😀", line.text)
        assertEquals(2, line.caret)
    }

    @Test
    fun `one surrounding read splits into before, selection and after`() {
        val line = magnifierLineFromSurrounding("say hello world", 4, 9, focusAtEnd = true)
        assertEquals(MagnifierLine("say hello world", caret = 9, selStart = 4, selEnd = 9), line)
    }

    @Test
    fun `a backward surrounding selection reads the same`() {
        val line = magnifierLineFromSurrounding("say hello world", 9, 4, focusAtEnd = false)
        assertEquals(MagnifierLine("say hello world", caret = 4, selStart = 4, selEnd = 9), line)
    }

    @Test
    fun `offsets outside the surrounding text give no line`() {
        assertNull(magnifierLineFromSurrounding("short", 2, 40, focusAtEnd = true))
        assertNull(magnifierLineFromSurrounding("short", -1, 2, focusAtEnd = true))
    }

    @Test
    fun `a selection past the cap shows the free side and marks the rest`() {
        val forward = magnifierLineBeyondSelection(" tail\nnext", focusAtEnd = true)
        assertEquals(MagnifierLine(" tail", 0, 0, 0, SelectionBeyond.BEFORE), forward)
        val backward = magnifierLineBeyondSelection("prev\nhead ", focusAtEnd = false)
        assertEquals(MagnifierLine("head ", 5, 5, 5, SelectionBeyond.AFTER), backward)
    }

    @Test
    fun `a password line is bullets with the caret on the same character`() {
        val line = MagnifierLine("pa😀ss", caret = 4, selStart = 2, selEnd = 4).masked()
        assertEquals(MagnifierLine("•••••", caret = 3, selStart = 2, selEnd = 3), line)
    }

    @Test
    fun `only fields that hide their text are masked`() {
        val text = InputType.TYPE_CLASS_TEXT
        assertTrue(hidesTypedText(text or InputType.TYPE_TEXT_VARIATION_PASSWORD))
        assertTrue(hidesTypedText(text or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD))
        assertTrue(hidesTypedText(InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD))
        assertFalse(hidesTypedText(text or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD))
        assertFalse(hidesTypedText(text or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS))
        assertFalse(hidesTypedText(0))
    }

    @Test
    fun `a long selection is never read back`() {
        val field = FakeField(before = "start ", selected = "x".repeat(5000), after = " end")
        val line = readMagnifierLine(field.ic, 6, 5006, focusAtEnd = true, sdk = 0)
        assertEquals(MagnifierLine(" end", 0, 0, 0, SelectionBeyond.BEFORE), line)
        assertEquals(0, field.calls["getSelectedText"] ?: 0)
    }

    @Test
    fun `an unknown selection is not read either`() {
        val field = FakeField(before = "ab", selected = "zzz", after = "cd")
        val line = readMagnifierLine(field.ic, -1, -1, focusAtEnd = true, sdk = 0)
        assertEquals(MagnifierLine("abcd", 2, 2, 2), line)
        assertEquals(0, field.calls["getSelectedText"] ?: 0)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `moves during a read coalesce into one more read`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = TestScope(dispatcher)
        val field = FakeField(before = "hello", after = " world")
        val controller = CaretMagnifierController(scope, dispatcher, sdk = 0)
        controller.begin(CaretDragSource.TRACKPAD, field.ic, 5, 5, mask = false)
        repeat(10) { controller.onSelection(6 + it, 6 + it) }
        scope.advanceUntilIdle()
        assertEquals(2, field.calls["getTextBeforeCursor"])
        assertEquals("hello world", controller.state.value?.line?.text)
        controller.end(CaretDragSource.TRACKPAD)
        scope.advanceUntilIdle()
        assertNull(controller.state.value)
        assertEquals(listOf(true, false), field.monitoring)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `a read that lands after the drag ended draws nothing`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = TestScope(dispatcher)
        val field = FakeField(before = "hello", after = "")
        val controller = CaretMagnifierController(scope, dispatcher, sdk = 0)
        controller.begin(CaretDragSource.SPACEBAR, field.ic, 5, 5, mask = true)
        controller.end(CaretDragSource.SPACEBAR)
        scope.advanceUntilIdle()
        assertNull(controller.state.value)
        assertFalse(controller.active)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `a password field is drawn masked and one source letting go keeps the other`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = TestScope(dispatcher)
        val field = FakeField(before = "secret", after = "")
        val controller = CaretMagnifierController(scope, dispatcher, sdk = 0)
        controller.begin(CaretDragSource.TRACKPAD, field.ic, 6, 6, mask = true)
        controller.begin(CaretDragSource.SPACEBAR, field.ic, 6, 6, mask = true)
        controller.end(CaretDragSource.SPACEBAR)
        scope.advanceUntilIdle()
        assertEquals("••••••", controller.state.value?.line?.text)
        assertTrue(controller.active)
    }

    /** An editor holding [before] + [selected] + [after], counting what is asked of it. */
    private class FakeField(val before: String, val selected: String = "", val after: String) {
        val calls = mutableMapOf<String, Int>()
        val monitoring = mutableListOf<Boolean>()
        val ic: InputConnection = Proxy.newProxyInstance(
            InputConnection::class.java.classLoader,
            arrayOf(InputConnection::class.java),
        ) { _, method, args ->
            calls[method.name] = (calls[method.name] ?: 0) + 1
            when (method.name) {
                "getTextBeforeCursor" -> before.takeLast(args[0] as Int)
                "getTextAfterCursor" -> after.take(args[0] as Int)
                "getSelectedText" -> selected
                "requestCursorUpdates" -> {
                    monitoring += (args[0] as Int) != 0
                    true
                }
                else -> null
            }
        } as InputConnection
    }
}
