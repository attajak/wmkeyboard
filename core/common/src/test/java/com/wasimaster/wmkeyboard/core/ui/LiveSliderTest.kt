package com.wasimaster.wmkeyboard.core.ui

import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

/**
 * [rememberLiveSlider]: who hears about a drag, and when.
 *
 * The store is the expensive listener — a preference write recomposes the app's
 * theme, the screen and the keyboard sharing the process — so it is told once,
 * on release, while the thumb and anything playing a sound follow the finger.
 */
@RunWith(RobolectricTestRunner::class)
class LiveSliderTest {

    private val compose = createComposeRule()

    // As in WmSliderTest: the rule launches a ComponentActivity this library
    // module's test manifest does not declare.
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

    private var stored by mutableFloatStateOf(0f)
    private val commits = mutableListOf<Float>()
    private val previews = mutableListOf<Float>()
    private var slop = 0f

    @Test
    fun `a drag commits once, when the finger lifts`() {
        page()
        dragTo(0.75f, lift = false)
        assertEquals("the store heard nothing mid-drag", emptyList<Float>(), commits)
        compose.onNodeWithTag(SLIDER).performTouchInput { up() }
        compose.waitForIdle()
        assertEquals(1, commits.size)
        assertEquals(0.75f, commits.single(), TOLERANCE)
    }

    @Test
    fun `the thumb follows the finger while the store is still behind`() {
        page()
        dragTo(0.75f, lift = false)
        // Nothing has been written, so a screen reading the stored value would
        // still draw the thumb at zero.
        assertEquals(0f, stored, 0f)
        assertEquals(0.75f, sliderValue, TOLERANCE)
    }

    @Test
    fun `a tap commits where it lands`() {
        page()
        compose.onNodeWithTag(SLIDER).performTouchInput { click(Offset(width * 0.25f, centerY)) }
        compose.waitForIdle()
        assertEquals(1, commits.size)
        assertEquals(0.25f, commits.single(), TOLERANCE)
    }

    @Test
    fun `a preview hears every step of the drag`() {
        page(preview = { previews += it })
        dragTo(0.75f, lift = true)
        assertTrue("the preview heard the drag", previews.size > 1)
        assertEquals("and ended under the finger", 0.75f, previews.last(), TOLERANCE)
        assertEquals("while the store heard it once", 1, commits.size)
    }

    @Test
    fun `a live slider writes while the finger is down`() {
        page(live = true)
        dragTo(0.75f, lift = false)
        // The throttle is time-based, so the clock has to move for the first
        // write to land.
        compose.mainClock.advanceTimeBy(SLIDER_WRITE_WAIT_MS)
        compose.waitForIdle()
        assertTrue("the theme editor's slider still writes mid-drag", commits.isNotEmpty())
        compose.onNodeWithTag(SLIDER).performTouchInput { up() }
        compose.waitForIdle()
        assertEquals("and finishes on the value it was left at", 0.75f, commits.last(), TOLERANCE)
    }

    @Test
    fun `an edit from elsewhere moves the thumb, but never mid-drag`() {
        page()
        dragTo(0.75f, lift = false)
        // A reset on another screen, or this row's own restore button, while
        // the finger is still down.
        compose.runOnUiThread { stored = 0.1f }
        compose.waitForIdle()
        assertEquals("the drag is not fought", 0.75f, sliderValue, TOLERANCE)
        compose.onNodeWithTag(SLIDER).performTouchInput { up() }
        compose.waitForIdle()
        compose.runOnUiThread { stored = 0.1f }
        compose.waitForIdle()
        assertEquals("but an edit after it lands", 0.1f, sliderValue, TOLERANCE)
    }

    private var sliderValue = 0f

    private fun page(
        live: Boolean = false,
        preview: ((Float) -> Unit)? = null,
    ) {
        compose.setContent {
            slop = LocalViewConfiguration.current.touchSlop
            val slider = rememberLiveSlider(
                value = stored,
                onChange = { commits += it; stored = it },
                live = live,
                preview = preview,
            )
            sliderValue = slider.value
            Box(Modifier.fillMaxWidth().testTag(SLIDER)) {
                WmSlider(
                    value = slider.value,
                    onValueChange = slider::onDrag,
                    onValueChangeFinished = slider::onRelease,
                )
            }
        }
    }

    /** Drags from the left quarter to [fraction] of the track, sideways enough to be the slider's. */
    private fun dragTo(fraction: Float, lift: Boolean) {
        compose.onNodeWithTag(SLIDER).performTouchInput {
            down(Offset(width * 0.25f, centerY))
            moveBy(Offset(slop * 2f, 0f))
            repeat(3) { step ->
                val from = width * 0.25f
                val to = width * fraction
                moveTo(Offset(from + (to - from) * (step + 1) / 3f, centerY))
            }
            if (lift) up()
        }
        compose.waitForIdle()
    }
}

private const val SLIDER = "live-slider"

/** Past [rememberLiveSlider]'s own 40 ms throttle. */
private const val SLIDER_WRITE_WAIT_MS = 100L

/** Thumb widths shift the answer by under a hundredth on the test's slider. */
private const val TOLERANCE = 0.01f
