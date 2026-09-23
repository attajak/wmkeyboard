package com.wasimaster.wmkeyboard.core.settings

/**
 * The connection details of the destinations added after [S3Config] and
 * [FtpConfig], one class each. They live only inside a [BackupLocation]: the
 * single-destination settings those two also serve never had these kinds.
 *
 * Every password, key and token here is covered by the location list being in
 * [SettingsBackup.SECRET_KEYS] as a whole.
 */

/** An SFTP server: SSH, with the SFTP subsystem. */
data class SftpConfig(
    val host: String = "",
    val port: Int = 22,
    val user: String = "",
    /** Empty when [privateKey] does the signing in. */
    val password: String = "",
    /**
     * A private key in OpenSSH or PEM form, pasted whole. Tried before the
     * password when both are there, which is what `ssh` does too.
     */
    val privateKey: String = "",
    /** Unlocks [privateKey] when it is encrypted. */
    val keyPassphrase: String = "",
    /** Directory to write into. Relative paths start where the login lands. */
    val path: String = "",
    /**
     * The server's host key the first connection saw, as
     * `<algorithm> <base64>`. Empty until then. A later connection that is
     * shown a different key refuses, because that is exactly what a machine in
     * the middle looks like.
     */
    val hostKey: String = "",
    /**
     * Also offers SHA-1 signatures, SHA-1 MACs and the 2048-bit SHA-1 group,
     * which OpenSSH itself has turned off. Some old NAS firmware speaks nothing
     * newer. Off by default.
     */
    val legacyAlgorithms: Boolean = false,
) {
    val configured: Boolean
        get() = host.isNotEmpty() && user.isNotEmpty() && (password.isNotEmpty() || privateKey.isNotEmpty())
}

/** A Windows share, or a Samba one: SMB 2 and 3. */
data class SmbConfig(
    val host: String = "",
    val port: Int = 445,
    /** The share name alone, `backups` for `\\nas\backups`. */
    val share: String = "",
    /** Folder inside the share, with either slash. Empty is the share's root. */
    val path: String = "",
    /** The Windows domain or workgroup. Usually empty. */
    val domain: String = "",
    val user: String = "",
    val password: String = "",
    /**
     * Requires SMB 3 encryption of everything after the sign-in, and refuses a
     * server that cannot do it. On by default, the same stance as TLS for FTP:
     * without it the backup crosses the network as plain bytes. Old servers
     * that stop at SMB 2 need it off.
     */
    val encrypt: Boolean = true,
) {
    val configured: Boolean
        get() = host.isNotEmpty() && share.isNotEmpty() && user.isNotEmpty()
}

/** Which Git host API a [GitConfig] speaks. */
enum class GitProvider(
    /** Stable on disk. */
    val id: String,
    /** The public service, used when [GitConfig.server] is empty. */
    val defaultServer: String,
) {
    GITHUB("github", "https://github.com"),
    GITLAB("gitlab", "https://gitlab.com"),

    /** Gitea and Forgejo, which share one API. Codeberg is Forgejo. */
    GITEA("gitea", "https://codeberg.org"),
    ;

    companion object {
        fun of(id: String): GitProvider = entries.firstOrNull { it.id == id } ?: GITHUB
    }
}

/**
 * A folder in a Git repository on GitHub, GitLab, Gitea or Forgejo, written
 * through the host's REST API. Every backup is a commit; rotation is a commit
 * that deletes a file, which the history keeps.
 */
data class GitConfig(
    val provider: GitProvider = GitProvider.GITHUB,
    /**
     * The web address of the host, `https://git.example.com`. Empty means the
     * public service of [provider]. The API address is derived from it.
     */
    val server: String = "",
    /** `owner/name`. On GitLab a project in a subgroup is `group/sub/name`. */
    val repository: String = "",
    /** Empty means the repository's default branch. */
    val branch: String = "",
    /** Folder inside the repository. Empty is the top. */
    val path: String = "",
    /** A token that may write repository contents, and nothing more. */
    val token: String = "",
    /** Commit author. Empty uses the account the token belongs to. */
    val authorName: String = "",
    val authorEmail: String = "",
    /**
     * The commit message, with `{action}` (Add, Update or Delete), `{file}`,
     * `{device}` and `{date}` replaced. Empty means [DEFAULT_MESSAGE].
     */
    val message: String = "",
    /** Appends `[skip ci]`, so a backup never starts the repository's CI. */
    val skipCi: Boolean = true,
    /**
     * Allows a public repository. Off, the sink refuses one: a backup holds the
     * words you type, and a public repository hands them to everybody,
     * history included.
     */
    val allowPublic: Boolean = false,
) {
    val configured: Boolean get() = repository.contains('/') && token.isNotEmpty()

    companion object {
        const val DEFAULT_MESSAGE = "{action} {file}"
    }
}

