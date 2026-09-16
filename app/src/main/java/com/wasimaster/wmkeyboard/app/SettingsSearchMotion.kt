package com.wasimaster.wmkeyboard.app

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.min

/**
 * The settings search screen's motion, in one file.
 *
 * Three things move, and they are deliberately three different things:
 *
 * * **Arriving.** A list that has just been put on screen — the results for
 *   the first letter typed, the recent picks under an empty field — rises into
 *   place as a wave, top row first. See [rememberSearchReveal].
 * * **Updating.** Every further keystroke edits a list that is already there.
 *   Rows that lost their match fade out, the survivors glide to their new
 *   places, and the rows the new letter brought in fade in behind them. That is
 *   [searchItemMotion], and it is the list's own placement animation rather
 *   than anything of ours: the row keeps its identity through the move, so it
 *   is the same row travelling, not one row leaving and another arriving.
 * * **Changing state.** Picks give way to placeholders, placeholders to
 *   results, results to "nothing matches". Those are whole pages replacing one
 *   another, so they cross-fade and lift. See [searchStageTransform].
 *
 * The wave and the placement animation must not both own a row at the same
 * time, or a row that merely moved would also fade from nothing. They don't:
 * the wave runs once per entry into a stage and is finished — every row at
 * full opacity — long before a second keystroke can land, and a wave that is
 * already at its end leaves the rows exactly where the placement animation
 * puts them.
 *
 * Every animation here collapses to nothing when the reader has asked for
 * reduced motion. They are passed the flag rather than reading it, because the
 * caller already holds the settings and a composition local for one screen's
 * motion would be a local nothing else reads.
 */

/** How far a row falls short of its place at the start of the wave. */
private val RevealRise = 16.dp

/** A row's size at the start of the wave: a hair small, not a zoom. */
private const val RevealScaleFrom = 0.96f

/** How long one row takes to arrive. */
private const val RevealRowMs = 300

/** How far behind the row above it each row starts. */
private const val RevealStaggerMs = 28

/**
 * The last row that waits its turn.
 *
 * Without a cap the wave would take as long as the list is long, and a search
 * for a common word returns eighty rows — the last of them would arrive two
 * and a half seconds after the first. Everything past this row rides the same
 * slice as this row, which nobody can tell apart: they are all below the fold.
 */
private const val RevealMaxStagger = 12

/** The whole wave, from the first row leaving to the last row landing. */
private const val RevealTotalMs = RevealRowMs + RevealStaggerMs * RevealMaxStagger

/**
 * One clock for a whole list's arrival, sliced per row.
 *
 * A row animating itself would be an [Animatable] per row, each with its own
 * coroutine and its own recomposition, on a list that is rebuilt on every
 * keystroke. This is one animation for the list; a row reads its own slice of
 * it in the draw phase, so the wave costs one redraw per frame and no
 * recomposition at all.
 *
 * [instant] is reduced motion: the clock starts finished, every row draws
 * plainly, and nothing is ever scheduled.
 */
@Stable
internal class SearchReveal(private val instant: Boolean) {
    private val clock = Animatable(if (instant) 1f else 0f)

    /** Runs the wave once. Held by [rememberSearchReveal]'s effect. */
    suspend fun run() {
        if (instant) return
        clock.animateTo(1f, tween(durationMillis = RevealTotalMs, easing = LinearEasing))
    }

    /**
     * How far the row at [index] has arrived, 0 to 1.
     *
     * The clock itself is linear and the curve is applied here, per row: an
     * eased clock cut into slices would hand the rows in the middle of the
     * list a faster arrival than the ones at either end.
     */
    fun at(index: Int): Float {
        val t = clock.value
        if (t >= 1f) return 1f
        val start = RevealStaggerMs * min(index, RevealMaxStagger) / RevealTotalMs.toFloat()
        val span = RevealRowMs / RevealTotalMs.toFloat()
        val local = ((t - start) / span).coerceIn(0f, 1f)
        return NavTransitionEasing.transform(local)
    }
}

/**
 * The wave for the list being composed, started on its first frame.
 *
 * Remembered without a key on purpose: it belongs to this composition, which
 * is one stage of the search screen, and a stage lives exactly as long as it is
 * the stage being shown. Typing another letter edits the list inside it and
 * must not start the wave again — that is what [searchItemMotion] is for.
 */
