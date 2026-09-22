package com.wasimaster.wmkeyboard.core.settings.sink

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.wasimaster.wmkeyboard.core.util.requireInputStream
import com.wasimaster.wmkeyboard.core.util.requireOutputStream
import com.wasimaster.wmkeyboard.core.util.runCancellable
import java.io.BufferedOutputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A [BackupSink] over a folder the user picked with `ACTION_OPEN_DOCUMENT_TREE`.
 *
 * Whatever is behind that folder is somebody else's problem: Drive, Dropbox,
 * Nextcloud, OneDrive, the SD card. Anything with a `DocumentsProvider` works,
 * which is the whole reason this is the first sink — it reaches every cloud the
 * user already has, with no account of ours, no server of ours, and nothing a
 * GMS-free or F-Droid build cannot do.
 *
 * Written against [DocumentsContract] rather than `androidx.documentfile`.
 * `DocumentFile` is a thin wrapper that re-queries on every property read, and
 * the IME already talks to this API directly for pasted files, so the
 * dependency would buy nothing.
 *
 * **On atomicity.** The rest of the app installs a downloaded file by writing
 * `foo.part` and renaming it. SAF has [DocumentsContract.renameDocument], but a
 * provider only implements it when the document reports
 * `FLAG_SUPPORTS_RENAME`, and plenty of cloud providers do not. So the
 * guarantee here is weaker and differently shaped: a half-written backup is
 * always *detectable* (truncated JSON does not parse, a truncated ciphertext
 * fails its tag) and the caller must re-read what it wrote before it rotates
 * anything away. Never destroying the last good backup is the property that
 * actually matters; byte-level atomicity is not available to buy.
 */