/** How an [ImapConfig] connection is protected. */
enum class ImapSecurity(val id: String, val defaultPort: Int) {
    /** TLS from the first byte, port 993. */
    TLS("tls", 993),

    /** Plain first, upgraded by `STARTTLS` before the login. Port 143. */
    STARTTLS("starttls", 143),

    /** Nothing. The password crosses as text; the screen says so. */
    NONE("none", 143),
    ;

    companion object {
        fun of(id: String): ImapSecurity = entries.firstOrNull { it.id == id } ?: TLS
    }
}

/**
 * A mailbox folder on an IMAP server. Each backup is one message whose body
 * is the file; rotation deletes messages. A mail account the user already has
 * becomes a backup location with no new service at all.
 */
data class ImapConfig(
    val host: String = "",
    val port: Int = ImapSecurity.TLS.defaultPort,
    val security: ImapSecurity = ImapSecurity.TLS,
    val user: String = "",
    /** Most services want an app password here, not the account password. */
    val password: String = "",
    /** Created on first use. Its own folder, because rotation expunges it. */
    val mailbox: String = DEFAULT_MAILBOX,
) {
    val configured: Boolean get() = host.isNotEmpty() && user.isNotEmpty() && password.isNotEmpty()

    companion object {
        const val DEFAULT_MAILBOX = "WM Keyboard backups"
    }
}

/** Which part of Google Drive a Drive location writes to. */
enum class DriveSpace(
    /** Stable on disk. */
    val id: String,
) {
    /**
     * The app's hidden `appDataFolder`, scope `drive.appdata`. Nobody else can
     * see it, the Drive app included, so a restore only works from this app.
     */
    APP_DATA("appdata"),

    /**
     * A normal folder the user sees in Drive, scope `drive.file`: the app can
     * open only files it made itself, never the rest of the Drive.
     */
    FOLDER("folder"),
    ;

    companion object {
        fun of(id: String): DriveSpace = entries.firstOrNull { it.id == id } ?: APP_DATA

        const val DEFAULT_FOLDER = "WM Keyboard backups"
    }
}

/**
 * A WebDAV service the screen knows the address shape of, so the user types a
 * server name and a folder instead of a long URL. [template] takes `{server}`,
 * `{user}` and `{folder}`.
 */
enum class WebDavPreset(
    /** Stable on disk. Empty is "type the whole address". */
    val id: String,
    val label: String,
    val template: String,
    /**
     * Whether the `{server}` in [template] is an account id rather than a
     * host name, which changes what the field is called.
     */
    val serverIsId: Boolean = false,
) {
    CUSTOM("", "", ""),
    NEXTCLOUD("nextcloud", "Nextcloud", "https://{server}/remote.php/dav/files/{user}/{folder}"),
    OWNCLOUD("owncloud", "ownCloud", "https://{server}/remote.php/dav/files/{user}/{folder}"),
    SEAFILE("seafile", "Seafile", "https://{server}/seafdav/{folder}"),
    KOOFR("koofr", "Koofr", "https://app.koofr.net/dav/Koofr/{folder}"),
    PCLOUD_US("pcloud-us", "pCloud (US)", "https://webdav.pcloud.com/{folder}"),
    PCLOUD_EU("pcloud-eu", "pCloud (EU)", "https://ewebdav.pcloud.com/{folder}"),
    YANDEX("yandex", "Yandex Disk", "https://webdav.yandex.com/{folder}"),
    KDRIVE("kdrive", "kDrive", "https://{server}.connect.kdrive.infomaniak.com/{folder}", serverIsId = true),
    STORAGE_BOX("storagebox", "Hetzner Storage Box", "https://{user}.your-storagebox.de/{folder}"),
    FOURSHARED("4shared", "4shared", "https://webdav.4shared.com/{folder}"),
    ;

    /** Whether [template] has a `{server}` the user must fill in. */
    val needsServer: Boolean get() = template.contains("{server}")

    /**
     * The address for these parts. Each part loses stray slashes and is
     * percent-encoded segment by segment, so a folder named "My backups"
     * becomes `My%20backups` and a nested folder keeps its slashes.
     */
    fun url(server: String, user: String, folder: String): String {
        val host = server.trim().removePrefix("https://").removePrefix("http://").trimEnd('/')
        val path = folder.trim().trim('/').split('/').filter { it.isNotEmpty() }.joinToString("/", transform = ::encodeSegment)
        return template
            .replace("{server}", host)
            .replace("{user}", encodeSegment(user.trim()))
            .replace("{folder}", path)
            .trimEnd('/') + "/"
    }

    companion object {
        fun of(id: String): WebDavPreset = entries.firstOrNull { it.id == id } ?: CUSTOM

        const val DEFAULT_FOLDER = "WM Keyboard"

        private fun encodeSegment(segment: String): String =
            java.net.URLEncoder.encode(segment, "UTF-8").replace("+", "%20")
    }
}

