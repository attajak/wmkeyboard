package com.wasimaster.wmkeyboard.core.settings

import java.security.SecureRandom
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * One place automatic backups go, and sync reads and writes.
 *
 * The user can have any number, of any type: two WebDAV servers, a Dropbox
 * and a folder on the SD card at once. Every enabled, configured location
 * gets every backup.
 *
 * All of a location's fields live together in one stored list rather than in
 * one preference per field, because the list has no fixed length. The list is
 * a single preference, [SettingsBackup.AUTO_BACKUP_LOCATIONS], named in
 * [SettingsBackup.SECRET_KEYS] because it holds passwords and refresh tokens.
 * Sync never carries it (see
 * [com.wasimaster.wmkeyboard.core.settings.sync.SyncKeys]): where this phone
 * backs up is this phone's business.
 */
data class BackupLocation(
    /** Eight hex characters, stable for the life of the location. */
    val id: String,
    val type: BackupDestination,
    /** What the user called it. Empty means the screen names it after [type]. */
    val name: String = "",
    /**
     * Paused locations keep their settings and get nothing. No longer offered
     * on the screen, where [backup] and the sync list say where each thing
     * goes; kept so a stored `false` still means what it meant.
     */
    val enabled: Boolean = true,
    /** Whether automatic backups go here. Ticked on the Automatic backup screen. */
    val backup: Boolean = true,
    /** [BackupDestination.FOLDER]: a persisted tree URI. See [AutoBackupSettings.folderUri]. */
    val folderUri: String = "",
    val webDavUrl: String = "",
    val webDavUser: String = "",
    val webDavPassword: String = "",
    val s3: S3Config = S3Config(),
    val ftp: FtpConfig = FtpConfig(),
    /** Dropbox or OneDrive refresh token. Empty means signed out. */
    val refreshToken: String = "",
) {

    /**
     * Whether this location has everything it needs to be tried. The same
     * cheap question [destinationConfigured] asks of the single destination
     * this replaced; see there.
     */
    val configured: Boolean
        get() = when (type) {
            BackupDestination.FOLDER -> folderUri.isNotEmpty()
            BackupDestination.WEBDAV -> webDavUrl.isNotEmpty() && webDavUser.isNotEmpty()
            BackupDestination.DRIVE -> true
            BackupDestination.S3 ->
                s3.bucket.isNotEmpty() && s3.accessKeyId.isNotEmpty() && s3.secretAccessKey.isNotEmpty()
            BackupDestination.DROPBOX, BackupDestination.ONEDRIVE -> refreshToken.isNotEmpty()
            BackupDestination.FTP -> ftp.host.isNotEmpty() && ftp.user.isNotEmpty()
        }

    /** Enabled and configured: what a run actually writes to. */
    val active: Boolean get() = enabled && configured

    companion object {

        private const val ID_BYTES = 4

        /**
         * The id of the location made from the old single destination. Fixed
         * rather than random: until the list is first saved it is rebuilt on
         * every read, and a new id each time would orphan its status.
         */
        const val LEGACY_ID = "00000001"

        fun newId(): String =
            ByteArray(ID_BYTES).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }

        private val json = Json { ignoreUnknownKeys = true }

        /** The stored list, or empty for anything that does not parse. */
        fun decodeList(raw: String?): List<BackupLocation> {
            if (raw.isNullOrBlank()) return emptyList()
            val array = runCatching { json.parseToJsonElement(raw).jsonArray }.getOrNull() ?: return emptyList()
            return array.mapNotNull { runCatching { decode(it.jsonObject) }.getOrNull() }
        }

        fun encodeList(locations: List<BackupLocation>): String =
            JsonArray(locations.map(::encode)).toString()

        private fun JsonObject.str(key: String): String = this[key]?.jsonPrimitive?.contentOrNull.orEmpty()

        private fun decode(o: JsonObject): BackupLocation? {
            val type = BackupDestination.entries.firstOrNull { it.id == o.str("type") } ?: return null
            val id = o.str("id").ifEmpty { return null }
            val s3 = o["s3"]?.jsonObject
            val ftp = o["ftp"]?.jsonObject
            return BackupLocation(
                id = id,
                type = type,
                name = o.str("name"),
                enabled = o["enabled"]?.jsonPrimitive?.booleanOrNull ?: true,
                backup = o["backup"]?.jsonPrimitive?.booleanOrNull ?: true,
                folderUri = o.str("folderUri"),
                webDavUrl = o.str("webDavUrl"),
                webDavUser = o.str("webDavUser"),
                webDavPassword = o.str("webDavPassword"),
                s3 = if (s3 == null) {
                    S3Config()
                } else {
                    S3Config(
                        endpoint = s3.str("endpoint"),
                        region = s3.str("region").ifEmpty { S3Config().region },
                        bucket = s3.str("bucket"),
                        prefix = s3.str("prefix"),
                        accessKeyId = s3.str("accessKeyId"),
                        secretAccessKey = s3.str("secretAccessKey"),
                        pathStyle = s3["pathStyle"]?.jsonPrimitive?.booleanOrNull ?: false,
                    )
                },
                ftp = if (ftp == null) {
                    FtpConfig()
                } else {
                    FtpConfig(
                        host = ftp.str("host"),
                        port = ftp["port"]?.jsonPrimitive?.intOrNull ?: FtpConfig().port,
                        user = ftp.str("user"),
                        password = ftp.str("password"),
                        path = ftp.str("path"),
                        secure = ftp["secure"]?.jsonPrimitive?.booleanOrNull ?: true,
                    )
                },
                refreshToken = o.str("refreshToken"),
            )
        }

        private fun encode(l: BackupLocation): JsonObject = buildJsonObject {
            put("id", JsonPrimitive(l.id))
            put("type", JsonPrimitive(l.type.id))
            if (l.name.isNotEmpty()) put("name", JsonPrimitive(l.name))
            put("enabled", JsonPrimitive(l.enabled))
            put("backup", JsonPrimitive(l.backup))
            when (l.type) {
                BackupDestination.FOLDER -> put("folderUri", JsonPrimitive(l.folderUri))
                BackupDestination.WEBDAV -> {
                    put("webDavUrl", JsonPrimitive(l.webDavUrl))
                    put("webDavUser", JsonPrimitive(l.webDavUser))
                    put("webDavPassword", JsonPrimitive(l.webDavPassword))
                }
                BackupDestination.S3 -> put(
                    "s3",
                    buildJsonObject {
                        put("endpoint", JsonPrimitive(l.s3.endpoint))
                        put("region", JsonPrimitive(l.s3.region))
                        put("bucket", JsonPrimitive(l.s3.bucket))
                        put("prefix", JsonPrimitive(l.s3.prefix))
                        put("accessKeyId", JsonPrimitive(l.s3.accessKeyId))
                        put("secretAccessKey", JsonPrimitive(l.s3.secretAccessKey))
                        put("pathStyle", JsonPrimitive(l.s3.pathStyle))
                    },
                )
                BackupDestination.FTP -> put(
                    "ftp",
                    buildJsonObject {
                        put("host", JsonPrimitive(l.ftp.host))
                        put("port", JsonPrimitive(l.ftp.port))
                        put("user", JsonPrimitive(l.ftp.user))
                        put("password", JsonPrimitive(l.ftp.password))
                        put("path", JsonPrimitive(l.ftp.path))
                        put("secure", JsonPrimitive(l.ftp.secure))
                    },
                )
                BackupDestination.DROPBOX, BackupDestination.ONEDRIVE ->
                    put("refreshToken", JsonPrimitive(l.refreshToken))
                BackupDestination.DRIVE -> Unit
            }
        }

        /**
         * The location the single-destination settings described, for the
         * one-time move to a list. Null when that destination was never set
         * up, so an untouched install starts with no locations rather than
         * an empty folder nobody chose.
         */
        fun fromLegacy(auto: AutoBackupSettings): BackupLocation? {
            if (!auto.destinationConfigured) return null
            return BackupLocation(
                id = LEGACY_ID,
                type = auto.destination,
                folderUri = auto.folderUri,
                webDavUrl = auto.webDavUrl,
                webDavUser = auto.webDavUser,
                webDavPassword = auto.webDavPassword,
                s3 = auto.s3,
                ftp = auto.ftp,
                refreshToken = when (auto.destination) {
                    BackupDestination.DROPBOX -> auto.dropboxRefreshToken
                    BackupDestination.ONEDRIVE -> auto.oneDriveRefreshToken
                    else -> ""
                },
            )
        }
    }
}

