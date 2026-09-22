package com.wasimaster.wmkeyboard.docshots

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.wasimaster.wmkeyboard.app.SettingsRoutes
import com.wasimaster.wmkeyboard.app.settingsSearchIndex
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Writes every searchable row as `route <tab> resource name <tab> title` and
 * every linkable route, for writing [SHOTS] against. Run it alone with
 * `-Pwmkb.docShots.only=index`.
 */
@RunWith(AndroidJUnit4::class)
class DumpIndex {
    @Test
    fun index() {
        if (System.getProperty("wmkb.docShots.only")?.contains("index") != true) return
        val app = ApplicationProvider.getApplicationContext<Application>()
        val out = File(System.getProperty("wmkb.docShots.out") ?: "build/docshots").apply { mkdirs() }
        File(out, "index.tsv").writeText(
            settingsSearchIndex(app.resources).joinToString("\n") { e ->
                val name = if (e.titleRes != 0) app.resources.getResourceEntryName(e.titleRes) else "-"
                "${e.route}\t$name\t${e.weight}\t${e.title}\t${e.screenPath.joinToString(" › ")}"
            },
        )
        File(out, "routes.txt").writeText(SettingsRoutes.all.joinToString("\n"))
    }
}
