package com.wasimaster.wmkeyboard.core.settings

import android.content.Context
import android.os.Build
import android.provider.Settings
import java.io.File
import java.security.SecureRandom

/**
 * Which installation an automatic backup came from.
 *
 * One destination can hold backups from several phones, or from the same
 * phone before and after a reset, and each of them rotates its own backups.
 * Without an owner in the name, a second phone would count the first phone's
 * backups as its own generations and delete them.
 *
 * The id is a file in `noBackupFilesDir` rather than a setting, and that is
 * the whole design. A setting would travel in an exported bundle and in
 * Android's own backup, and a phone restored from either would take over the
 * old phone's backups. A file there is never exported, never copied by
 * Android's backup or device transfer, survives app updates, and is gone
 * after "Clear storage" or a reinstall, which is when a new id is right.
 */
object BackupInstall {

    private const val FILE = "backup_install_id"
    private const val ID_BYTES = 4

    /** Eight lowercase hex characters, the shape AutoBackupNaming parses. */
    private val ID_SHAPE = Regex("[0-9a-f]{8}")

    @Volatile
    private var cached: String? = null

    /** This installation's id, made on first use. */
    @Synchronized
    fun id(context: Context): String {
        cached?.let { return it }
        val file = File(context.noBackupFilesDir, FILE)
        val existing = runCatching { file.readText().trim() }.getOrNull()
        val id = if (existing != null && ID_SHAPE.matches(existing)) {
            existing
        } else {
            val bytes = ByteArray(ID_BYTES).also { SecureRandom().nextBytes(it) }
            bytes.joinToString("") { "%02x".format(it) }.also { fresh ->
                runCatching { file.writeText(fresh) }
            }
        }
        cached = id
        return id
    }

    /**
     * The name the user gave this phone, or its model when they gave none.
     *
     * `Settings.Global.DEVICE_NAME` is what the phone shows in Bluetooth and
     * in "About phone", so it is the name a person recognises in a list of
     * backups from several devices.
     */
    fun deviceLabel(context: Context): String {
        val named = runCatching {
            // The constant is API 25; the key it names is older than that.
            Settings.Global.getString(context.contentResolver, "device_name")
        }.getOrNull()?.trim()
        return named?.takeIf { it.isNotEmpty() } ?: Build.MODEL.orEmpty()
    }
}