/**
 * An S3-compatible service the screen knows the endpoint shape of. [template]
 * takes `{region}` and `{account}`; [account] says what `{account}` is on that
 * service, and whether there is one to ask for at all.
 */
enum class S3Preset(
    /** Stable on disk. Empty is "type the endpoint". */
    val id: String,
    val label: String,
    val template: String,
    val defaultRegion: String,
    val pathStyle: Boolean = false,
    val regions: List<String> = emptyList(),
    val account: S3Account = S3Account.NONE,
) {
    CUSTOM("", "", "", "us-east-1"),
    AWS(
        "aws", "Amazon S3", "", "us-east-1",
        regions = listOf("us-east-1", "us-west-2", "eu-west-1", "eu-central-1", "ap-south-1", "ap-southeast-1"),
    ),
    R2("r2", "Cloudflare R2", "https://{account}.r2.cloudflarestorage.com", "auto", account = S3Account.ACCOUNT_ID),
    B2(
        "b2", "Backblaze B2", "https://s3.{region}.backblazeb2.com", "us-west-004",
        regions = listOf("us-west-000", "us-west-001", "us-west-002", "us-west-004", "us-east-005", "eu-central-003"),
    ),
    WASABI(
        "wasabi", "Wasabi", "https://s3.{region}.wasabisys.com", "us-east-1",
        regions = listOf("us-east-1", "us-east-2", "us-west-1", "eu-central-1", "eu-west-1", "ap-northeast-1"),
    ),
    GCS("gcs", "Google Cloud Storage", "https://storage.googleapis.com", "auto"),
    SPACES(
        "spaces", "DigitalOcean Spaces", "https://{region}.digitaloceanspaces.com", "nyc3",
        regions = listOf("nyc3", "sfo3", "ams3", "fra1", "sgp1", "syd1"),
    ),
    HETZNER(
        "hetzner", "Hetzner Object Storage", "https://{region}.your-objectstorage.com", "fsn1",
        regions = listOf("fsn1", "nbg1", "hel1"),
    ),
    SCALEWAY(
        "scaleway", "Scaleway", "https://s3.{region}.scw.cloud", "fr-par",
        regions = listOf("fr-par", "nl-ams", "pl-waw"),
    ),
    OVH(
        "ovh", "OVHcloud", "https://s3.{region}.io.cloud.ovh.net", "gra",
        regions = listOf("gra", "sbg", "de", "uk", "bhs"),
    ),
    LINODE(
        "linode", "Akamai (Linode)", "https://{region}.linodeobjects.com", "us-east-1",
        regions = listOf("us-east-1", "us-southeast-1", "eu-central-1", "ap-south-1"),
    ),
    STORJ("storj", "Storj", "https://gateway.storjshare.io", "us1", pathStyle = true),
    IDRIVE_E2("idrive", "IDrive e2", "https://{account}", "us-east-1", account = S3Account.ENDPOINT),
    MINIO("minio", "MinIO", "https://{account}", "us-east-1", pathStyle = true, account = S3Account.SERVER),
    GARAGE("garage", "Garage", "https://{account}", "garage", pathStyle = true, account = S3Account.SERVER),
    ;

    /** The endpoint for [region] and [account]. Empty for Amazon, where the sink derives it. */
    fun endpoint(region: String, account: String): String {
        val host = account.trim().removePrefix("https://").removePrefix("http://").trimEnd('/')
        return template.replace("{region}", region.trim()).replace("{account}", host)
    }

    companion object {
        fun of(id: String): S3Preset = entries.firstOrNull { it.id == id } ?: CUSTOM
    }
}

/** What the `{account}` part of an [S3Preset] endpoint is. The screen words it. */
enum class S3Account { NONE, ACCOUNT_ID, ENDPOINT, SERVER }
