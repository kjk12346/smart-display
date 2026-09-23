package dev.smartdisplay.app.server

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Home Assistant's default port. */
const val DEFAULT_PORT = 8123

private val explicitPort = Regex(":\\d+$")

/**
 * Turns what someone typed ("192.168.1.20", "homeassistant.local:8123", "https://ha.example.com/") into a base URL
 * with no trailing slash, or null if it can't be one.
 *
 * With no scheme, http:// is assumed; with no scheme and no port, Home Assistant's default port 8123 is too. An
 * explicit scheme without a port keeps that scheme's default (a reverse proxy on 443, say). Any path is dropped:
 * Home Assistant can't be served from a sub-path.
 */
fun normalizeAddress(input: String): String? {
    val text = input.trim()
    if (text.isEmpty() || text.any { it.isWhitespace() }) return null

    val url = (if (hasScheme(text)) text else "http://$text").toHttpUrlOrNull() ?: return null
    val port = if (hasScheme(text) || hasPort(text)) url.port else DEFAULT_PORT

    return url.newBuilder()
        .port(port)
        .encodedPath("/")
        .query(null)
        .fragment(null)
        .username("")
        .password("")
        .build()
        .toString()
        .trimEnd('/')
}

/**
 * The base URLs to try for a typed address, best first. A bare host or IP gets the default port first, then plain
 * http and https on their standard ports (Home Assistant behind a proxy, or a VM with port 80 forwarded). A typed
 * scheme or port is taken as meant.
 */
fun addressCandidates(input: String): List<String> {
    val text = input.trim()
    val first = normalizeAddress(text) ?: return emptyList()
    if (hasScheme(text) || hasPort(text)) return listOf(first)
    return listOfNotNull(first, normalizeAddress("http://$text"), normalizeAddress("https://$text")).distinct()
}

private fun hasScheme(text: String) = "://" in text

private fun hasPort(text: String): Boolean {
    val authority = text.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore('#')
    return explicitPort.containsMatchIn(authority)
}
