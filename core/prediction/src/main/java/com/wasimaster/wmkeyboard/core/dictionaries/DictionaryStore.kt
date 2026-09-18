package com.wasimaster.wmkeyboard.core.dictionaries

import android.content.Context
import com.wasimaster.wmkeyboard.config.BuildConfig
import com.wasimaster.wmkeyboard.core.prediction.PackedTrieCodec
import java.io.File
import java.io.IOException

/**
 * File layout for `.wmdict` binary dictionaries under `filesDir/dict/`:
 *
 *  - `dict/bundled/<name>.wmdict` — the compiled bundled lists (en, bn),
 *    inflated once out of the APK so they can be memory-mapped ([ensureBundled];
 *    AAPT-deflated assets cannot be mapped in place). Re-extracted when the app
 *    updates, tracked by a `.version` marker holding the versionCode.
 *  - `dict/<langId>/main.wmdict` — a downloaded wordlist for that language,
 *    with `main.wmdict.part` while the download/convert pipeline runs. The
 *    atomic-rename contract means a final file exists only if it was written
 *    completely, so presence == valid — same as `LocalLlmStore`.
 *
 * Deliberately separate from `filesDir/dictionaries/` (user-imported custom
 * `.txt` lists, [com.wasimaster.wmkeyboard.core.prediction.CustomDictionaries])
 * so that feature's glob-and-parse semantics stay untouched.
 */
object DictionaryStore {

    private const val FILE_NAME = "main.wmdict"
    private const val BUNDLED_DIR = "bundled"

    private fun root(filesDir: File) = File(filesDir, "dict")

    private fun bundledDir(filesDir: File) = File(root(filesDir), BUNDLED_DIR)

    fun downloadedFile(filesDir: File, langId: String): File =
        File(File(root(filesDir), langId), FILE_NAME)

    fun partFile(filesDir: File, langId: String): File =
        File(File(root(filesDir), langId), "$FILE_NAME.part")

    /**
     * Whether this build can actually read [langId]'s downloaded dictionary.
     *
     * Deliberately more than a file-existence test. A `.wmdict` written by an
     * older format still exists on disk after an update, but nothing can map
     * it: the engine would fall back to the bundled list while Settings kept
     * claiming the language was downloaded — and showed it as holding zero
     * words, because reading its header throws. Judging the file by whether it
     * parses means a stale one reads as absent, so the UI offers it again and
     * [sweepUnreadable] can reclaim the space.
     */
    fun isDownloaded(filesDir: File, langId: String): Boolean =
        PackedTrieCodec.isReadable(downloadedFile(filesDir, langId))

    /**
     * Deletes every downloaded dictionary this build cannot read, and returns
     * the language ids it removed. Called once at startup: a format change
     * strands the old files otherwise, and they are the largest thing the app
     * puts on disk.
     */
    fun sweepUnreadable(filesDir: File): List<String> =
        root(filesDir).listFiles { file -> file.isDirectory && file.name != BUNDLED_DIR }
            .orEmpty()
            .filter { dir ->
                val main = File(dir, FILE_NAME)
                main.isFile && !PackedTrieCodec.isReadable(main)
            }
            .map { dir -> dir.name.also { delete(filesDir, it) } }
            .sorted()

    /**
     * Deletes [langId]'s downloaded word list and nothing else. The folder is
     * shared with the language's word-pair data (`ngrams.wmng`), which is its
     * own item on the language screen with its own Delete.
     */
    fun delete(filesDir: File, langId: String) {
        if (langId == BUNDLED_DIR) return
        val dir = File(root(filesDir), langId)
        File(dir, FILE_NAME).delete()
        File(dir, "$FILE_NAME.part").delete()
        File(dir, "source").delete()
    }

    /**
     * Which catalog entry a language's downloaded dictionary came from. Only
     * matters where several entries feed one language (Portuguese Europe vs
     * Brazil): the UI shows "downloaded" on the matching row alone.
     */
    fun sourceEntryId(filesDir: File, langId: String): String? =
        runCatching { File(File(root(filesDir), langId), "source").readText().trim() }
            .getOrNull()
            ?.takeIf { it.isNotEmpty() && isDownloaded(filesDir, langId) }

    fun writeSourceEntryId(filesDir: File, langId: String, entryId: String) {
        runCatching { File(File(root(filesDir), langId), "source").writeText(entryId) }
    }

    /**
     * Removes the bundled copies an older install inflated into
     * credential-encrypted storage, now that [ensureBundled] extracts into the
     * device-protected area instead. Pure housekeeping — a few megabytes — and
     * a no-op on a fresh install.
     */
    fun deleteLegacyBundled(filesDir: File) {
        runCatching { bundledDir(filesDir).deleteRecursively() }
    }

    /** Language ids that have a completed downloaded dictionary on disk. */
    fun downloadedLanguageIds(filesDir: File): List<String> =
        root(filesDir).listFiles { file -> file.isDirectory && file.name != BUNDLED_DIR }
            .orEmpty()
            .filter { PackedTrieCodec.isReadable(File(it, FILE_NAME)) }
            .map { it.name }
            .sorted()

    /**
     * A monotonically-changing token summarising which downloaded dictionaries
     * are on disk (and which bytes). The service compares it against the token
     * it last loaded from and rebuilds its word sources only when it differs —
     * so a download finished in Settings goes live on the next field focus
     * without re-checking files on every keypress.
     */
    fun stateToken(filesDir: File): Int =
        downloadedLanguageIds(filesDir).fold(0) { acc, langId ->
            val file = downloadedFile(filesDir, langId)
            var h = acc * 31 + langId.hashCode()
            h = h * 31 + file.length().toInt()
            h * 31 + file.lastModified().toInt()
        }

    /**
     * Guarantees the bundled dictionary [baseName] (e.g. `"en"`) exists as a
     * plain file and returns it, inflating `assets/dictionaries/<baseName>.wmdict`
     * on first run or after an app update. Returns null if the asset is missing
     * or the copy fails (disk full) — callers degrade to an empty dictionary.
     *
     * The IME passes a *device-protected* context here so that one extracted
     * copy serves both a normal run and a direct boot, where credential
     * encrypted storage does not exist yet. These bytes come out of the APK, so
     * nothing of the user's is exposed by living outside their encryption.
     */
    fun ensureBundled(context: Context, baseName: String): File? {
        val dir = bundledDir(context.filesDir)
        if (!dir.isDirectory && !dir.mkdirs()) return null
        val marker = File(dir, ".version")
        val version = BuildConfig.VERSION_CODE.toString()
        val stale = runCatching { marker.readText() }.getOrNull() != version
        if (stale) {
            // Formats or wordlists may have changed with the app; drop every
            // extracted copy so each is re-inflated from the new APK.
            dir.listFiles { file -> file.name.endsWith(".wmdict") }?.forEach { it.delete() }
            runCatching { marker.writeText(version) }.getOrElse { return null }
        }
        val out = File(dir, "$baseName.wmdict")
        if (out.isFile) return out
        return try {
            val tmp = File(dir, "$baseName.wmdict.tmp")
            context.assets.open("dictionaries/$baseName.wmdict").use { input ->
                tmp.outputStream().use { input.copyTo(it) }
            }
            if (tmp.renameTo(out)) out else throw IOException("rename failed")
        } catch (_: IOException) {
            null
        }
    }
}
