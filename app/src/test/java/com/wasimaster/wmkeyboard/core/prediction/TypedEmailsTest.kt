package com.wasimaster.wmkeyboard.core.prediction

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TypedEmailsTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `only whole addresses are learned`() {
        val emails = TypedEmails()
        assertFalse(emails.record("john"))
        assertFalse(emails.record("john@"))
        assertFalse(emails.record("john@example"))
        assertFalse(emails.record("a b@example.com"))
        assertFalse(emails.record("a@b@example.com"))
        assertTrue(emails.record("  John.Doe@Example.com "))
        assertEquals(listOf("john.doe@example.com"), emails.complete("", 5))
    }

    @Test
    fun `an empty field offers the most used first, a prefix narrows it`() {
        val emails = TypedEmails()
        emails.record("work@company.com", now = 1)
        emails.record("me@mail.com", now = 2)
        emails.record("work@company.com", now = 3)
        emails.record("mum@mail.com", now = 4)
        assertEquals(listOf("work@company.com", "mum@mail.com", "me@mail.com"), emails.complete("", 5))
        assertEquals(listOf("mum@mail.com", "me@mail.com"), emails.complete("M", 5))
        // The address already typed in full has nothing left to complete.
        assertEquals(emptyList<String>(), emails.complete("me@mail.com", 5))
    }

    @Test
    fun `the least recently used falls off past the cap`() {
        val emails = TypedEmails()
        for (i in 0..TypedEmails.MAX_ADDRESSES) emails.record("u$i@mail.com", now = i.toLong())
        assertFalse("u0@mail.com" in emails.complete("u", 100))
        assertEquals(TypedEmails.MAX_ADDRESSES, emails.complete("", 100).size)
    }

    @Test
    fun `survives a save and a reload, and clear forgets`() {
        val file = File(temp.root, "typed_emails.json")
        TypedEmails(file).apply {
            record("me@mail.com")
            save()
        }
        val again = TypedEmails(file)
        assertEquals(listOf("me@mail.com"), again.complete("", 5))
        again.clear()
        assertTrue(again.isEmpty)
        assertFalse(file.exists())
        assertNull(TypedEmails.normalize("nobody"))
    }
}