/**
 * How the last backup and the last sync went at one location. Per install,
 * never exported: another phone's run record means nothing here.
 */
data class LocationStatus(
    val backupAtMs: Long = 0L,
    /** A `SinkError` name, or empty. */
    val backupError: String = "",
    val syncAtMs: Long = 0L,
    val syncError: String = "",
) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun decodeMap(raw: String?): Map<String, LocationStatus> {
            if (raw.isNullOrBlank()) return emptyMap()
            val root = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return emptyMap()
            return root.mapValues { (_, v) ->
                val o = v.jsonObject
                LocationStatus(
                    backupAtMs = o["b"]?.jsonPrimitive?.longOrNull ?: 0L,
                    backupError = o["be"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    syncAtMs = o["s"]?.jsonPrimitive?.longOrNull ?: 0L,
                    syncError = o["se"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                )
            }
        }

        fun encodeMap(map: Map<String, LocationStatus>): String = JsonObject(
            map.mapValues { (_, s) ->
                buildJsonObject {
                    put("b", JsonPrimitive(s.backupAtMs))
                    put("be", JsonPrimitive(s.backupError))
                    put("s", JsonPrimitive(s.syncAtMs))
                    put("se", JsonPrimitive(s.syncError))
                }
            },
        ).toString()
    }
}
