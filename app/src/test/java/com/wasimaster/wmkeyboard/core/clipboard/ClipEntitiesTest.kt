package com.wasimaster.wmkeyboard.core.clipboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fragment detection: one-time codes, phone numbers and links inside clips. */
class ClipEntitiesTest {

    private fun values(text: String, kind: ClipEntityKind) =
        ClipEntities.extract(text).filter { it.kind == kind }.map { it.value }

    // ---- one-time codes ----

    @Test fun findsCodeAfterItsKeyword() {
        assertEquals(
            listOf("483920"),
            values("483920 is your verification code. Do not share it.", ClipEntityKind.OTP),
        )
        assertEquals(
            listOf("2931"),
            values("Your OTP is 2931", ClipEntityKind.OTP),
        )
    }

    @Test fun findsGoogleStyleAlphanumericCode() {
        assertEquals(listOf("123456"), values("G-123456 is your Google code", ClipEntityKind.OTP))
        assertEquals(listOf("A1B2C3"), values("Login code: A1B2C3", ClipEntityKind.OTP))
    }

    @Test fun needsAKeywordAtAll() {
        assertEquals(emptyList<String>(), values("Meet me at 483920 Elm Street", ClipEntityKind.OTP))
    }

    @Test fun keywordMustBeNearTheDigits() {
        val far = "Your code is on the way. " + "filler ".repeat(12) + "483920"
        assertEquals(emptyList<String>(), values(far, ClipEntityKind.OTP))
    }

    @Test fun ignoresWordsAndYearsAndPostcodes() {
        // No digits at all.
        assertEquals(emptyList<String>(), values("Enter the code PLEASE", ClipEntityKind.OTP))
        // A bare year in prose is not the code.
        assertEquals(emptyList<String>(), values("Discount code valid until 2027", ClipEntityKind.OTP))
        // "zip code" is a denied keyword.
        assertEquals(emptyList<String>(), values("Zip code 90210", ClipEntityKind.OTP))
    }

    // ---- phone numbers ----

    @Test fun findsPhoneNumbersInProse() {
        assertEquals(
            listOf("+1 (555) 123-4567"),
            values("Call me on +1 (555) 123-4567 tomorrow", ClipEntityKind.PHONE),
        )
        assertEquals(listOf("01711234567"), values("mobile 01711234567 please", ClipEntityKind.PHONE))
    }

    @Test fun hyphensWorkAsSeparators() {
        assertEquals(listOf("555-123-4567"), values("ring 555-123-4567 today", ClipEntityKind.PHONE))
        assertEquals(listOf("+44-20-7946-0958"), values("try +44-20-7946-0958 now", ClipEntityKind.PHONE))
        assertEquals(listOf("01711-234567"), values("mobile 01711-234567 ok", ClipEntityKind.PHONE))
        // Date-shaped but seven digits long, so it is a number, not a date.
        assertEquals(listOf("555-12-3456"), values("call 555-12-3456 please", ClipEntityKind.PHONE))
    }

    @Test fun rejectsDatesAndLongReferenceNumbers() {
        assertEquals(emptyList<String>(), values("Delivery on 2026-05-12 as agreed", ClipEntityKind.PHONE))
        assertEquals(emptyList<String>(), values("Order #12345678901234 shipped", ClipEntityKind.PHONE))
    }

    @Test fun tooFewDigitsIsNotAPhoneNumber() {
        assertEquals(emptyList<String>(), values("suite 12-345", ClipEntityKind.PHONE))
    }

    @Test fun digitsInsideAnIdentifierAreNotAPhoneNumber() {
        // The tail of a generated filename shares a dialable length.
        assertEquals(emptyList<String>(), values("saved as emoji_864356927", ClipEntityKind.PHONE))
        assertEquals(emptyList<String>(), values("build 864356927beta ready", ClipEntityKind.PHONE))
        assertEquals(emptyList<String>(), values("token 864356927_v2 issued", ClipEntityKind.PHONE))
    }

    // ---- links ----

    @Test fun findsLinkInsideASentence() {
        assertEquals(
            listOf("https://example.com/a?b=1"),
            values("see https://example.com/a?b=1 for details", ClipEntityKind.URL),
        )
        assertEquals(listOf("www.example.com"), values("go to www.example.com now", ClipEntityKind.URL))
    }

