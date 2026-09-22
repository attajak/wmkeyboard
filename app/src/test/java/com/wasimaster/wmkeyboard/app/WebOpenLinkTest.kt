package com.wasimaster.wmkeyboard.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The docs site's `/open/` page as an App Link: every address the page itself
 * reads has to mean the same thing here, or a link works in the browser and
 * dies when the app claims it.
 *
 * Kept in step with `requestedLink()` in `docs/src/pages/open/index.astro`.
 */
class WebOpenLinkTest {

    private val site = "https://wmkeyboard.pages.dev"

    @Test
    fun `fragment carries the address`() {
        assertEquals(
            "wmkeyboard://settings/themes",
            WebOpenLink.appLink("$site/open/#wmkeyboard://settings/themes"),
        )
    }

    @Test
    fun `fragment works without the trailing slash`() {
        assertEquals(
            "wmkeyboard://settings/themes",
            WebOpenLink.appLink("$site/open#wmkeyboard://settings/themes"),
        )
    }

    @Test
    fun `a percent-encoded fragment is decoded`() {
        assertEquals(
            "wmkeyboard://settings/vocab/word/p1/caf%C3%A9",
            WebOpenLink.appLink("$site/open/#wmkeyboard%3A%2F%2Fsettings%2Fvocab%2Fword%2Fp1%2Fcaf%25C3%25A9"),
        )
    }

    @Test
    fun `link parameter carries the address`() {
        assertEquals(
            "wmkeyboard://settings/typing/corrections",
            WebOpenLink.appLink("$site/open/?link=wmkeyboard%3A%2F%2Fsettings%2Ftyping%2Fcorrections"),
        )
    }

    @Test
    fun `a fragment wins over a link parameter`() {
        assertEquals(
            "wmkeyboard://settings/themes",
            WebOpenLink.appLink("$site/open/?link=wmkeyboard%3A%2F%2Fsettings%2Femoji#wmkeyboard://settings/themes"),
        )
    }

    @Test
    fun `route shorthand names a screen`() {
        assertEquals("wmkeyboard://settings/themes", WebOpenLink.appLink("$site/open/?route=themes"))
    }

    @Test
    fun `route shorthand keeps an encoded argument`() {
        assertEquals("wmkeyboard://settings/tool/CLIPBOARD", WebOpenLink.appLink("$site/open/?route=tool%2FCLIPBOARD"))
    }

    @Test
    fun `an empty route names the home list`() {
        assertEquals("wmkeyboard://settings", WebOpenLink.appLink("$site/open/?route="))
    }

    @Test
    fun `route and setting name a row on a screen`() {
        assertEquals(
            "wmkeyboard://settings/typing/corrections?setting=typing_autocorrect_title",
            WebOpenLink.appLink("$site/open/?route=typing%2Fcorrections&setting=typing_autocorrect_title"),
        )
    }

    @Test
    fun `a setting alone names the row and no screen`() {
        assertEquals(
            "wmkeyboard://setting/typing_autocorrect_title",
            WebOpenLink.appLink("$site/open/?setting=typing_autocorrect_title"),
        )
    }

    @Test
    fun `since is handed on with a route or a setting`() {
        assertEquals(
            "wmkeyboard://settings/typing?setting=typing_auto_close_brackets_title&since=0.5.12",
            WebOpenLink.appLink("$site/open/?route=typing&setting=typing_auto_close_brackets_title&since=0.5.12"),
        )
        assertEquals(
            "wmkeyboard://setting/typing_auto_close_brackets_title?since=0.5.12",
            WebOpenLink.appLink("$site/open/?setting=typing_auto_close_brackets_title&since=0.5.12"),
        )
        assertEquals("wmkeyboard://settings/themes?since=0.5.12", WebOpenLink.appLink("$site/open/?route=themes&since=0.5.12"))
    }

