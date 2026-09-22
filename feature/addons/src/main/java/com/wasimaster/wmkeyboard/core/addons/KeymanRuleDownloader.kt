package com.wasimaster.wmkeyboard.core.addons

import com.wasimaster.wmkeyboard.core.netlog.NetSource
import com.wasimaster.wmkeyboard.core.netlog.NetLog
import android.content.Context
import com.wasimaster.wmkeyboard.core.endpoints.ServiceEndpoint
import com.wasimaster.wmkeyboard.core.endpoints.ServiceEndpoints
import com.wasimaster.wmkeyboard.core.keyman.KeymanFault
import com.wasimaster.wmkeyboard.core.keyman.KeymanPackage
import com.wasimaster.wmkeyboard.core.keyman.KeymanResult
import com.wasimaster.wmkeyboard.core.keyman.KeymanRuleStore
import com.wasimaster.wmkeyboard.core.keyman.KmxParser
import com.wasimaster.wmkeyboard.core.tools.ToolHttp
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Fetches a Keyman keyboard's rules so its converted layout stops typing its
 * key caps and starts typing what its author wrote.
 *
 * The grids ship in the APK; the rules do not. They are a few kilobytes each
 * across 862 keyboards, almost nobody enables more than a couple, and they
 * change upstream on their own schedule. Downloading the two you use beats
 * bundling all of them and shipping a new APK when one is fixed.
 *
 * ## Why this is not in `:core:keyman`
 *
 * That module is a parser and an interpreter with no Android dependencies worth
 * the name, and no Compose at all. [ToolHttp] lives in `:core:tools`, which
 * pulls Compose in. Putting the fetch here keeps the engine module testable on
 * the JVM without a UI toolkit on its test classpath.
 *
 * ## Trust
 *
 * The bytes come off the network and go through a ZIP reader, so every step is
 * bounded and checked:
 *
 * - HTTPS only, and only the two Keyman hosts. A redirect to anywhere else is
 *   refused rather than followed, which is why [ToolHttp.download] is handed a
 *   URL this object built rather than one from the response.
 * - The archive is read by [KeymanPackage], which compares entry names rather
 *   than using them as paths and caps what it reads as it reads it, so `../` has
 *   nothing to act on and a zip bomb runs out of budget instead of disk.
 * - The result must parse as a keyboard before it is installed. A truncated or
 *   hostile `.kmx` that reached the rules directory would be loaded on every
 *   keystroke.
 */
object KeymanRuleDownloader {

    sealed interface Outcome {
        /** Rules are on disk and the engine can use them. */
        data class Installed(val version: String) : Outcome

        /** Already present at this version; nothing was fetched. */
        data class AlreadyCurrent(val version: String) : Outcome

        /** Upstream has no rules for this keyboard. Not an error. */
        data object NotAvailable : Outcome

        /**
         * [message] is already in the user's language when the failure came off
         * the network, and null when it came from the file itself. Carrying it
         * is what keeps the underlying exception from being swallowed.
         */
        data class Failed(val fault: KeymanFault, val message: String? = null) : Outcome
    }

