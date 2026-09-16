package com.wasimaster.wmkeyboard.ime.ui

import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
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
 * [RowRevealHeadroom] and [RevealingBarRow] under Compose's own
 * AnimatedVisibility, frame by frame.
 *
 * The frame's height is the IME window's height, so what matters is when it
 * changes: once as a row starts arriving and once after it has finished
 * leaving, never on the frames between. The stack inside the frame is recorded
 * beside it, so nothing here passes because a row snapped instead of moving.
 */
@RunWith(RobolectricTestRunner::class)
class RowRevealHeadroomTest {

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

    private var tools by mutableStateOf(false)
    private var macros by mutableStateOf(false)
    private var macrosInStack by mutableStateOf(true)

    /** The reserving row: the chevron's on-demand tools row (issue #217). */
    private var onDemand by mutableStateOf(false)
    private var onDemandInStack by mutableStateOf(false)

    /** The frame's own headroom, for what it says is window rather than keyboard. */
    private lateinit var headroom: RowRevealHeadroom

    /** The macro bar with its offer gone: the row's content draws nothing. */
    private var macrosEmpty by mutableStateOf(false)

    /** The frame's height at each change, with the clock time it changed at. */
    private val frame = mutableListOf<Pair<Long, Int>>()

    /** The stack's own height at each change. */
    private val stack = mutableListOf<Int>()

    private val rowPx get() = with(compose.density) { ROW_DP.dp.roundToPx() }
    private val boardPx get() = with(compose.density) { BOARD_DP.dp.roundToPx() }

    @Test
    fun `a row growing in and shrinking away resizes the frame once each way`() {
        start()

        val enterAt = change { tools = true }
        assertEquals("one resize, to the final height", listOf(boardPx + rowPx), heights())
        assertTrue("on the entrance's first frames", frame.single().first - enterAt <= 2 * FRAME_MS)
        assertTrue("while the row really grew", stack.distinct().size > 2)

        forget()
        val exitAt = change { tools = false }
        assertEquals("one resize, back to the board", listOf(boardPx), heights())
        assertTrue("only once the row is gone", frame.single().first - exitAt >= MOVE_MS)
        assertTrue("while the row really shrank", stack.distinct().size > 2)
    }

    @Test
    fun `a row emptied in the update that hides it collapses at once, and nothing stays held`() {
        start()
        change { macros = true }

        forget()
        // The macro bar: its offer and its visibility go in the same update, so
        // its content is already gone when the exit begins.
        val exitAt = change {
            macrosEmpty = true
            macros = false
        }
        assertEquals("the row collapsed in one step", listOf(boardPx), stack)
        assertEquals("one resize, at once", listOf(boardPx), heights())
        assertTrue(frame.single().first - exitAt <= 2 * FRAME_MS)
    }

    @Test
    fun `content emptying mid-exit does not cut the hold short`() {
        start()
        change { macros = true }

        forget()
        val exitAt = change(frames = 3) { macros = false }
        change { macrosEmpty = true }
        // The shrink carries on from where it was, so the frame has to keep
        // the height the row had until the row is gone.
        assertTrue("the row kept shrinking", stack.distinct().size > 2)
        assertEquals(listOf(boardPx), heights())
        assertTrue(frame.single().first - exitAt >= MOVE_MS)
    }

    @Test
    fun `a row arriving as another leaves holds both, and never resizes per frame`() {
        start()
        change { tools = true }

        forget()
        change {
            tools = false
            macros = true
        }
        // Up by the arriving row on the first frame, down by the leaving one
        // after its last: what a sum costs a swap, and still not per frame.
        assertEquals(listOf(boardPx + 2 * rowPx, boardPx + rowPx), heights())
    }

    @Test
    fun `turning back mid-exit leaves the frame where it was`() {
        start()
        change { tools = true }

        forget()
        change(frames = 3) { tools = false }
        change { tools = true }
        assertEquals("the frame never moved", emptyList<Int>(), heights())
        assertTrue("while the row went part way out and back", stack.distinct().size > 2)
    }