    @Test
    fun `repo alone pre-fills the add dialog`() {
        assertEquals(
            "wmkeyboard://repo?url=https%3A%2F%2Fgithub.com%2Fwasi-master%2Fwmkeyboard-addon-repository",
            WebOpenLink.appLink("$site/open/?repo=https%3A%2F%2Fgithub.com%2Fwasi-master%2Fwmkeyboard-addon-repository"),
        )
    }

    @Test
    fun `repo and id name one addon`() {
        val link = WebOpenLink.appLink("$site/open/?repo=github.com%2Fa%2Fb&id=blockland")
        assertEquals("wmkeyboard://addon?repo=github.com%2Fa%2Fb&id=blockland", link)
    }

    @Test
    fun `the addons flag needs no value`() {
        assertEquals("wmkeyboard://addons", WebOpenLink.appLink("$site/open/?addons"))
    }

    /** What the rewrite is for: the result has to survive the real parsers. */
    @Test
    fun `a rewritten address resolves like a typed one`() {
        val fromWeb = WebOpenLink.appLink("$site/open/?route=typing%2Fcorrections&setting=typing_autocorrect_title")
        val target = SettingsDeepLink.parse(fromWeb)
        assertEquals(SettingsDeepLink.Target("typing/corrections", "typing_autocorrect_title"), target)
    }

    @Test
    fun `a rewritten addon address resolves like a typed one`() {
        val fromWeb = WebOpenLink.appLink("$site/open/?repo=https%3A%2F%2Fgithub.com%2Fa%2Fb&id=blockland")
        assertEquals(AddonDeepLink.routeFor("wmkeyboard://addon?repo=https%3A%2F%2Fgithub.com%2Fa%2Fb&id=blockland"), AddonDeepLink.routeFor(fromWeb))
    }

    @Test
    fun `another page on the same site is not ours`() {
        assertNull(WebOpenLink.appLink("$site/reference/deep-links/#wmkeyboard://settings/themes"))
        assertNull(WebOpenLink.appLink("$site/opensource/#wmkeyboard://settings/themes"))
        assertNull(WebOpenLink.appLink("$site/open/extra/#wmkeyboard://settings/themes"))
    }

    @Test
    fun `another host is not ours`() {
        assertNull(WebOpenLink.appLink("https://evil.example/open/#wmkeyboard://settings/themes"))
        assertNull(WebOpenLink.appLink("https://wmkeyboard.pages.dev.evil.example/open/#wmkeyboard://settings/themes"))
    }

    @Test
    fun `http is not https`() {
        assertNull(WebOpenLink.appLink("http://wmkeyboard.pages.dev/open/#wmkeyboard://settings/themes"))
    }

    @Test
    fun `the host is matched without case`() {
        assertEquals(
            "wmkeyboard://settings/themes",
            WebOpenLink.appLink("https://WMKeyboard.Pages.Dev/open/#wmkeyboard://settings/themes"),
        )
    }

    @Test
    fun `an address naming nothing opens nothing`() {
        assertNull(WebOpenLink.appLink("$site/open/"))
        assertNull(WebOpenLink.appLink("$site/open/?"))
        assertNull(WebOpenLink.appLink("$site/open/#"))
        assertNull(WebOpenLink.appLink(""))
        // Typed, because a bare null fits the Uri overload just as well.
        assertNull(WebOpenLink.appLink(null as String?))
    }

    /**
     * The rewrite grants nothing on its own: a fragment naming a screen this
     * build does not have still has to die in the allowlist.
     */
    @Test
    fun `a rewritten address still faces the allowlist`() {
        val fromWeb = WebOpenLink.appLink("$site/open/#wmkeyboard://settings/not_a_screen")
        assertEquals("wmkeyboard://settings/not_a_screen", fromWeb)
        assertNull(SettingsDeepLink.parse(fromWeb))
    }

    @Test
    fun `a fragment that is not one of ours is handed on and refused`() {
        val fromWeb = WebOpenLink.appLink("$site/open/#https://evil.example")
        assertEquals("https://evil.example", fromWeb)
        assertNull(SettingsDeepLink.parse(fromWeb))
        assertNull(AddonDeepLink.routeFor(fromWeb))
    }
}
