package com.wasimaster.wmkeyboard.core.settings

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * The switch itself: off until it is asked for, and still on after a trip
 * through storage. The rule it turns on is `VietnameseStrictTonesTest`'s, and
 * what the switch is wired to is the push in the service.
 *
 * The round trip is the part worth having. A preference key that does not match
 * the one the reader looks for is not a compile error and not a crash — the
 * switch simply never remembers being turned on, which reads as the setting
 * being broken rather than as the wiring being wrong.
 *
 * It lives in this module because this is where the Robolectric harness is;
 * `:app`'s unit tests run on the plain JVM.
 */
@RunWith(RobolectricTestRunner::class)
class VietnameseSettingsTest {

    private fun repository() = SettingsRepository(RuntimeEnvironment.getApplication())

    @Test
    fun `strict tones are off until they are asked for`() = runBlocking {
        assertFalse(repository().settings.first().vietnamese.strictTones)
    }

    @Test
    fun `the switch survives the trip through storage`() = runBlocking {
        val repository = repository()
        repository.setVietnameseStrictTones(true)
        assertTrue(repository.settings.first().vietnamese.strictTones)
        repository.setVietnameseStrictTones(false)
        assertFalse(repository.settings.first().vietnamese.strictTones)
    }
}