    @Test
    fun `a row cut from the stack mid-move takes its hold with it`() {
        start()

        change(frames = 3) { macros = true }
        change { macrosInStack = false }
        assertEquals(listOf(boardPx + rowPx, boardPx), heights())
    }

    @Test
    fun `a reserving row opens and closes without resizing the frame at all`() {
        onDemandInStack = true
        start()
        assertEquals("the band is held while the row is out", rowPx, headroom.reservedPx)

        change { onDemand = true }
        assertEquals("the frame never moved", emptyList<Int>(), heights())
        assertTrue("while the row really grew", stack.distinct().size > 2)
        assertEquals("and the band is the row's own now", 0, headroom.reservedPx)

        forget()
        change { onDemand = false }
        assertEquals("nor on the way back", emptyList<Int>(), heights())
        assertTrue("while the row really shrank", stack.distinct().size > 2)
        assertEquals("the band is held again", rowPx, headroom.reservedPx)
    }

    @Test
    fun `a reserving row cut from the stack gives the band back`() {
        onDemandInStack = true
        start()

        change { onDemandInStack = false }
        assertEquals("the frame drops the band with the row", listOf(boardPx), heights())
        assertEquals(0, headroom.reservedPx)
    }

    @Test
    fun `a reserving row holds its own height beside a moving neighbour`() {
        onDemandInStack = true
        start()
        change { onDemand = true }

        forget()
        // The macro bar arrives while the tools row folds away: the arriving
        // row is the only thing the frame has to find room for, because the
        // leaving one hands its height straight back to its band.
        change {
            onDemand = false
            macros = true
        }
        assertEquals(listOf(boardPx + 2 * rowPx), heights())
        assertEquals(rowPx, headroom.reservedPx)
    }

    private fun start() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            val headroom = remember { RowRevealHeadroom() }
            this.headroom = headroom
            Box(
                Modifier
                    .onSizeChanged { frame += compose.mainClock.currentTime to it.height }
                    .rowRevealHeadroom(headroom),
            ) {
                CompositionLocalProvider(LocalRowRevealHeadroom provides headroom) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .onSizeChanged { stack += it.height },
                    ) {
                        RevealingBarRow(tools, expandVertically(tween(MOVE_MS)), shrinkVertically(tween(MOVE_MS))) {
                            Box(Modifier.fillMaxWidth().height(ROW_DP.dp))
                        }
                        if (macrosInStack) {
                            RevealingBarRow(macros, expandVertically(tween(MOVE_MS)), shrinkVertically(tween(MOVE_MS))) {
                                if (!macrosEmpty) Box(Modifier.fillMaxWidth().height(ROW_DP.dp))
                            }
                        }
                        if (onDemandInStack) {
                            RevealingBarRow(
                                onDemand,
                                expandVertically(tween(MOVE_MS)),
                                shrinkVertically(tween(MOVE_MS)),
                                reserveWhenOutPx = rowPx,
                            ) {
                                Box(Modifier.fillMaxWidth().height(ROW_DP.dp))
                            }
                        }
                        Box(Modifier.fillMaxWidth().height(BOARD_DP.dp))
                    }
                }
            }
        }
        advance(SETTLE_FRAMES)
        forget()
    }

    /**
     * Applies [block] and runs [frames] frames, by default the whole move and
     * the clean-up after it. Returns the clock time the change was made at.
     */
    private fun change(frames: Int = MOVE_MS / FRAME_MS + SETTLE_FRAMES, block: () -> Unit): Long {
        val at = compose.mainClock.currentTime
        block()
        Snapshot.sendApplyNotifications()
        advance(frames)
        return at
    }

    private fun advance(frames: Int) = repeat(frames) { compose.mainClock.advanceTimeByFrame() }

    private fun forget() {
        frame.clear()
        stack.clear()
    }

    private fun heights() = frame.map { it.second }

    private companion object {
        const val MOVE_MS = 140
        const val FRAME_MS = 16
        const val SETTLE_FRAMES = 6
        const val ROW_DP = 40
        const val BOARD_DP = 200
    }
}
