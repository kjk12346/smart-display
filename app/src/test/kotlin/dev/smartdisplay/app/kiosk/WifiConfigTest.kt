package dev.smartdisplay.app.kiosk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WifiConfigTest {
    @Test
    fun `a secured network gives its type, name and password`() {
        assertEquals("WIFI:T:WPA;S:Home;P:hunter22;;", wifiQrText(WifiConfig("Home", "hunter22")))
        assertEquals("WIFI:T:WEP;S:Old;P:abc;;", wifiQrText(WifiConfig("Old", "abc", WifiSecurity.Wep)))
    }

    @Test
    fun `an open network has no password, and a hidden one says so`() {
        assertEquals("WIFI:T:nopass;S:Cafe;;", wifiQrText(WifiConfig("Cafe", "ignored", WifiSecurity.Open)))
        assertEquals("WIFI:T:WPA;S:Attic;P:pw;H:true;;", wifiQrText(WifiConfig("Attic", "pw", hidden = true)))
    }

    @Test
    fun `special characters are escaped`() {
        assertEquals(
            """WIFI:T:WPA;S:My\;Net\,1;P:a\:b\\c\"d;;""",
            wifiQrText(WifiConfig("My;Net,1", """a:b\c"d""")),
        )
    }

    @Test
    fun `no network, no code`() {
        assertNull(wifiQrText(WifiConfig()))
        assertNull(wifiQrText(WifiConfig("  ", "pw")))
    }
}