    /**
     * Downloads and installs the rules for [keyboardId].
     *
     * Runs on [Dispatchers.IO]. Safe to call for a keyboard that already has
     * rules: it asks upstream for the current version first and does nothing
     * when they match.
     */
    suspend fun fetch(
        context: Context,
        keyboardId: String,
        force: Boolean = false,
        onProgress: ((Long, Long) -> Unit)? = null,
    ): Outcome = withContext(Dispatchers.IO) {
        if (!isPlausibleId(keyboardId)) return@withContext Outcome.Failed(KeymanFault.BAD_MAGIC)
        val store = KeymanRuleStore(context)
        val target = store.ruleFile(keyboardId)
            ?: return@withContext Outcome.Failed(KeymanFault.TRUNCATED)

        val meta = runCatching { keyboardMeta(keyboardId) }.getOrNull()
            ?: return@withContext Outcome.NotAvailable
        if (meta.version.isEmpty()) return@withContext Outcome.NotAvailable
        // The bundled grids all come from the MIT release tree, so this should
        // never fire for them. It is here for everything else: the upstream
        // repository also holds freeware keyboards whose terms do not let us
        // redistribute or repackage, and an id is just a string in a URL.
        if (meta.license != LICENSE_MIT) return@withContext Outcome.NotAvailable
        if (!force && target.isFile && store.installedVersion(keyboardId) == meta.version) {
            return@withContext Outcome.AlreadyCurrent(meta.version)
        }

        val scratch = File(context.cacheDir, "keyman-rules").apply { mkdirs() }
        val packageFile = File(scratch, "$keyboardId.kmp")
        try {
            ToolHttp.download(
                url = packageUrl(keyboardId, meta.version, meta.packageFilename),
                target = packageFile,
                maxBytes = MAX_PACKAGE_BYTES,
                onProgress = onProgress,
                source = NetSource.KEYMAN,
                route = NetLog.pathOf(packageUrl(keyboardId, meta.version, meta.packageFilename)),
            )
            val rules = packageFile.inputStream().use { KeymanPackage.rulesFrom(it, keyboardId) }
                ?: return@withContext Outcome.Failed(KeymanFault.TRUNCATED)

            // Parse before installing. A file that reached the rules directory
            // is opened on the typing path, where a failure is a dead keyboard
            // rather than a message.
            when (val parsed = KmxParser.parse(rules)) {
                is KeymanResult.Failure -> return@withContext Outcome.Failed(parsed.fault)
                is KeymanResult.Success -> Unit
            }

            target.parentFile?.mkdirs()
            target.writeBytes(rules)
            store.writeInstalledVersion(keyboardId, meta.version)
            store.invalidate(keyboardId)
            Outcome.Installed(meta.version)
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Cancellation is the caller leaving the screen, not a failure, and
            // swallowing it would leave the coroutine looking like it finished.
            throw e
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            // Everything the network and the filesystem can raise, reported as
            // one outcome: the row says "try again" either way, and a keyboard
            // must not crash because a download went wrong. The reason is
            // carried rather than discarded, worded the way the addon
            // downloader words its own failures.
            Outcome.Failed(KeymanFault.TRUNCATED, ToolHttp.friendlyMessage(context, e))
        } finally {
            packageFile.delete()
        }
    }

    /** Removes downloaded rules, so the layout goes back to typing its caps. */
    fun remove(context: Context, keyboardId: String): Boolean {
        val store = KeymanRuleStore(context)
        val file = store.ruleFile(keyboardId) ?: return false
        store.invalidate(keyboardId)
        store.clearInstalledVersion(keyboardId)
        return file.delete()
    }

    private data class Meta(
        val version: String,
        val packageFilename: String,
        val license: String,
    )

    /**
     * Asks upstream what the current version is.
     *
     * The version is not baked into the app on purpose. A keyboard's rules are
     * fixed upstream on their own schedule, and a version frozen at build time
     * would pin every user to whatever was current the day we ran the pipeline.
     */
    private fun keyboardMeta(keyboardId: String): Meta {
        val body = ToolHttp.get(
            "${ServiceEndpoints.base(ServiceEndpoint.KEYMAN_API)}/keyboard/$keyboardId",
            source = NetSource.KEYMAN,
            route = "/keyboard/$keyboardId",
        )
        val root = json.parseToJsonElement(body) as? JsonObject ?: return Meta("", "", "")
        val version = root["version"]?.jsonPrimitive?.contentOrNull().orEmpty()
        val packageFilename = root["packageFilename"]?.jsonPrimitive?.contentOrNull()
            ?: "$keyboardId.kmp"
        val license = root["license"]?.jsonPrimitive?.contentOrNull().orEmpty()
        return Meta(version, packageFilename.substringAfterLast('/'), license.lowercase())
    }

    private fun packageUrl(keyboardId: String, version: String, packageFilename: String): String =
        "${ServiceEndpoints.base(ServiceEndpoint.KEYMAN_DOWNLOADS)}/keyboards/$keyboardId/$version/$packageFilename"

    /**
     * Whether an id could name a keyboard, checked before it reaches a URL or a
     * file name. Upstream ids are lowercase alphanumerics with underscores.
     */
    private fun isPlausibleId(id: String): Boolean =
        id.isNotEmpty() && id.length <= MAX_ID_LENGTH &&
            id.all { it.isDigit() || it in 'a'..'z' || it == '_' }

    private fun kotlinx.serialization.json.JsonPrimitive.contentOrNull(): String? =
        if (isString || content.isNotEmpty()) content else null

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    // Metadata comes from api.keyman.com and packages from downloads.keyman.com,
    // unless the F-Droid build has either pointed at a mirror: ServiceEndpoint
    // KEYMAN_API and KEYMAN_DOWNLOADS.

    /**
     * The largest package worth fetching. The biggest in the corpus is a few
     * megabytes, almost all of it fonts we do not use.
     */
    private const val MAX_PACKAGE_BYTES = 32L * 1024 * 1024

    /** The only licence whose terms let us fetch and store a keyboard's rules. */
    private const val LICENSE_MIT = "mit"

    private const val MAX_ID_LENGTH = 64
}