@Composable
internal fun rememberSearchReveal(reduceMotion: Boolean): SearchReveal {
    val reveal = remember(reduceMotion) { SearchReveal(reduceMotion) }
    LaunchedEffect(reveal) { reveal.run() }
    return reveal
}

/**
 * Draws this row at its point in the wave: below its place, a little small and
 * transparent, arriving at the position the layout already gave it.
 *
 * A draw-phase read of the clock, so the wave never recomposes the row — which
 * matters here more than usual, because a result row resolves an icon, a tool's
 * accent and an annotated title when it composes.
 */
internal fun Modifier.searchReveal(reveal: SearchReveal, index: Int): Modifier =
    this.graphicsLayer {
        val arrived = reveal.at(index)
        alpha = arrived
        translationY = (1f - arrived) * RevealRise.toPx()
        val scale = RevealScaleFrom + (1f - RevealScaleFrom) * arrived
        scaleX = scale
        scaleY = scale
    }

/**
 * A row's part in a list that is being edited under it, as the query grows or
 * shrinks by a letter.
 *
 * The three timings are one choreography, not three settings. A row that no
 * longer matches leaves first and quickly, because it is the one thing on
 * screen that is now wrong. The survivors then glide into the gaps it left, on
 * a spring, with just enough damping to settle without a wobble — this is the
 * part the eye actually follows, and it is what makes "row moved" read
 * differently from "row replaced". The rows the new letter brought in wait for
 * both before fading in, so they appear into a list that has finished
 * rearranging rather than into one still in motion.
 *
 * Only works because the results are keyed on [SettingsSearchEntry.key]: the
 * list has to be able to tell that the row three places down is the row that
 * was at the top a moment ago.
 */
internal fun LazyItemScope.searchItemMotion(reduceMotion: Boolean): Modifier =
    if (reduceMotion) {
        Modifier
    } else {
        Modifier.animateItem(
            fadeInSpec = tween(durationMillis = 200, delayMillis = 90),
            placementSpec = spring(
                dampingRatio = 0.9f,
                stiffness = Spring.StiffnessMediumLow,
                visibilityThreshold = IntOffset.VisibilityThreshold,
            ),
            fadeOutSpec = tween(durationMillis = 140),
        )
    }

/**
 * One whole stage of the screen replacing another.
 *
 * The outgoing page goes first and fast; the incoming one waits for it to be
 * most of the way gone before lifting into place, so the two are never both
 * legible at once. The lift is a fraction of the page's own height, which keeps
 * it a suggestion of movement rather than a slide.
 *
 * [SizeTransform] with `clip = false`: the stages are different heights and the
 * shorter one must not be cut off while the box between them is still growing.
 */
internal fun AnimatedContentTransitionScope<*>.searchStageTransform(
    reduceMotion: Boolean,
): ContentTransform =
    if (reduceMotion) {
        fadeIn(snap()) togetherWith fadeOut(snap())
    } else {
        val enter = fadeIn(tween(durationMillis = 220, delayMillis = 70)) +
            slideInVertically(
                tween(durationMillis = 320, delayMillis = 70, easing = NavTransitionEasing),
            ) { height -> height / 18 }
        enter togetherWith fadeOut(tween(durationMillis = 130)) using SizeTransform(clip = false)
    }

/**
 * The clear button appearing with the first letter typed.
 *
 * It springs rather than fades because it is a thing arriving beside the
 * cursor, and a button that grows into place under the finger that is typing
 * reads as an answer to the typing.
 */
internal fun searchClearEnter(reduceMotion: Boolean): EnterTransition =
    if (reduceMotion) {
        EnterTransition.None
    } else {
        scaleIn(
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessMediumLow,
            ),
            initialScale = 0.6f,
        ) + fadeIn(tween(durationMillis = 120))
    }

/** The clear button leaving with the last letter deleted. Plain, and quick. */
internal fun searchClearExit(reduceMotion: Boolean): ExitTransition =
    if (reduceMotion) {
        ExitTransition.None
    } else {
        scaleOut(tween(durationMillis = 120), targetScale = 0.6f) +
            fadeOut(tween(durationMillis = 120))
    }
