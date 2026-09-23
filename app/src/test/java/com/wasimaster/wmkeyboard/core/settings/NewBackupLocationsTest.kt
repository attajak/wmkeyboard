package com.wasimaster.wmkeyboard.core.settings

import com.wasimaster.wmkeyboard.core.settings.sink.WebDavSink
import com.wasimaster.wmkeyboard.core.settings.sink.GitSink
import com.wasimaster.wmkeyboard.core.settings.sink.SinkError
import com.wasimaster.wmkeyboard.core.settings.sink.sftp.SftpAlgorithms
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The SFTP, SMB, Git and IMAP locations, the Drive folder mode and the WebDAV
 * and S3 presets: what is stored, what the screen builds from it, and the
 * keep rules the SFTP sink depends on in a release build.
 */
class NewBackupLocationsTest {

    @Test
    fun `every new section survives the stored list`() {
        val locations = listOf(
            BackupLocation(
                id = "aaaaaaaa", type = BackupDestination.SFTP,
                sftp = SftpConfig("h", 2222, "u", "p", "KEY", "kp", "/b", "ssh-ed25519 AAAA", legacyAlgorithms = true),
            ),
            BackupLocation(
                id = "bbbbbbbb", type = BackupDestination.SMB,
                smb = SmbConfig("nas", 4450, "share", "a/b", "WORK", "u", "p", encrypt = false),
            ),
            BackupLocation(
                id = "cccccccc", type = BackupDestination.GIT,
                git = GitConfig(
                    GitProvider.GITEA, "https://git.example", "me/b", "bk", "dir", "tok",
                    "Me", "me@x", "{action} {file}", skipCi = false, allowPublic = true,
                ),
            ),
            BackupLocation(
                id = "dddddddd", type = BackupDestination.IMAP,
                imap = ImapConfig("imap.x", 143, ImapSecurity.STARTTLS, "me@x", "p", "Box"),
            ),
            BackupLocation(id = "eeeeeeee", type = BackupDestination.DRIVE, driveSpace = DriveSpace.FOLDER, driveFolder = "A/B"),
            BackupLocation(
                id = "ffffffff", type = BackupDestination.WEBDAV, webDavUrl = "https://c/remote.php/dav/files/u/WM/",
                webDavUser = "u", webDavPassword = "p", webDavPreset = "nextcloud", webDavServer = "c", webDavFolder = "WM",
            ),
            BackupLocation(
                id = "11111111", type = BackupDestination.S3,
                s3 = S3Config(endpoint = "https://acct.r2.cloudflarestorage.com", region = "auto", bucket = "b",
                    accessKeyId = "k", secretAccessKey = "s", preset = "r2", account = "acct"),
            ),
        )
        assertEquals(locations, BackupLocation.decodeList(BackupLocation.encodeList(locations)))
        assertTrue(locations.all { it.configured })
    }

    @Test
    fun `an old stored Drive location stays in the hidden folder`() {
        val decoded = BackupLocation.decodeList("""[{"id":"12345678","type":"drive"}]""").single()
        assertEquals(DriveSpace.APP_DATA, decoded.driveSpace)
    }

    @Test
    fun `configured asks for the minimum of each kind`() {
        assertFalse(SftpConfig(host = "h", user = "u").configured)
        assertTrue(SftpConfig(host = "h", user = "u", privateKey = "k").configured)
        assertFalse(SmbConfig(host = "h", user = "u").configured)
        assertFalse(GitConfig(repository = "noslash", token = "t").configured)
        assertTrue(GitConfig(repository = "me/r", token = "t").configured)
        assertFalse(ImapConfig(host = "h", user = "u").configured)
    }

    @Test
    fun `webdav presets build the address, encoding each part`() {
        assertEquals(
            "https://cloud.example.com/remote.php/webdav/WM%20Keyboard/",
            WebDavPreset.NEXTCLOUD.url("https://cloud.example.com/", "me@x", "WM Keyboard"),
        )
        assertEquals("https://app.koofr.net/dav/Koofr/a/b/", WebDavPreset.KOOFR.url("", "u", "/a/b/"))
        assertEquals("https://u123.your-storagebox.de/bk/", WebDavPreset.STORAGE_BOX.url("", "u123", "bk"))
        assertEquals("https://p.example/seafdav/Lib/", WebDavPreset.SEAFILE.url("p.example", "u", "Lib"))
        assertTrue(WebDavPreset.KDRIVE.needsServer)
        assertFalse(WebDavPreset.YANDEX.needsServer)
        assertEquals(
            "http://100.100.100.100:8080/example.ts.net/laptop/my%20share/WM%20Keyboard/",
            WebDavPreset.TAILDRIVE.url("/example.ts.net/laptop/my share/", "", "WM Keyboard"),
        )
        assertFalse(WebDavPreset.TAILDRIVE.signsIn)
        assertTrue(WebDavPreset.NEXTCLOUD.signsIn)
    }

