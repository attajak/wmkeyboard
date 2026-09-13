package com.wasimaster.wmkeyboard.core.prediction

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PendingLearnTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun file(): File = File(temp.root, "learning/pending_learn.json")

    @Test
    fun sightingsAccumulateAndReport() {
        val pending = PendingLearn(file())
        assertEquals(0, pending.sightings("wibble"))
        assertEquals(1, pending.sight("wibble"))
        assertEquals(2, pending.sight("wibble"))
        assertEquals(2, pending.sightings("wibble"))
    }

    @Test
    fun weightGradesTheEvidence() {
        val pending = PendingLearn(file())
        assertEquals(2, pending.sight("wibble", weight = 2))
        assertEquals(3, pending.sight("wibble"))
    }

    @Test
    fun caseAndCompositionFoldToOneEntry() {
        val pending = PendingLearn(file())
        pending.sight("Wibble")
        assertEquals(2, pending.sight("wibble"))
    }

    @Test
    fun oneLetterWordsAreNotCandidates() {
        val pending = PendingLearn(file())
        assertEquals(0, pending.sight("a"))
        assertEquals(0, pending.sightings("a"))
    }

    @Test
    fun forgettingClearsTheCount() {
        val pending = PendingLearn(file())
        pending.sight("wibble")
        pending.sight("wibble")
        pending.forget("wibble")
        assertEquals(0, pending.sightings("wibble"))
    }

    @Test
    fun decliningStopsFurtherCounting() {
        val pending = PendingLearn(file())
        pending.sight("wibble")
        pending.decline("wibble")
        assertTrue(pending.isDeclined("wibble"))
        assertEquals(0, pending.sight("wibble"))
        assertEquals(0, pending.sightings("wibble"))
    }

    @Test
    fun roundTripsThroughTheFile() {
        val f = file()
        PendingLearn(f).apply {
            sight("wibble", "en", weight = 2)
            decline("nope")
            save()
        }
        val back = PendingLearn(f)
        assertEquals(2, back.sightings("wibble"))
        assertEquals("en", back.languageOf("wibble"))
        assertTrue(back.isDeclined("nope"))
    }

    @Test
    fun cleanSaveWritesNothing() {
        val f = file()
        PendingLearn(f).save()
        assertFalse(f.exists())
    }

    @Test
    fun clearWipesTheFile() {
        val f = file()
        PendingLearn(f).apply {
            sight("wibble")
            save()
        }
        assertTrue(f.exists())
        PendingLearn(f).apply {
            clear()
            save()
        }
        assertEquals(0, PendingLearn(f).sightings("wibble"))
    }

    @Test
    fun staleSightingsDecayAndDisappear() {
        val f = file()
        val pending = PendingLearn(f)
        pending.sight("wibble")
        // Each save is one generation; a word untouched across the expiry
        // window loses its only sighting and goes.
        repeat(130) { pending.sight("anchor$it"); pending.save() }
        assertEquals(0, pending.sightings("wibble"))
    }

    @Test
    fun waitingListsWhatIsQueued() {
        val pending = PendingLearn(file())
        pending.sight("wibble")
        pending.sight("wobble", weight = 3)
        assertEquals(
            mapOf("wibble" to 1, "wobble" to 3),
            pending.waiting().toMap(),
        )
    }

    /** Issue #185: the waiting room takes only what the lexicon would. */
    @Test
    fun aWordWithASymbolGluedOnIsNeverACandidate() {
        val pending = PendingLearn(file())
        assertEquals(0, pending.sight("manager\""))
        assertEquals(0, pending.sight("manager\""))
        assertEquals(0, pending.sightings("manager\""))
        assertEquals(1, pending.sight("manager"))
    }
}
