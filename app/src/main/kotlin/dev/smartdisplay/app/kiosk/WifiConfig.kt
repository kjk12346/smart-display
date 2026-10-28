package dev.smartdisplay.app.kiosk

/** How the network is secured, as a Wi-Fi QR code says it. */
enum class WifiSecurity { Wpa, Wep, Open }

/**
 * The Wi-Fi network guests join, shown as a QR code their phone's camera can scan. Kept on the display as typed:
 * it's shown to anyone in the room anyway.
 */
data class WifiConfig(
    val ssid: String? = null,
    val password: String = "",
    val security: WifiSecurity = WifiSecurity.Wpa,
    val hidden: Boolean = false,
) {
    val isSet: Boolean get() = !ssid.isNullOrBlank()
}

/**
 * The text of a Wi-Fi QR code, in the format phone cameras read: `WIFI:T:WPA;S:name;P:password;;`. Backslashes,
 * semicolons, commas, colons and double quotes in the name and password are escaped with a backslash. Null when no
 * network is set.
 */
fun wifiQrText(config: WifiConfig): String? {
    val ssid = config.ssid?.takeIf { it.isNotBlank() } ?: return null
    val type = when (config.security) {
        WifiSecurity.Wpa -> "WPA"
        WifiSecurity.Wep -> "WEP"
        WifiSecurity.Open -> "nopass"
    }
    return buildString {
        append("WIFI:T:").append(type)
        append(";S:").append(escapeWifi(ssid))
        if (config.security != WifiSecurity.Open) append(";P:").append(escapeWifi(config.password))
        if (config.hidden) append(";H:true")
        append(";;")
    }
}

private fun escapeWifi(text: String): String = buildString {
    text.forEach { c ->
        if (c in "\\;,:\"") append('\\')
        append(c)
    }
}
