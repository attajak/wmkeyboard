package com.wasimaster.wmkeyboard.app

import com.wasimaster.wmkeyboard.ime.KeyboardAutomation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The automation receiver's intent filter is what lets `am broadcast -a <action>
 * -p <package>` and Tasker reach it, so every action the receiver handles has to
 * be in the filter, and nothing the receiver ignores.
 */
class AutomationManifestTest {

    private val manifest = File("src/main/AndroidManifest.xml").readText()

    private val receiverBlock: String = manifest
        .substringAfter("android:name=\"com.wasimaster.wmkeyboard.ime.KeyboardAutomationReceiver\"")
        .substringBefore("</receiver>")

    @Test
    fun `receiver is declared and exported`() {
        assertTrue(receiverBlock.isNotEmpty() && receiverBlock != manifest)
        assertTrue(receiverBlock.contains("android:exported=\"true\""))
    }

    @Test
    fun `filter lists exactly the handled actions`() {
        val declared = Regex("<action android:name=\"([^\"]+)\"").findAll(receiverBlock)
            .map { it.groupValues[1] }
            .toSet()
        assertEquals(KeyboardAutomation.actions, declared)
    }
}
