package com.wasimaster.wmkeyboard.app

import java.io.File
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Writes the settings search index out as JSON for the documentation site.
 *
 * The docs' `<SettingsPath>` chip turns into a `wmkeyboard://` link on an
 * Android phone, and to do that it has to know which screen, or which row on
 * which screen, a breadcrumb like "Typing / Automatic corrections" names. The
 * search index is the one place that already knows every addressable screen
 * and row, with the route that opens it and the resource name `?setting=`
 * expects, so the docs read a dump of it rather than keeping a second table.
 *
 * This is a generator dressed as a test, because [XmlSearchStrings] is what
 * lets the real index be built on a plain JVM. It is skipped unless
 * `WM_SETTINGS_LINKS_OUT` names the file to write, which
 * `docs/scripts/extract_settings_links.sh` does. Rerun that script whenever
 * `SettingsSearch.kt` or a settings title changes.
 */
class SettingsLinksDump {

    @Test
    fun `write the index for the docs`() {
        val out = System.getenv("WM_SETTINGS_LINKS_OUT")
        assumeTrue("set WM_SETTINGS_LINKS_OUT to write the dump", !out.isNullOrBlank())

        val strings = XmlSearchStrings.forApp()
        val home = strings.getString(com.wasimaster.wmkeyboard.common.R.string.common_settings)
        val entries = settingsSearchIndex(strings).map { entry ->
            // A tool page is a destination too, but its own path ends with its
            // own name, unlike a section whose path stops above it.
            val isScreen = entry.weight == EntryWeight.SECTION || entry.weight == EntryWeight.TOOL
            val screens = entry.screenPath.filterNot { it == home }
                .let { if (entry.weight == EntryWeight.TOOL && it.lastOrNull() == entry.title) it.dropLast(1) else it }
            buildString {
                append("{\"title\":").append(json(entry.title))
                append(",\"name\":").append(json(entry.key.substringAfter('#')))
                append(",\"route\":").append(json(entry.route))
                append(",\"screens\":[").append(screens.joinToString(",") { json(it) }).append(']')
                append(",\"screen\":").append(if (isScreen) "true" else "false")
                append('}')
            }
        }
        val file = File(out!!)
        file.parentFile?.mkdirs()
        file.writeText(
            "[\n" + entries.joinToString(",\n") { "  $it" } + "\n]\n",
        )
        println("wrote ${entries.size} entries to ${file.absolutePath}")
    }

    private fun json(text: String): String = buildString {
        append('"')
        for (ch in text) {
            when (ch) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (ch < ' ') append(String.format("\\u%04x", ch.code)) else append(ch)
            }
        }
        append('"')
    }
}
