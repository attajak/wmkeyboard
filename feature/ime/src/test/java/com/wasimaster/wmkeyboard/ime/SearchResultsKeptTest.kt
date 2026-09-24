package com.wasimaster.wmkeyboard.ime

import com.wasimaster.wmkeyboard.core.clipboard.ClipboardStore
import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import com.wasimaster.wmkeyboard.core.settings.WebSearchSettings
import com.wasimaster.wmkeyboard.core.stickers.StickerPackStore
import com.wasimaster.wmkeyboard.core.tools.ImageResult
import com.wasimaster.wmkeyboard.core.tools.WebResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Web and image search results outlive the panel (#347). Someone reads the
 * first result in the browser, comes back and opens the tool again for the
 * second one: the list they left must still be there, not an empty search box
 * that costs another request and may answer differently. Only the clear button
 * in the search bar starts over.
 */
@RunWith(RobolectricTestRunner::class)
class SearchResultsKeptTest {

    private val keyed = KeyboardSettings(
        learnFromTyping = false,
        webSearch = WebSearchSettings(braveApiKey = "test-key"),
    )

    private val web = WebSearchUi.Ready(
        listOf(WebResult("Kotlin", "A language", "https://kotlinlang.org", "kotlinlang.org")),
        query = "kotlin",
    )

    private val images = ImageSearchUi.Ready(
        listOf(ImageResult("Cat", "https://t/cat.jpg", "https://i/cat.jpg", "image/jpeg", "https://p/cat")),
        query = "cat",
    )

    private fun keyboard(settings: KeyboardSettings = keyed): GlideKeyboard {
        val (service, _, _) = glideKeyboard(
            glideReadyState(settings = settings).copy(webSearch = web, imageSearch = images),
        )
        // What onCreate attaches and every panel open reads.
        plant(service, "clipboardStore", ClipboardStore(null))
        plant(service, "stickerPackStore", StickerPackStore(null))
        return service
    }

    private fun plant(service: WMKeyboardService, name: String, value: Any) {
        val field = WMKeyboardService::class.java.getDeclaredField(name)
        field.isAccessible = true
        field.set(service, value)
    }

    @Test
    fun `reopening web search lands back on the results and their query`() {
        val service = keyboard()

        service.onPanelChange(PanelMode.WEB_SEARCH)
        service.onPanelChange(PanelMode.WEB_SEARCH) // closed, e.g. to open a link
        service.onPanelChange(PanelMode.WEB_SEARCH)

        val state = service.uiState.value
        assertEquals(web, state.webSearch)
        assertEquals("kotlin", state.mediaQuery)
        assertFalse("the results are on screen, not the keys", state.mediaSearchActive)
    }

    @Test
    fun `reopening image search keeps its grid too`() {
        val service = keyboard()

        service.onPanelChange(PanelMode.IMAGE_SEARCH)

        val state = service.uiState.value
        assertEquals(images, state.imageSearch)
        assertEquals("cat", state.mediaQuery)
        assertFalse(state.mediaSearchActive)
    }

    @Test
    fun `clear starts a new search`() {
        val service = keyboard()
        service.onPanelChange(PanelMode.WEB_SEARCH)

        service.onMediaQueryClear()

        val state = service.uiState.value
        assertEquals(WebSearchUi.Idle, state.webSearch)
        assertEquals("", state.mediaQuery)
        assertTrue("the keys go to the search box", state.mediaSearchActive)
        // Only the open panel's results go.
        assertEquals(images, state.imageSearch)
    }

    @Test
    fun `without results the panel opens straight into the search box as before`() {
        val (service, _, _) = glideKeyboard(glideReadyState(settings = keyed))
        plant(service, "clipboardStore", ClipboardStore(null))
        plant(service, "stickerPackStore", StickerPackStore(null))

        service.onPanelChange(PanelMode.WEB_SEARCH)

        val state = service.uiState.value
        assertEquals(WebSearchUi.Idle, state.webSearch)
        assertEquals("", state.mediaQuery)
        assertTrue(state.mediaSearchActive)
    }
}
