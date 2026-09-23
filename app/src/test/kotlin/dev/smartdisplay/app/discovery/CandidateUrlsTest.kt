package dev.smartdisplay.app.discovery

import org.junit.Assert.assertEquals
import org.junit.Test

class CandidateUrlsTest {

    @Test
    fun `internal URL first, then the found address, then external`() {
        val txt = mapOf(
            "internal_url" to "http://homeassistant.local:8123",
            "base_url" to "http://homeassistant.local:8123",
            "external_url" to "https://abc.ui.nabu.casa",
        )
        assertEquals(
            listOf(
                "http://homeassistant.local:8123",
                "http://192.168.1.20:8123",
                "https://abc.ui.nabu.casa",
            ),
            candidateUrls(txt, "192.168.1.20", 8123),
        )
    }

    @Test
    fun `found address alone when the TXT record has no URLs`() {
        assertEquals(listOf("http://192.168.1.20:8123"), candidateUrls(emptyMap(), "192.168.1.20", 8123))
    }

    @Test
    fun `blank TXT values are skipped and duplicates merged`() {
        val txt = mapOf("internal_url" to "", "base_url" to "http://192.168.1.20:8123/")
        assertEquals(listOf("http://192.168.1.20:8123"), candidateUrls(txt, "192.168.1.20", 8123))
    }

    @Test
    fun `IPv6 address is bracketed`() {
        assertEquals(listOf("http://[fd00::20]:8123"), candidateUrls(emptyMap(), "fd00::20", 8123))
    }

    @Test
    fun `nothing usable gives an empty list`() {
        assertEquals(emptyList<String>(), candidateUrls(emptyMap(), null, 8123))
    }
}
