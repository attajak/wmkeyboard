package com.wasimaster.wmkeyboard.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.conflate

/**
 * How often a drag in progress is written to the store by the sliders that
 * still write while the finger is down — see [rememberLiveSlider]'s `live`.
 * Everything else commits once, on release, because a write costs far more
 * than a frame.
 */
private const val SLIDER_WRITE_INTERVAL_MS = 40L

/**
 * The live position of a slider that edits a stored setting, held locally so
 * the thumb follows the finger instead of the stored value: routing every touch
 * event through a DataStore write and waiting for the settings flow to come
 * back made the thumb visibly trail. Create one with [rememberLiveSlider], read
 * [value] for both the thumb and the readout, and hand [onDrag]/[onRelease] to
 * the [WmSlider].
 */
@Stable
class LiveSliderState(initial: Float) {
    var value by mutableFloatStateOf(initial)
        private set

    /** True from the first movement of a drag until the finger lifts. */
    var dragging by mutableStateOf(false)
        private set

    /** Replaced on every composition so the latest lambda is always called. */
    internal var commit: (Float) -> Unit = {}

    /**
     * What the value should do to the world *while* the finger is down — play
     * the key sound at the volume under the thumb, buzz at the strength under
     * it. Not the store: a commit per touch event is what [rememberLiveSlider]
     * exists to avoid.
     */
    internal var preview: ((Float) -> Unit)? = null

    internal fun adopt(external: Float) {
        if (!dragging) value = external
    }

    fun onDrag(next: Float) {
        dragging = true
        value = next
        preview?.invoke(next)
    }

    fun onRelease() {
        dragging = false
        commit(value)
    }
}

/**
 * A [LiveSliderState] wired to [value] and [onChange]. The store is written
 * once, when the finger lifts: a settings write costs a full recomposition of
 * the app's theme, the screen and — the keyboard service sharing the app's
 * process — the keyboard itself, measured at 35–45 ms on a mid-range phone.
 * Writing on a throttle while dragging spent that on every frame, so the thumb
 * ran at 24 fps and only caught up with the finger once it let go. Nothing on
 * screen needs the round trip: the thumb and the readout both read [value].
 *
 * [preview] is for the sliders that *do* have to act on every step — the key
 * sound's volume, a haptic's strength — and is called with the value under the
 * finger, no store in between.
 *
 * [live] is the exception, for a surface drawing the stored value itself: the
 * theme and sticker editors preview the theme they are editing, so their
 * sliders keep the old behaviour, a write per [SLIDER_WRITE_INTERVAL_MS].
 *
 * [value] is adopted only while no drag is in progress, so an edit from
 * elsewhere (a reset, another screen showing the same setting) still moves the
 * thumb but the user's own drag is never fought.
 */
@Composable
fun rememberLiveSlider(
    value: Float,
    onChange: (Float) -> Unit,
    live: Boolean = false,
    preview: ((Float) -> Unit)? = null,
): LiveSliderState {
    val state = remember { LiveSliderState(value) }
    state.commit = onChange
    state.preview = preview
    LaunchedEffect(value) { state.adopt(value) }
    if (live) {
        LaunchedEffect(state) {
            snapshotFlow { state.value }
                .conflate()
                .collect {
                    if (state.dragging) state.commit(it)
                    delay(SLIDER_WRITE_INTERVAL_MS)
                }
        }
    }
    return state
}
