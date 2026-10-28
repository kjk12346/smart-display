package dev.smartdisplay.app.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsSearchTest {
    private val wifi = listOf("Guest Wi-Fi", "Guest Wi-Fi", "network password qr code")

    @Test
    fun `every word must appear, ignoring case and hyphens`() {
        assertTrue(searchMatches("wifi", wifi))
        assertTrue(searchMatches("WI-FI password", wifi))
        assertTrue(searchMatches("  pass ", wifi))
        assertFalse(searchMatches("wifi alarm", wifi))
    }

    @Test
    fun `accents and punctuation don't count, and an empty search finds nothing`() {
        assertTrue(searchMatches("cafe", listOf("Café")))
        assertTrue(searchMatches("guest's", listOf("Guest's room")))
        assertFalse(searchMatches("   ", wifi))
    }

    @Test
    fun `each page's settings are listed together, in page order`() {
        val pages = SEARCH_TOPICS.map { it.page }.distinct()
        assertEquals(pages.sorted(), pages)
        assertEquals(SettingsPage.entries.toList(), pages)
    }
}
