package com.wasimaster.wmkeyboard.core.addons

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The link resolver is the other trust boundary of the addon layer: the text
 * comes out of a share sheet, and every address it produces gets fetched.
 *
 * What is pinned here is the mapping, one row of the table per test, and the
 * two refusals that matter: plain http, and text with no address in it. The
 * network half is not tested here because there is nothing to test without a
 * server; what it does with a listing is decided by these targets.
 */
class ImportLinkTest {

    private fun target(link: String): ImportLink.Target = ImportLink.resolve(link)

    private fun fileUrl(link: String): String {
        val resolved = target(link)
        assertTrue("$link resolved to $resolved", resolved is ImportLink.Target.File)
        return (resolved as ImportLink.Target.File).url
    }

    private fun listingUrl(link: String): String {
        val resolved = target(link)
        assertTrue("$link resolved to $resolved", resolved is ImportLink.Target.Listing)
        return (resolved as ImportLink.Target.Listing).apiUrl
    }

    // ---- what counts as a link -----------------------------------------

    @Test
    fun `the address is taken out of the sentence around it`() {
        // A browser shares the page title and then the link; a chat app shares
        // whatever was typed around it.
        assertEquals(
            "https://github.com/o/r/blob/main/a.wmtheme.json",
            ImportLink.urlIn("Look at this theme https://github.com/o/r/blob/main/a.wmtheme.json"),
        )
    }

    @Test
    fun `the punctuation of the sentence is not part of the address`() {
        assertEquals(
            "https://example.com/a.flex",
            ImportLink.urlIn("Try https://example.com/a.flex."),
        )
    }

    @Test
    fun `an https address wins over an http one earlier in the text`() {
        assertEquals(
            "https://example.com/b",
            ImportLink.urlIn("was http://example.com/a, now https://example.com/b"),
        )
    }

    @Test
    fun `plain http is refused rather than upgraded`() {
        // The same rule AddonRepoCodec follows: a link that expected to be
        // intercepted has to fail visibly.
        assertTrue(target("http://example.com/a.wmtheme.json") is ImportLink.Target.Insecure)
    }

    @Test
    fun `text with no address in it is not a link`() {
        assertTrue(target("just a message") is ImportLink.Target.None)
        assertTrue(target("") is ImportLink.Target.None)
    }

    // ---- one file ------------------------------------------------------

    @Test
    fun `a raw address is fetched as it stands`() {
        val raw = "https://raw.githubusercontent.com/o/r/main/a.wmtheme.json"
        assertEquals(raw, fileUrl(raw))
    }

    @Test
    fun `a release asset is fetched as it stands`() {
        val asset = "https://github.com/o/r/releases/download/v1.0/pack.wmstickers"
        assertEquals(asset, fileUrl(asset))
    }

    @Test
    fun `a file on a repository page resolves to its raw address`() {
        assertEquals(
            "https://raw.githubusercontent.com/o/r/main/themes/a.wmtheme.json",
            fileUrl("https://github.com/o/r/blob/main/themes/a.wmtheme.json"),
        )
        assertEquals(
            "https://codeberg.org/o/r/raw/branch/main/a.wmlayout.json",
            fileUrl("https://codeberg.org/o/r/src/branch/main/a.wmlayout.json"),
        )
        assertEquals(
            "https://gitlab.com/o/r/-/raw/main/a.flex",
            fileUrl("https://gitlab.com/o/r/-/blob/main/a.flex"),
        )
    }

    @Test
    fun `a raw path is recognised by where the word sits, not by having it`() {
        // A repository with a folder called "raw" in it is a page.
        assertEquals(
            "https://github.com/o/r/raw/main/a.kmp",
            fileUrl("https://github.com/o/r/raw/main/a.kmp"),
        )
        assertEquals(
            "https://raw.githubusercontent.com/o/r/main/raw/a.wmtheme.json",
            fileUrl("https://github.com/o/r/blob/main/raw/a.wmtheme.json"),
        )
    }

    @Test
    fun `an unknown host is downloaded and identified by its bytes`() {
        val other = "https://example.com/files/a.wmsnippets.json"
        assertEquals(other, fileUrl(other))
        assertEquals("a.wmsnippets.json", (target(other) as ImportLink.Target.File).name)
    }

    // ---- lists ---------------------------------------------------------

    @Test
    fun `a release page resolves to that release's assets`() {
        assertEquals(
            "https://api.github.com/repos/o/r/releases/tags/v1.0",
            listingUrl("https://github.com/o/r/releases/tag/v1.0"),
        )
        assertEquals(
            "https://api.github.com/repos/o/r/releases/latest",
            listingUrl("https://github.com/o/r/releases/latest"),
        )
    }

    @Test
    fun `a release page on another forge uses that forge's own API`() {
        assertEquals(
            "https://codeberg.org/api/v1/repos/o/r/releases/tags/v2",
            listingUrl("https://codeberg.org/o/r/releases/tag/v2"),
        )
        assertEquals(
            "https://gitlab.com/api/v4/projects/group%2Fsub%2Fr/releases/v2",
            listingUrl("https://gitlab.com/group/sub/r/-/releases/v2"),
        )
    }

    @Test
    fun `a gist resolves to the gist API`() {
        assertEquals(
            "https://api.github.com/gists/0123456789abcdef0123456789abcdef",
            listingUrl("https://gist.github.com/someone/0123456789abcdef0123456789abcdef"),
        )
    }

    @Test
    fun `a repository root is both a listing and a manifest probe`() {
        val resolved = target("https://github.com/o/r") as ImportLink.Target.Listing
        assertEquals("https://api.github.com/repos/o/r/contents/", resolved.apiUrl)
        assertEquals(
            "https://raw.githubusercontent.com/o/r/HEAD/${AddonRepoCodec.MANIFEST_NAME}",
            resolved.manifestProbe,
        )
    }

    @Test
    fun `a folder inside a repository is not probed for a manifest`() {
        // The manifest lives at the root, so probing for one on a link into a
        // folder would answer a link to the folder with the whole repository.
        val resolved = target("https://github.com/o/r/tree/main/themes") as ImportLink.Target.Listing
        assertEquals("https://api.github.com/repos/o/r/contents/themes?ref=main", resolved.apiUrl)
        assertEquals(null, resolved.manifestProbe)
    }

    // ---- artifacts -----------------------------------------------------

    @Test
    fun `a build artifact is its own target, in both link shapes`() {
        val fromRun = target("https://github.com/o/r/actions/runs/123/artifacts/456")
        assertEquals(ImportLink.Target.Artifact("o", "r", "456"), fromRun)
        val fromSuite = target("https://github.com/o/r/suites/99/artifacts/456")
        assertEquals(ImportLink.Target.Artifact("o", "r", "456"), fromSuite)
    }

    @Test
    fun `an artifact has an address with a token and one without`() {
        val artifact = ImportLink.Target.Artifact("o", "r", "456")
        assertEquals(
            "https://api.github.com/repos/o/r/actions/artifacts/456/zip",
            ImportLink.artifactApiUrl(artifact),
        )
        assertEquals(
            "https://nightly.link/o/r/actions/artifacts/456.zip",
            ImportLink.nightlyLinkUrl(artifact),
        )
    }
}
