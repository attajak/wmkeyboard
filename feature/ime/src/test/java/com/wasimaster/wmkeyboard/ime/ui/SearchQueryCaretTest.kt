package com.wasimaster.wmkeyboard.ime.ui

import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.sp
import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

/**
 * Typing into one of the keyboard's own text boxes must not crash it (#252).
 *
 * `SearchQueryText` draws its caret at an x it asks the text's own layout for.
 * `Text` reports that layout during the *layout* phase, so the composition that
 * first sees a new query is still holding the layout of the previous one — and
 * the second keystroke asked a one-character layout for offset 2, which throws
 * `IllegalArgumentException: offset(2) is out of bounds [0, 1]` and took the
 * keyboard down in every capture box: translate, Wikipedia, emoji search, the
 * clipboard filter, all of them.
 *
 * So this types rather than asserting a position: a query that grows and
 * shrinks a character at a time with the caret at its end, which is exactly
 * what a keystroke does, plus a caret dropped into the middle by a tap.
 * Each step advances a real frame, since the bug lives in the gap between
 * composition and layout and a test that only recomposed would miss it.
 */
@RunWith(RobolectricTestRunner::class)
class SearchQueryCaretTest {

    private val compose = createComposeRule()

    // The rule launches a ComponentActivity, which this library module's test
    // manifest does not declare, so it is registered before the launch.
    @get:Rule
    val rules: RuleChain = RuleChain
        .outerRule(
            object : ExternalResource() {
                override fun before() {
                    val app = RuntimeEnvironment.getApplication()
                    shadowOf(app.packageManager)
                        .addActivityIfNotPresent(ComponentName(app, ComponentActivity::class.java))
                }
            },
        )
        .around(compose)

    private var query by mutableStateOf("")
    private var caret by mutableIntStateOf(0)
    private val taps = mutableListOf<Int>()

    private fun start() {
        // Reduce motion, so the caret holds solid instead of blinking: its
        // blink is an endless `delay` loop, and `waitForIdle` would advance the
        // test clock into it forever.
        val theme = defaultKbTheme(
            scheme = lightColorScheme(),
            dark = false,
            amoled = false,
            settings = KeyboardSettings(),
        ).copy(reduceMotion = true)
        compose.setContent {
            CompositionLocalProvider(
                LocalKbTheme provides theme,
                LocalCaptureCaret provides CaptureCaretHandle(caret) { taps += it },
            ) {
                SearchQueryText(
                    query = query,
                    placeholder = "Search",
                    active = true,
                    textColor = Color.Black,
                    placeholderColor = Color.Gray,
                    fontSize = 14.sp,
                )
            }
        }
        compose.waitForIdle()
    }

    /** One keystroke: the text grows and the caret goes with it, then a frame. */
    private fun type(text: String) {
        for (c in text) {
            query += c
            caret = query.length
            compose.waitForIdle()
        }
    }

    @Test
    fun `typing a word into a search box does not crash`() {
        start()
        type("hello")
        assertEquals("hello", query)
    }

    @Test
    fun `backspacing back to empty does not crash`() {
        start()
        type("hi")
        while (query.isNotEmpty()) {
            query = query.dropLast(1)
            caret = query.length
            compose.waitForIdle()
        }
        assertEquals("", query)
    }

    @Test
    fun `a caret in the middle survives the text changing under it`() {
        start()
        type("hello")
        // A tap put it mid-word…
        caret = 2
        compose.waitForIdle()
        // …and the next keystroke lands there, which is a caret that is neither
        // 0 nor the end while the layout is being replaced.
        query = "heXllo"
        caret = 3
        compose.waitForIdle()
        assertEquals("heXllo", query)
    }

    @Test
    fun `a caret past the end of the text it lands in does not crash`() {
        // The worst case the stale-layout bug produced: the caret is already
        // past the end of the string the layout on hand describes.
        start()
        type("hello")
        query = "hi"
        caret = 5
        compose.waitForIdle()
        assertEquals("hi", query)
    }

    @Test
    fun `an emoji query does not crash`() {
        // Surrogate pairs: the caret is clamped in UTF-16 units and a layout
        // rejects an offset inside one.
        start()
        query = "👍"
        caret = 2
        compose.waitForIdle()
        query = "👍👍"
        caret = 4
        compose.waitForIdle()
        assertEquals(4, caret)
    }
}
