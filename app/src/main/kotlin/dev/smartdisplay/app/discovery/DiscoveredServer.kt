package dev.smartdisplay.app.discovery

import dev.smartdisplay.app.server.normalizeAddress

/** A Home Assistant server found on the local network. */
data class DiscoveredServer(
    /** The mDNS service name, unique on the network. */
    val serviceName: String,
    /** The name set in Home Assistant (Settings > System > General), or the service name. */
    val name: String,
    val version: String?,
    val uuid: String?,
    /** Base URLs to try, best first. Never empty. */
    val urls: List<String>,
)

/**
 * Builds the URLs to try for a server from its TXT record and resolved address, best first: the URL Home Assistant
 * says to use locally, then the address it was found at (older tablets can't resolve `.local` names), then its
 * external URL as a last resort.
 */
internal fun candidateUrls(txt: Map<String, String>, hostAddress: String?, port: Int): List<String> {
    val direct = hostAddress?.let { host ->
        if (':' in host) "http://[$host]:$port" else "http://$host:$port"
    }
    return listOfNotNull(txt["internal_url"], txt["base_url"], direct, txt["external_url"])
        .filter { it.isNotBlank() }
        .mapNotNull { normalizeAddress(it) }
        .distinct()
}
