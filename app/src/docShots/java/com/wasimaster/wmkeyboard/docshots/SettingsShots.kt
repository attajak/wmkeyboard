package com.wasimaster.wmkeyboard.docshots

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.wasimaster.wmkeyboard.app.MainActivity
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Docs screenshots of the settings app, rendered on the JVM.
 *
 * Runs only under `-Pwmkb.docShots=true` (see the end of app/build.gradle.kts).
 * Each shot launches the real [MainActivity] through the same
 * `wmkeyboard://settings/<route>` link a user would follow, so the screen is
 * the shipped one — nav graph, theme, crumb trail and all — and not a
 * re-assembly of it that could drift.
 *
 * Pixel 5 geometry: 393x851dp at 440dpi is the 1080x2340 the device shots use.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w393dp-h851dp-normal-long-notround-any-440dpi-keyshidden-nonav")
class SettingsShots {

    @get:Rule
    val compose = createEmptyComposeRule()

    @Test
    fun dataSaver() = shoot("spike/data-saver", route = "datasaver", waitFor = "Data saver")

    @Test
    fun accessibility() = shoot("accessibility/vision-group", route = "accessibility", waitFor = "Accessibility")

    private fun shoot(id: String, route: String, waitFor: String) {
        val app = ApplicationProvider.getApplicationContext<Application>()
        // Past onboarding, or every link lands on the welcome screen instead.
        runBlocking { SettingsRepository(app).setOnboardingDone(true) }
        val link = Intent(Intent.ACTION_VIEW, Uri.parse("wmkeyboard://settings/$route"))
            .setClass(app, MainActivity::class.java)
        ActivityScenario.launch<MainActivity>(link).use {
            // The first frame waits on DataStore and the asset layouts, both
            // off the main thread, so the screen is not there at launch.
            compose.waitUntil(timeoutMillis = 30_000) {
                compose.onAllNodesWithText(waitFor).fetchSemanticsNodes().isNotEmpty()
            }
            compose.waitForIdle()
            val out = File(System.getProperty("wmkb.docShots.out"), "$id.png")
            out.parentFile?.mkdirs()
            captureScreenRoboImage(out.path)
        }
    }
}