class SafFolderSink(
    context: Context,
    private val treeUri: Uri,
) : BackupSink {

    private val appContext = context.applicationContext

    private val resolver: ContentResolver get() = appContext.contentResolver

    override val id: String get() = ID

    /**
     * The tree as a *document*, which is the form that can be queried and
     * written to. A tree URI on its own cannot.
     */
    private fun folderDoc(): Uri = DocumentsContract.buildDocumentUriUsingTree(
        treeUri,
        DocumentsContract.getTreeDocumentId(treeUri),
    )

    private fun childrenUri(): Uri = DocumentsContract.buildChildDocumentsUriUsingTree(
        treeUri,
        DocumentsContract.getTreeDocumentId(treeUri),
    )

    private fun docUri(entry: SinkEntry): Uri =
        DocumentsContract.buildDocumentUriUsingTree(treeUri, entry.id)

    override suspend fun readiness(): Result<Unit> = withContext(Dispatchers.IO) {
        runCancellable {
            // Two checks, because they are two different sentences to the user:
            // a lost grant means "pick the folder again", a missing target means
            // "the folder is gone or the card is out". Only the permission list
            // can tell them apart — a revoked grant throws SecurityException
            // from the query, which looks the same as a deleted folder.
            if (!grantHeld()) throw BackupSinkException(SinkError.PERMISSION_LOST)

            val present = try {
                resolver.query(
                    folderDoc(),
                    arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID),
                    null,
                    null,
                    null,
                )?.use { it.moveToFirst() } == true
            } catch (security: SecurityException) {
                throw BackupSinkException(SinkError.PERMISSION_LOST, security)
            } catch (io: IOException) {
                throw BackupSinkException(SinkError.TARGET_MISSING, io)
            } catch (illegal: IllegalArgumentException) {
                // A tree URI whose provider is no longer installed at all.
                throw BackupSinkException(SinkError.TARGET_MISSING, illegal)
            }
            if (!present) throw BackupSinkException(SinkError.TARGET_MISSING)
        }
    }

    override suspend fun write(
        name: String,
        mimeType: String,
        body: (OutputStream) -> Unit,
    ): Result<SinkEntry> = withContext(Dispatchers.IO) {
        runCancellable {
            readiness().getOrThrow()
            sweepStaleParts()

            // Create under the .part name first, then find out whether this
            // provider can rename. It cannot be asked in advance — rename
            // support is a property of the document, not of the folder — but
            // the document is empty at this point, so learning the answer the
            // hard way costs one create and one delete, not a transfer.
            var target = create(name + AutoBackupNaming.PART_SUFFIX, mimeType)
            val renameable = supportsRename(target)
            if (!renameable) {
                deleteQuietly(target)
                replaceExisting(name)
                target = create(name, mimeType)
            }

            val finished = try {
                resolver.requireOutputStream(target).use { raw ->
                    BufferedOutputStream(raw).use(body)
                }
                if (renameable) {
                    // Only once the new file is whole: the old one stays until
                    // there is something to take its place.
                    replaceExisting(name)
                    renameTo(target, name) ?: copyInto(target, name, mimeType)
                } else {
                    target
                }
            } catch (failure: Throwable) {
                // The partial is worse than nothing: it would sit in the folder
                // looking like a generation. Take it back out.
                deleteQuietly(target)
                throw failure.asSinkException()
            }

            describe(finished) ?: throw BackupSinkException(SinkError.IO)
        }
    }

    override suspend fun list(): Result<List<SinkEntry>> = withContext(Dispatchers.IO) {
        runCancellable {
            readiness().getOrThrow()
            children().filter { AutoBackupNaming.isListed(it.name) }
        }
    }

    override suspend fun read(entry: SinkEntry): Result<InputStream> =
        withContext(Dispatchers.IO) {
            runCancellable {
                try {
                    resolver.requireInputStream(docUri(entry))
                } catch (failure: Throwable) {
                    throw failure.asSinkException()
                }
            }
        }

    override suspend fun delete(entry: SinkEntry): Result<Unit> = withContext(Dispatchers.IO) {
        runCancellable { deleteOrThrow(docUri(entry)) }
    }

    /** Whether the tree grant this sink was built on is still held. */
    private fun grantHeld(): Boolean = runCatching {
        resolver.persistedUriPermissions.any {
            it.uri == treeUri && it.isReadPermission && it.isWritePermission
        }
    }.getOrDefault(false)

    private fun deleteOrThrow(uri: Uri) {
        try {
            DocumentsContract.deleteDocument(resolver, uri)
        } catch (_: FileNotFoundException) {
            // Already gone is the outcome rotation wanted. It re-runs after a
            // failure, so it has to not mind having half-succeeded before.
        } catch (failure: Throwable) {
            throw failure.asSinkException()
        }
    }

    /** For cleanup paths, where the failure being handled is the interesting one. */
    private fun deleteQuietly(uri: Uri) {
        runCatching { DocumentsContract.deleteDocument(resolver, uri) }
    }

    /**
     * The rename half of the `.part` install. A provider that advertised
     * `FLAG_SUPPORTS_RENAME` can still refuse, and the file is already fully
     * written by then — so a refusal leaves a good backup under the `.part`
     * name rather than failing the run. [AutoBackupNaming.isOurs] will not list
     * it and the next sweep will collect it, which is the right outcome: the
     * generation is lost, nothing else is.
     */
    /**
     * Deletes a file already called [name], so writing that name again
     * replaces it. Without this a provider renames the clash to
     * `name (1).ext`, and a file sync rewrites in place would pile up under
     * names nothing recognises. An automatic backup's name is unique to the
     * second and never gets here with anything to delete.
     */
    private fun replaceExisting(name: String) {
        children().filter { it.name == name }.forEach { entry -> deleteQuietly(docUri(entry)) }
    }

    /** The renamed document, or null when the provider refused after all. */
    private fun renameTo(uri: Uri, name: String): Uri? =
        runCatching { DocumentsContract.renameDocument(resolver, uri, name) }.getOrNull()

    /**
     * The fallback for a rename refused at the last step: copy the finished
     * `.part` into a new file under [name], then drop the `.part`. By now the
     * old file of that name may already be gone, so leaving the `.part` as it
     * was would leave nothing under the name at all.
     */
    private fun copyInto(part: Uri, name: String, mimeType: String): Uri {
        val target = create(name, mimeType)
        resolver.requireInputStream(part).use { input ->
            resolver.requireOutputStream(target).use { input.copyTo(it) }
        }
        deleteQuietly(part)
        return target
    }

    /**
     * Deletes our own half-written files older than [STALE_PART_AGE_MS].
     *
     * They can only come from a run that was killed between the create and the
     * rename. The age bound is there so a concurrent run — the scheduled job
     * and a "Back up now" press landing together — does not delete the file the
     * other one is still writing.
     */
    private fun sweepStaleParts() {
        val cutoff = System.currentTimeMillis() - STALE_PART_AGE_MS
        for (entry in runCatching { children() }.getOrDefault(emptyList())) {
            if (!AutoBackupNaming.isPart(entry.name)) continue
            if (entry.modifiedAtMs != 0L && entry.modifiedAtMs > cutoff) continue
            runCatching { DocumentsContract.deleteDocument(resolver, docUri(entry)) }
        }
    }

    /** Everything in the folder, ours or not. Callers filter. */
    private fun children(): List<SinkEntry> {
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
        val out = ArrayList<SinkEntry>()
        val cursor = try {
            resolver.query(childrenUri(), projection, null, null, null)
        } catch (security: SecurityException) {
            throw BackupSinkException(SinkError.PERMISSION_LOST, security)
        } catch (failure: Throwable) {
            throw failure.asSinkException()
        } ?: throw BackupSinkException(SinkError.TARGET_MISSING)

        cursor.use { rows ->
            fun column(key: String) = rows.getColumnIndex(key).takeIf { it >= 0 }
            val idColumn = column(DocumentsContract.Document.COLUMN_DOCUMENT_ID) ?: return@use
            val nameColumn = column(DocumentsContract.Document.COLUMN_DISPLAY_NAME) ?: return@use
            val sizeColumn = column(DocumentsContract.Document.COLUMN_SIZE)
            val modifiedColumn = column(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
            while (rows.moveToNext()) {
                val documentId = rows.getString(idColumn) ?: continue
                val displayName = rows.getString(nameColumn) ?: continue
                out += SinkEntry(
                    id = documentId,
                    name = displayName,
                    sizeBytes = sizeColumn?.takeIf { !rows.isNull(it) }?.let(rows::getLong) ?: -1L,
                    modifiedAtMs =
                    modifiedColumn?.takeIf { !rows.isNull(it) }?.let(rows::getLong) ?: 0L,
                )
            }
        }
        return out
    }

    private fun create(name: String, mimeType: String): Uri =
        try {
            DocumentsContract.createDocument(resolver, folderDoc(), mimeType, name)
                ?: throw BackupSinkException(SinkError.IO)
        } catch (failure: Throwable) {
            throw failure.asSinkException()
        }

    /**
     * Reads back what a document actually became.
     *
     * Never assume the requested name landed: providers sanitise names for the
     * filesystem underneath, append an extension they derive from the MIME
     * type, and turn a collision into `foo (1).json`.
     */
    private fun describe(uri: Uri): SinkEntry? {
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
        return runCatching {
            resolver.query(uri, projection, null, null, null)?.use { rows ->
                if (!rows.moveToFirst()) return@use null
                fun column(key: String) = rows.getColumnIndex(key).takeIf { it >= 0 }
                val documentId = column(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                    ?.let(rows::getString)
                    ?: DocumentsContract.getDocumentId(uri)
                val displayName = column(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                    ?.let(rows::getString)
                    ?: return@use null
                SinkEntry(
                    id = documentId,
                    name = displayName,
                    sizeBytes = column(DocumentsContract.Document.COLUMN_SIZE)
                        ?.takeIf { !rows.isNull(it) }?.let(rows::getLong) ?: -1L,
                    modifiedAtMs = column(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                        ?.takeIf { !rows.isNull(it) }?.let(rows::getLong) ?: 0L,
                )
            }
        }.getOrNull()
    }

    private fun supportsRename(uri: Uri): Boolean = runCatching {
        resolver.query(
            uri,
            arrayOf(DocumentsContract.Document.COLUMN_FLAGS),
            null,
            null,
            null,
        )?.use { rows ->
            if (!rows.moveToFirst()) return@use false
            val flags = rows.getColumnIndex(DocumentsContract.Document.COLUMN_FLAGS)
                .takeIf { it >= 0 }
                ?.let(rows::getLong)
                ?: return@use false
            flags and DocumentsContract.Document.FLAG_SUPPORTS_RENAME.toLong() != 0L
        } == true
    }.getOrDefault(false)

    /**
     * The reason behind a platform failure, so the UI has one sentence to say
     * and the runner knows whether trying again could ever help.
     */
    private fun Throwable.asSinkException(): BackupSinkException = when {
        this is BackupSinkException -> this
        this is SecurityException -> BackupSinkException(SinkError.PERMISSION_LOST, this)
        this is FileNotFoundException -> BackupSinkException(SinkError.TARGET_MISSING, this)
        isOutOfSpace() -> BackupSinkException(SinkError.OUT_OF_SPACE, this)
        else -> BackupSinkException(SinkError.IO, this)
    }

    /**
     * There is no typed out-of-space failure to catch: the platform surfaces it
     * as an `ErrnoException` wrapped in an `IOException`, and a cloud provider
     * that has hit a quota just says so in the message. The string check is the
     * only handle either of them offers.
     */
    private fun Throwable.isOutOfSpace(): Boolean {
        var cause: Throwable? = this
        while (cause != null) {
            val message = cause.message.orEmpty()
            if (message.contains("ENOSPC") ||
                message.contains("No space left", ignoreCase = true) ||
                message.contains("quota", ignoreCase = true)
            ) {
                return true
            }
            cause = cause.cause.takeIf { it !== cause }
        }
        return false
    }

    companion object {
        const val ID = "saf"

        /**
         * How long a `.part` file has to sit untouched before the next run
         * treats it as debris rather than as somebody else's work in progress.
         */
        private const val STALE_PART_AGE_MS = 30L * 60L * 1000L
    }
}
