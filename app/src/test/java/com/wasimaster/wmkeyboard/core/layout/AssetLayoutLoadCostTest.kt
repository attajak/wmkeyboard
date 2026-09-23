package com.wasimaster.wmkeyboard.core.layout

import java.io.File
import kotlin.system.measureTimeMillis
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What it costs to read every shipped layout, now that there are 1,256 of them.
 *
 * The Keyman conversion took the asset count from 394 to 1,256, and it has
 * grown since. `AssetLayouts.load` used to decode all of them in one pass at
 * every process start, which cost seconds on a slow phone and held every grid
 * on the heap (#296); it reads a build-generated index now and parses a layout
 * the first time something asks for it. This is the cost that change avoids,
 * and the bound on anything that ever walks the whole set again.
 *
 * ## Why the assertion is loose
 *
 * This measures a desktop JVM reading from a page cache, which is not a phone
 * reading from an APK's compressed asset table. The number it prints is a
 * relative signal, useful for catching an order-of-magnitude regression in the
 * decoder, and nothing more. A tight bound here would fail on a loaded CI box
 * and teach everyone to ignore it.
 */
class AssetLayoutLoadCostTest {

    private val dir = File("src/main/assets/layouts")

    @Test
    fun `decoding every shipped layout stays within a sane budget`() {
        val files = dir.listFiles { f -> f.name.endsWith(".wmlayout.json") }.orEmpty()
        assertTrue("no asset layouts found", files.size > 1_000)

        // One untimed pass so the comparison is decode cost, not disk latency.
        for (file in files) LayoutFile.decode(file.readText())

        var decoded = 0
        val millis = measureTimeMillis {
            for (file in files) {
                if (LayoutFile.decode(file.readText()) != null) decoded++
            }
        }
        val keymanCount = files.count { it.name.startsWith("kmn_") }
        println(
            "asset layout load: ${files.size} files ($keymanCount from Keyman), " +
                "$decoded decoded in ${millis}ms " +
                "(${"%.2f".format(millis.toDouble() / files.size)}ms each)",
        )

        assertTrue("$decoded of ${files.size} layouts failed to decode", decoded == files.size)
        assertTrue(
            "decoding ${files.size} layouts took ${millis}ms, which is far past anything " +
                "this decoder should need; something in the parse path regressed",
            millis < BUDGET_MS,
        )
    }

    /**
     * The Keyman grids are bigger than ours: more layers, more keys, longpress
     * on nearly every key. Worth knowing by how much, since it is the multiplier
     * on every future decision about bundling more of them.
     */
    @Test
    fun `report the size split between our layouts and the converted ones`() {
        val files = dir.listFiles { f -> f.name.endsWith(".wmlayout.json") }.orEmpty()
        val (keyman, ours) = files.partition { it.name.startsWith("kmn_") }
        fun mean(group: List<File>) = if (group.isEmpty()) 0L else group.sumOf { it.length() } / group.size
        println(
            "layout bytes: ours ${ours.size} files avg ${mean(ours)}B, " +
                "Keyman ${keyman.size} files avg ${mean(keyman)}B, " +
                "total ${files.sumOf { it.length() } / 1024}KiB uncompressed",
        )
        assertTrue("expected both groups to be present", ours.isNotEmpty() && keyman.isNotEmpty())
    }

    private companion object {
        /**
         * Deliberately an order of magnitude above what this actually takes. It
         * is a tripwire for a decoder regression, not a performance target.
         */
        const val BUDGET_MS = 30_000L
    }
}
