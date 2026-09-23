package dev.smartdisplay.app.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ServerAddressTest {

    @Test
    fun `bare IP gets http and the default port`() {
        assertEquals("http://192.168.1.20:8123", normalizeAddress("192.168.1.20"))
    }

    @Test
    fun `host name gets http and the default port`() {
        assertEquals("http://homeassistant.local:8123", normalizeAddress("  homeassistant.local "))
    }

    @Test
    fun `typed port is kept`() {
        assertEquals("http://ha.lan:8124", normalizeAddress("ha.lan:8124"))
    }

    @Test
    fun `typed scheme keeps its own default port`() {
        assertEquals("https://ha.example.com", normalizeAddress("https://ha.example.com/"))
        assertEquals("http://ha.lan", normalizeAddress("http://ha.lan"))
    }

    @Test
    fun `path, query and credentials are dropped`() {
        assertEquals(
            "http://192.168.1.20:8123",
            normalizeAddress("http://user:pw@192.168.1.20:8123/lovelace/0?kiosk#top"),
        )
    }

    @Test
    fun `host names are lower-cased`() {
        assertEquals("http://homeassistant.local:8123", normalizeAddress("HomeAssistant.local"))
    }

    @Test
    fun `IPv6 literal gets the default port`() {
        assertEquals("http://[fd00::20]:8123", normalizeAddress("[fd00::20]"))
    }

    @Test
    fun `bare address tries the default port, then http, then https`() {
        assertEquals(
            listOf("http://192.168.1.30:8123", "http://192.168.1.30", "https://192.168.1.30"),
            addressCandidates("192.168.1.30"),
        )
    }

    @Test
    fun `typed scheme or port is the only candidate`() {
        assertEquals(listOf("http://192.168.1.30"), addressCandidates("http://192.168.1.30"))
        assertEquals(listOf("http://192.168.1.30:8124"), addressCandidates("192.168.1.30:8124"))
    }

    @Test
    fun `no candidates for nonsense`() {
        assertEquals(emptyList<String>(), addressCandidates("not an address"))
    }

    @Test
    fun `nonsense is rejected`() {
        assertNull(normalizeAddress(""))
        assertNull(normalizeAddress("   "))
        assertNull(normalizeAddress("home assistant"))
        assertNull(normalizeAddress("ftp://ha.lan"))
        assertNull(normalizeAddress("http://"))
    }
}