    @Test
    fun `plain http is allowed only on a tailscale network`() {
        assertTrue(WebDavSink.isTailnet("http://100.100.100.100:8080/example.ts.net/laptop/share/"))
        assertTrue(WebDavSink.isTailnet("http://100.101.2.3:5005/dav/"))
        assertTrue(WebDavSink.isTailnet("http://laptop.example.ts.net/dav/"))
        assertTrue(WebDavSink.isTailnet("http://[fd7a:115c:a1e0::1]:8080/"))
        assertFalse(WebDavSink.isTailnet("http://192.168.1.10/dav/"))
        assertFalse(WebDavSink.isTailnet("http://100.128.0.1/"))
        assertFalse(WebDavSink.isTailnet("http://nas.local/"))
        assertFalse(WebDavSink.isTailnet("https://100.100.100.100/"))
        // A webdav location needs no user name: Taildrive asks for none.
        assertTrue(BackupLocation(id = "a", type = BackupDestination.WEBDAV, webDavUrl = "http://100.100.100.100:8080/x/").configured)
    }

    @Test
    fun `s3 presets fill the endpoint from the region and the account`() {
        assertEquals("https://s3.us-west-004.backblazeb2.com", S3Preset.B2.endpoint("us-west-004", ""))
        assertEquals("https://abc.r2.cloudflarestorage.com", S3Preset.R2.endpoint("auto", " abc "))
        assertEquals("https://nas.local:9000", S3Preset.MINIO.endpoint("us-east-1", "http://nas.local:9000/"))
        assertEquals("", S3Preset.AWS.endpoint("eu-west-1", ""))
        assertTrue(S3Preset.MINIO.pathStyle)
        assertEquals(S3Preset.CUSTOM, S3Preset.of("nonsense"))
    }

    @Test
    fun `git api roots and pasted addresses`() {
        assertEquals("https://api.github.com", GitSink.apiBase(GitProvider.GITHUB, ""))
        assertEquals("https://ghe.corp/api/v3", GitSink.apiBase(GitProvider.GITHUB, "ghe.corp/"))
        assertEquals("https://gitlab.com/api/v4", GitSink.apiBase(GitProvider.GITLAB, ""))
        assertEquals("https://codeberg.org/api/v1", GitSink.apiBase(GitProvider.GITEA, ""))
        assertEquals("https://github.com" to "me/b", GitSink.parseRemote("https://github.com/me/b.git"))
        assertEquals("https://codeberg.org" to "me/b", GitSink.parseRemote("git@codeberg.org:me/b.git"))
        assertEquals("https://gitlab.com" to "g/sub/b", GitSink.parseRemote("https://gitlab.com/g/sub/b/-/tree/main"))
        assertNull(GitSink.parseRemote("me/b"))
    }

    @Test
    fun `commit messages take the template and skip ci`() {
        val config = GitConfig(message = "{action} {file} from {device}")
        assertEquals("Add x.json from Pixel [skip ci]", GitSink.commitMessage(config, "Add", "x.json", "Pixel", 0L))
        assertEquals("Delete x.json", GitSink.commitMessage(GitConfig(skipCi = false), "Delete", "x.json", "", 0L))
    }

    @Test
    fun `git errors, with the rate limit told apart from a refusal`() {
        assertEquals(SinkError.IO, GitSink.statusError(403, "0"))
        assertEquals(SinkError.PERMISSION_LOST, GitSink.statusError(403, "12"))
        assertEquals(SinkError.PERMISSION_LOST, GitSink.statusError(401))
        assertEquals(SinkError.TARGET_MISSING, GitSink.statusError(404))
    }

    /**
     * JSch finds its algorithm classes by name, which R8 cannot see. Every one
     * the SFTP sink can reach must be kept by `proguard-rules.pro`, or a
     * release build fails the first SFTP backup with ClassNotFoundException.
     */
    @Test
    fun `proguard keeps every class JSch looks up by name`() {
        val root = listOf(File(".."), File(".")).first { File(it, "settings.gradle.kts").isFile }
        val rules = File(root, "app/proguard-rules.pro").readText()
        val keptExact = Regex("""-keep class (com\.jcraft\.jsch\.[A-Za-z0-9.]+) \{ <init>\(\); \}""")
            .findAll(rules).map { it.groupValues[1] }.toSet()
        val keptPackages = Regex("""-keep class (com\.jcraft\.jsch\.[a-z0-9.]+)\.\*\* \{ <init>\(\); \}""")
            .findAll(rules).map { it.groupValues[1] + "." }.toList()
        val classes = SftpAlgorithms.reflectedClasses()
        assertTrue("found only ${classes.size} classes", classes.size > 20)
        val missing = classes.filter { name -> name !in keptExact && keptPackages.none { name.startsWith(it) } }
        assertTrue("Not kept for R8: $missing", missing.isEmpty())
        assertTrue(rules.contains("-keeppackagenames com.jcraft.jsch.**"))
    }
}
