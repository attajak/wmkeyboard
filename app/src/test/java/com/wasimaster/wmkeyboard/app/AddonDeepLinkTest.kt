package com.wasimaster.wmkeyboard.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLDecoder

/**
 * Deep links are untrusted input from a web page, so what they can and cannot
 * turn into is worth pinning. Every case here is "what route does this link
 * produce" — that a link can only ever *navigate*, never install or add a
 * repository, is enforced by the screens it lands on.
 */
class AddonDeepLinkTest {

    private fun decodedSegments(route: String): List<String> =
        route.split('/', '?', '=').map { URLDecoder.decode(it, "UTF-8") }

    @Test
    fun `the bare addons link opens the addons screen`() {
        assertEquals("addons", AddonDeepLink.routeFor("wmkeyboard://addons"))
    }

    @Test
    fun `the scheme is matched case-insensitively`() {
        assertEquals("addons", AddonDeepLink.routeFor("WMKeyboard://addons"))
    }

    @Test
    fun `a repo link pre-fills the add dialog with the resolved manifest URL`() {
        val route = AddonDeepLink.routeFor(
            "wmkeyboard://repo?url=https%3A%2F%2Fgithub.com%2Fuser%2Frepo",
        )
        assertTrue("expected an addons route, got $route", route!!.startsWith("addons?add="))
        assertTrue(
            "the resolved raw manifest URL should be carried through",
            decodedSegments(route).any {
                it == "https://raw.githubusercontent.com/user/repo/HEAD/wmkeyboard-repo.json"
            },
        )
    }

    @Test
    fun `a repo link with no url just opens the addons screen`() {
        assertEquals("addons", AddonDeepLink.routeFor("wmkeyboard://repo"))
    }

    @Test
    fun `an addon link resolves to that addon's detail route`() {
        val route = AddonDeepLink.routeFor(
            "wmkeyboard://addon?repo=https%3A%2F%2Fgithub.com%2Fuser%2Frepo&id=midnight",
        )
        assertTrue("expected an addon route, got $route", route!!.startsWith("addon/"))
        val parts = decodedSegments(route)
        assertTrue(
            parts.any { it == "https://raw.githubusercontent.com/user/repo/HEAD/wmkeyboard-repo.json" },
        )
        assertEquals("midnight", parts.last())
    }

    @Test
    fun `an unencoded repo URL still works`() {
        // People hand-write these links; requiring percent-encoding of the
        // whole URL would make most of them silently dead.
        val route = AddonDeepLink.routeFor(
            "wmkeyboard://addon?repo=https://github.com/user/repo&id=midnight",
        )
        assertTrue("expected an addon route, got $route", route!!.startsWith("addon/"))
    }

    @Test
    fun `an addon link without an id is refused`() {
        assertNull(AddonDeepLink.routeFor("wmkeyboard://addon?repo=https://github.com/user/repo"))
    }

    @Test
    fun `an addon link without a repo is refused`() {
        assertNull(AddonDeepLink.routeFor("wmkeyboard://addon?id=midnight"))
    }

    @Test
    fun `a link carrying a non-https repository is refused`() {
        for (repo in listOf(
            "http://example.com/wmkeyboard-repo.json",
            "file:///data/data/com.example/wmkeyboard-repo.json",
            "javascript:alert(1)",
        )) {
            assertNull(
                "should refuse $repo",
                AddonDeepLink.routeFor("wmkeyboard://addon?repo=$repo&id=x"),
            )
        }
    }

    @Test
    fun `another app's scheme is not ours`() {
        assertNull(AddonDeepLink.routeFor("https://example.com/addons"))
        assertNull(AddonDeepLink.routeFor("otherkeyboard://addons"))
    }

    @Test
    fun `an unknown target is refused rather than guessed`() {
        assertNull(AddonDeepLink.routeFor("wmkeyboard://install-everything"))
    }

    @Test
    fun `junk and empties resolve to nothing`() {
        assertNull(AddonDeepLink.routeFor(null as String?))
        assertNull(AddonDeepLink.routeFor(""))
        assertNull(AddonDeepLink.routeFor("   "))
        assertNull(AddonDeepLink.routeFor("wmkeyboard://"))
    }

    // ---- Signal sticker packs ------------------------------------------

    private val signalId = "fb535407d2f6497ec074df8b9c51dd1d"
    private val signalKey = "17e971c134035622781d2ee249e6473b774583750b68c11bb82b7509c68b6dfd"

    @Test
    fun `a signal pack link opens that pack's preview`() {
        assertEquals(
            signalPackRoute(signalId, signalKey),
            AddonDeepLink.routeFor(AddonDeepLink.signalPackLink(signalId, signalKey)),
        )
        // Hex in capitals is the same pack, and the route carries one spelling.
        assertEquals(
            signalPackRoute(signalId, signalKey),
            AddonDeepLink.routeFor("wmkeyboard://signalpack?id=${signalId.uppercase()}&key=$signalKey"),
        )
    }

    @Test
    fun `a signal pack link with a bad half opens nothing`() {
        assertNull(AddonDeepLink.routeFor("wmkeyboard://signalpack?id=abc&key=$signalKey"))
        assertNull(AddonDeepLink.routeFor("wmkeyboard://signalpack?id=../../../../etc/passwd/0000000000&key=$signalKey"))
        assertNull(AddonDeepLink.routeFor("wmkeyboard://signalpack"))
    }
}
