package com.wasimaster.wmkeyboard.app.updates

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the dialog says about a settings link this build could not follow,
 * for every state an updater can be in.
 */
class NewerLinkTest {

    private val installed = "0.5.11"

    private fun verdict(since: String, state: UpdateState, autoCheck: Boolean = true) =
        LinkVerdict.of(since, installed, state, autoCheck)

    private fun available(name: String?) =
        UpdateState.Available(versionCode = 24, sizeBytes = 0L, immediate = false, versionName = name)

    @Test
    fun `versions compare by number, not by text`() {
        assertTrue(AppVersion.compare("0.5.10", "0.5.9") > 0)
        assertTrue(AppVersion.compare("0.5.9", "0.5.10") < 0)
        assertTrue(AppVersion.compare("1.0", "0.9.9") > 0)
        assertEquals(0, AppVersion.compare("0.6", "0.6.0"))
        assertEquals(0, AppVersion.compare("0.5.11-debug", "0.5.11"))
    }

    @Test
    fun `a link this version should already open is not an update question`() {
        assertEquals(LinkVerdict.NotInThisVersion, verdict("0.5.9", available("0.5.12")))
        assertEquals(LinkVerdict.NotInThisVersion, verdict("0.5.11", UpdateState.Idle))
        assertFalse(LinkVerdict.shouldCheck("0.5.11", installed, UpdateState.Idle, autoCheck = true))
    }

    @Test
    fun `a release that is new enough is offered`() {
        assertEquals(LinkVerdict.Update("0.5.12"), verdict("0.5.12", available("0.5.12")))
        assertEquals(LinkVerdict.Update("0.6.0"), verdict("0.5.12", available("0.6.0")))
        // No since: any newer version may be the one.
        assertEquals(LinkVerdict.Update("0.5.12"), verdict("", available("0.5.12")))
        // Play names no version, so it cannot be ruled out.
        assertEquals(LinkVerdict.Update(null), verdict("0.6.0", available(null)))
    }

    @Test
    fun `a release that is still too old means not released yet`() {
        assertEquals(LinkVerdict.NotReleased, verdict("0.6.0", available("0.5.12")))
        assertEquals(LinkVerdict.NotReleased, verdict("0.5.12", UpdateState.UpToDate(userAsked = true)))
        assertEquals(LinkVerdict.NotReleased, verdict("", UpdateState.UpToDate(userAsked = false)))
    }

    @Test
    fun `f-droid asks before it checks`() {
        assertEquals(LinkVerdict.Ask, verdict("0.5.12", UpdateState.Idle, autoCheck = false))
        assertFalse(LinkVerdict.shouldCheck("0.5.12", installed, UpdateState.Idle, autoCheck = false))
        assertEquals(LinkVerdict.Checking, verdict("0.5.12", UpdateState.Idle))
        assertTrue(LinkVerdict.shouldCheck("0.5.12", installed, UpdateState.Idle, autoCheck = true))
    }

    @Test
    fun `an update already under way is left alone`() {
        for (state in listOf(UpdateState.Downloading(1L, 2L), UpdateState.Downloaded, UpdateState.Installing)) {
            assertEquals(LinkVerdict.Updating, verdict("0.5.12", state))
            assertFalse(LinkVerdict.shouldCheck("0.5.12", installed, state, autoCheck = true))
        }
    }

    @Test
    fun `no updater and a failed check are told apart`() {
        assertEquals(LinkVerdict.CannotCheck(failed = false), verdict("0.5.12", UpdateState.Unsupported))
        assertEquals(LinkVerdict.CannotCheck(failed = true), verdict("0.5.12", UpdateState.Failed(cancelled = false)))
        // Backing out of the store's dialog asks again rather than reporting an error.
        assertEquals(LinkVerdict.Ask, verdict("0.5.12", UpdateState.Failed(cancelled = true)))
    }
}