    @Test fun dropsTrailingSentencePunctuationButKeepsPairedBrackets() {
        assertEquals(
            listOf("https://example.com/page"),
            values("Read https://example.com/page.", ClipEntityKind.URL),
        )
        assertEquals(
            listOf("https://en.wikipedia.org/wiki/Foo_(bar)"),
            values("Read https://en.wikipedia.org/wiki/Foo_(bar) today", ClipEntityKind.URL),
        )
        assertEquals(
            listOf("https://example.com/page"),
            values("Read (https://example.com/page) today", ClipEntityKind.URL),
        )
    }

    @Test fun digitsInsideALinkAreNotAPhoneNumber() {
        val found = ClipEntities.extract("open https://example.com/id/5551234567 now")
        assertEquals(listOf(ClipEntityKind.URL), found.map { it.kind })
    }

    // ---- spans and ordering ----

    @Test fun spanPointsAtTheFragmentInsideItsClip() {
        val text = "Your code is 483920, do not share"
        val entity = ClipEntities.extract(text, sourceId = 7).single()
        assertEquals(7L, entity.sourceId)
        assertEquals(text, entity.sourceText)
        assertEquals("483920", text.substring(entity.start, entity.end))
    }

    @Test fun codesComeBeforePhonesBeforeLinks() {
        val store = ClipboardStore(null)
        val now = System.currentTimeMillis()
        store.add("visit https://example.com/help for more", now = now)
        store.add("ring me on +1 (555) 123-4567 later", now = now)
        store.add("Your verification code is 998877 — do not share", now = now)
        assertEquals(
            listOf(ClipEntityKind.OTP, ClipEntityKind.PHONE, ClipEntityKind.URL),
            ClipEntities.entitiesIn(store.items(now)).map { it.kind },
        )
    }

    // ---- the "part of an entry" rule ----

    @Test fun aClipThatIsOnlyTheFragmentGetsNoChip() {
        val store = ClipboardStore(null)
        val now = System.currentTimeMillis()
        // The card already pastes this; a chip would be a duplicate claiming to
        // be a fragment of something.
        store.add("+1 (555) 123-4567", now = now)
        store.add("https://example.com/page", now = now)
        assertEquals(emptyList<ClipEntityKind>(), ClipEntities.entitiesIn(store.items(now)).map { it.kind })
    }

    @Test fun sameFragmentInTwoClipsAppearsOnce() {
        val store = ClipboardStore(null)
        val now = System.currentTimeMillis()
        store.add("code 445566 expires soon", now = now)
        store.add("your code 445566 again", now = now)
        assertEquals(listOf("445566"), ClipEntities.entitiesIn(store.items(now)).map { it.value })
    }

    @Test fun eachClipKeepsItsOwnParts() {
        // #472: the cards count what is in them, so a part two clips share is
        // on both, and one clip lists every part it holds.
        val store = ClipboardStore(null)
        val now = System.currentTimeMillis()
        store.add("code 445566 expires soon", now = now)
        store.add("your code 445566, see https://a.example/x and https://b.example/y", now = now)
        store.add("https://only.example/link", now = now)
        val byClip = ClipEntities.byClip(store.items(now))
        val items = store.items(now)
        assertEquals(
            listOf(ClipEntityKind.OTP, ClipEntityKind.URL, ClipEntityKind.URL),
            byClip[items.first { it.text.startsWith("your") }.id]!!.map { it.kind },
        )
        assertEquals(listOf("445566"), byClip[items.first { it.text.startsWith("code") }.id]!!.map { it.value })
        // A clip that is only its fragment has nothing to extract.
        assertTrue(items.first { it.text.startsWith("https://only") }.id !in byClip)
    }

    @Test fun imageAndFileClipsAreNotScanned() {
        val store = ClipboardStore(null)
        val now = System.currentTimeMillis()
        store.addUri("content://d/1", "code 445566.pdf", "application/pdf", false, 1, now = now)
        assertTrue(ClipEntities.entitiesIn(store.items(now)).isEmpty())
    }
}
