package com.wasimaster.wmkeyboard.docshots

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.wasimaster.wmkeyboard.app.MainActivity
import com.wasimaster.wmkeyboard.app.SettingsHighlight
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Docs screenshots of the settings app, rendered on the JVM, each in light and
 * dark.
 *
 * Runs only under `-Pwmkb.docShots=true` (see the end of app/build.gradle.kts);
 * `-Pwmkb.docShots.only=<regex>` narrows it to matching ids. Each shot
 * launches the real [MainActivity] through the same
 * `wmkeyboard://settings/<route>[?setting=<name>]` link a reader of the docs
 * would follow, so what is captured is the shipped screen and not a
 * re-assembly of it that could drift.
 *
 * Writes `<id>.<light|dark>.png` and, beside each, a `.json` naming the ring
 * to draw. docs/screenshots/finish.py turns those into the docs assets.
 *
 * Pixel 5 geometry: 393x851dp at 440dpi is the 1080x2340 the device shots use.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w393dp-h851dp-normal-long-notround-any-440dpi-keyshidden-nonav")
class SettingsShots(
    private val shot: Shot,
    private val dark: Boolean,
    // Only names the test in reports; JUnit allows the one constructor.
    @Suppress("unused") private val name: String,
) {

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{2}")
        fun shots(): List<Array<Any>> {
            val only = System.getProperty("wmkb.docShots.only")?.takeIf { it.isNotBlank() }?.toRegex()
            val modes = shotModes()
            return SHOTS
                .filter { only == null || only.containsMatchIn(it.id) }
                .flatMap { shot ->
                    modes.map { dark ->
                        arrayOf(shot, dark, "${shot.id} ${if (dark) "dark" else "light"}")
                    }
                }
        }

        private const val TOP_FRACTION = 0.28f

        /** 24 dp at 440 dpi: clear of the list's top edge, ring and all. */
        private const val LIST_MARGIN_PX = 66f
    }

    @get:Rule
    val compose = createEmptyComposeRule()

    private val out = File(System.getProperty("wmkb.docShots.out") ?: "build/docshots")

    @Test
    fun capture() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        grantInternet(app)
        RuntimeEnvironment.setQualifiers(if (dark) "+night" else "+notnight")
        SettingsHighlight.clear()
        SettingsHighlight.hold = true
        val repo = SettingsRepository(app)
        val seed = Seed(repo, app)
        val launch = runBlocking {
            // The DataStore outlives a test in this process, so every shot
            // starts from the defaults rather than from the last shot's state.
            repo.clearAllPreferences()
            // Past onboarding, or every link lands on the welcome screen.
            if (!shot.onboarding) repo.setOnboardingDone(true)
            shot.seed(seed)
            shot.launch?.invoke(seed)
        }
        shot.setting?.let { name ->
            require(app.resources.getIdentifier(name, "string", app.packageName) != 0) {
                "No string resource named $name"
            }
        }
        // The same link a docs page would carry: a row, or a group by its heading.
        val link = buildString {
            append("wmkeyboard://settings/").append(shot.route)
            shot.setting?.let { append("?setting=").append(it) }
        }
        val intent = launch ?: Intent(Intent.ACTION_VIEW, Uri.parse(link)).setClass(app, MainActivity::class.java)
        // A screen that never goes idle (a blinking cursor, an endless
        // animation) fails in seconds instead of Espresso's 60 s per wait.
        androidx.test.espresso.IdlingPolicies.setMasterPolicyTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
        androidx.test.espresso.IdlingPolicies.setIdlingResourceTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
        ActivityScenario.launch<android.app.Activity>(intent).use {
            val steps = StepsImpl(compose)
            if (shot.freezeClock) compose.mainClock.autoAdvance = false
            val base = File(out, "${shot.id}.${if (dark) "dark" else "light"}")
            base.parentFile?.mkdirs()
            try {
                steps.settle(first = true)
                if (shot.setting != null) steps.ringHighlight()
                shot.steps(steps)
                steps.settle()
            } catch (failure: Throwable) {
                // Keep what the screen looked like and what it held, so a spec
                // that went wrong can be fixed without a second run to look.
                runCatching { captureScreenRoboImage("${base.path}.fail.png") }
                File("${base.path}.fail.txt").writeText(
                    "$failure\n\n" + runCatching {
                        compose.onAllNodes(SemanticsMatcher("any") { true })
                            .fetchSemanticsNodes()
                            .joinToString("\n") { n -> "${n.screenBounds()}  ${n.config}" }
                    }.getOrElse { it.toString() },
                )
                throw failure
            }
            captureScreenRoboImage("${base.path}.png")
            val ring = steps.ring
            File("${base.path}.json").writeText(
                if (ring == null) "{}\n"
                else "{\"ring\": [${ring.left}, ${ring.top}, ${ring.right}, ${ring.bottom}], \"group\": ${steps.group}}\n",
            )
        }
        SettingsHighlight.clear()
        resetFakes()
    }

    private class StepsImpl(override val rule: ComposeTestRule) : Steps {
        var ring: Rect? = null

        /** A whole group, heading and all, rather than one row: it is ringed tighter. */
        var group = false

        private val all = SemanticsMatcher("any") { true }

        fun settle(first: Boolean = false) {
            // The first frame waits on DataStore and the asset layouts, both
            // off the main thread, and screens fill in from files and flows
            // after that. Settled means the tree stopped changing.
            val deadline = System.currentTimeMillis() + if (first) 30_000 else 10_000
            var last = -1
            var stable = 0
            while (stable < 3 && System.currentTimeMillis() < deadline) {
                tick()
                val count = runCatching { rule.onAllNodes(all).fetchSemanticsNodes().size }.getOrDefault(0)
                if (count > 3 && count == last) stable++ else stable = 0
                last = count
                Thread.sleep(120)
            }
            if (rule.mainClock.autoAdvance) rule.waitForIdle()
        }

        /**
         * Moves time on. Robolectric's clock stands still on its own, and a
         * settings screen composes its lower groups only once its entrance
         * animation has finished; left alone, everything below the first
         * screenful stays a skeleton and nothing down there can be found.
         */
        private fun tick() {
            // A stopped clock (a focused field's blinking cursor) is moved by
            // hand, and never waited on: it would never read idle.
            rule.mainClock.advanceTimeBy(100)
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper())
                .idleFor(java.time.Duration.ofMillis(100))
            if (rule.mainClock.autoAdvance) rule.waitForIdle()
        }

        fun ringHighlight() {
            val tag = hasTestTag(SettingsHighlight.HIGHLIGHT_TAG)
            val deadline = System.currentTimeMillis() + 8_000
            while (System.currentTimeMillis() < deadline &&
                rule.onAllNodes(tag).fetchSemanticsNodes().isEmpty()
            ) {
                tick()
                Thread.sleep(50)
            }
            val node = rule.onAllNodes(tag).fetchSemanticsNodes().firstOrNull()
                ?: error("The setting was not found on the screen: nothing was highlighted")
            placeInUpperThird(node)
            settle()
            val placed = rule.onAllNodes(tag).fetchSemanticsNodes().first()
            ring = placed.children.map { it.screenBounds() }.reduceOrNull(Rect::union)
                ?: placed.screenBounds()
            // A group's frame holds its heading and its cards; a row's, the one card.
            group = placed.children.size > 1
        }

        private fun placeInUpperThird(node: SemanticsNode) {
            val scrollable = rule.onAllNodes(hasScrollAction()).fetchSemanticsNodes()
                .maxByOrNull { it.size.height } ?: return
            val height = rule.onAllNodes(all).fetchSemanticsNodes().maxOf { it.size.height }
            // Never above the list's own top: a screen that pins something over
            // its list (Layout's keyboard preview) would hide the ring under it.
            val top = maxOf(height * TOP_FRACTION, scrollable.screenBounds().top + LIST_MARGIN_PX)
            val dy = node.screenBounds().top - top
            if (kotlin.math.abs(dy) < 4f) return
            rule.onNode(SemanticsMatcher("scrollable ${scrollable.id}") { it.id == scrollable.id })
                .performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, dy) }
        }

        private fun byText(text: String, substring: Boolean) =
            rule.onAllNodesWithText(text, substring = substring).onFirst()

        override fun tap(text: String, substring: Boolean) {
            val node = byText(text, substring)
            runCatching { node.performScrollTo() }
            node.performClick()
            settle()
        }

        override fun tapIcon(description: String, substring: Boolean) {
            val node = rule.onAllNodesWithContentDescription(description, substring = substring).onFirst()
            runCatching { node.performScrollTo() }
            node.performClick()
            settle()
        }

        override fun type(text: String, index: Int) {
            // A focused field's cursor blinks forever, and a clock that runs on
            // its own never sees the screen idle. Stopped, it sees one frame.
            rule.mainClock.autoAdvance = false
            rule.onAllNodes(hasSetTextAction())[index].performTextInput(text)
            rule.mainClock.advanceTimeBy(600)
        }

        override fun scrollTo(text: String, substring: Boolean) {
            val node = byText(text, substring)
            runCatching { node.performScrollTo() }
            settle()
            placeInUpperThird(byText(text, substring).fetchSemanticsNode())
            settle()
        }

        override fun ring(text: String, substring: Boolean) = ringNode { byText(text, substring) }

        override fun ringNode(find: ComposeTestRule.() -> SemanticsNodeInteraction) {
            val node = rule.find().fetchSemanticsNode()
            val bounds = node.screenBounds()
            ring = ring?.union(bounds) ?: bounds
        }

        override fun tapUntil(button: String, target: String, limit: Int) {
            repeat(limit) {
                if (rule.onAllNodesWithText(target).fetchSemanticsNodes().isNotEmpty()) return
                byText(button, false).performClick()
                settle()
            }
            error("$target never appeared after $limit presses of $button")
        }

        override fun settle() = settle(first = false)
    }
}

private fun SemanticsNode.screenBounds(): Rect {
    val at = positionOnScreen
    return Rect(at.x, at.y, at.x + size.width, at.y + size.height)
}

private fun Rect.union(other: Rect) = Rect(
    minOf(left, other.left),
    minOf(top, other.top),
    maxOf(right, other.right),
    maxOf(bottom, other.bottom),
)
