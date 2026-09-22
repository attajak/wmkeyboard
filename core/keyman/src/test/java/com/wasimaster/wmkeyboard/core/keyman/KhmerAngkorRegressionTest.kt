package com.wasimaster.wmkeyboard.core.keyman

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Khmer Angkor's own regression suite, run through the engine.
 *
 * The keyboard's authors keep these beside its source upstream
 * (`release/k/khmer_angkor/extras/regression_tests`): one file per case, each a
 * sequence of keystrokes with the text the field must hold after every one.
 * They were written against Keyman for Windows, so they run with Windows'
 * platform words — a rule guarded by `platform('touch')` must stay out of them,
 * which is itself part of what they check.
 *
 * Most of them type a cluster in a wrong order and expect the keyboard's
 * `match > use(normalise)` pass to put it right, so they are the test of that
 * pass, of deletes cancelling against the keystroke's own output, and of
 * `any()`/`index()` across groups, in a way no hand-written case is.
 */
class KhmerAngkorRegressionTest {

    private class Case(val name: String, val events: List<Pair<ProcessorKey, String>>)

    private fun keyboard(): KeymanKeyboard {
        val bytes = checkNotNull(javaClass.classLoader?.getResourceAsStream("kmx/khmer_angkor.kmx")).use { it.readBytes() }
        return (KmxParser.parse(bytes) as KeymanResult.Success).value
    }

    private fun cases(): List<Case> {
        val dir = File(checkNotNull(javaClass.classLoader?.getResource("regression/khmer_angkor")).toURI())
        // The files name a DTD on a long-dead host; never go looking for it.
        val factory = DocumentBuilderFactory.newInstance().apply {
            setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
        }
        return dir.listFiles { f -> f.name.endsWith(".xml") }.orEmpty().sortedBy { it.name }.map { file ->
            val doc = factory.newDocumentBuilder().parse(file)
            val events = doc.getElementsByTagName("event")
            Case(
                file.nameWithoutExtension,
                (0 until events.length).map { i ->
                    val event = events.item(i) as org.w3c.dom.Element
                    val vkey = event.getElementsByTagName("vkey").item(0).textContent.trim()
                    val shift = event.getElementsByTagName("shiftstate").item(0) as? org.w3c.dom.Element
                    var modifiers = 0
                    if (shift?.getElementsByTagName("shift")?.length ?: 0 > 0) modifiers = modifiers or KmxFormat.K_SHIFTFLAG
                    if (shift?.getElementsByTagName("altgr")?.length ?: 0 > 0) modifiers = modifiers or KmxFormat.RALTFLAG
                    val text = event.getElementsByTagName("postcontext").item(0).textContent
                    ProcessorKey(checkNotNull(VirtualKeys.byName(vkey)) { "unknown $vkey" }, modifiers) to text
                },
            )
        }
    }

    /** Types [case] and returns the first event whose field disagrees, or null. */
    private fun run(engine: KeyProcessor, case: Case): String? {
        val field = StringBuilder()
        engine.resetContext("")
        case.events.forEachIndexed { i, (key, expected) ->
            when (val result = engine.process(key)) {
                is ProcessorResult.Edit -> {
                    check(result.deleteBefore <= field.length) { "${case.name}: over-delete" }
                    field.setLength(field.length - result.deleteBefore)
                    field.append(result.insert)
                }
                is ProcessorResult.Declined -> field.append(VirtualKeys.toChar(key.vkey, key.modifiers))
                is ProcessorResult.Failed -> return "${case.name}#$i faulted ${result.fault}"
            }
            if (field.toString() != expected) return "${case.name}#$i expected '$expected' got '$field'"
        }
        return null
    }

    @Test
    fun `khmer angkor passes its own regression suite`() {
        val kb = keyboard()
        val cases = cases()
        assertEquals("the suite went missing", 94, cases.size)
        val failures = cases.mapNotNull { run(KmxProcessor(kb, platform = DESKTOP), it) }
        assertEquals(failures.joinToString("\n"), 0, failures.size)
    }

    private companion object {
        const val DESKTOP = "windows desktop hardware native"
    }
}
