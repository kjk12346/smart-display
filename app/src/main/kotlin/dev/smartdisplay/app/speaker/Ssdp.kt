package dev.smartdisplay.app.speaker

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import java.net.DatagramPacket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.SocketAddress
import java.net.SocketTimeoutException
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** This tablet's address on the local network, and the interface it's on; null without one. */
fun localAddress(): Pair<Inet4Address, NetworkInterface>? {
    val candidates = NetworkInterface.getNetworkInterfaces().toList()
        .filter { it.isUp && !it.isLoopback && !it.isVirtual }
        .flatMap { nif -> nif.inetAddresses.toList().filterIsInstance<Inet4Address>().map { it to nif } }
        .filter { (address, _) -> address.isSiteLocalAddress }
    // Wi-Fi first (wlan0), then wired (eth0), then anything else private.
    return candidates.minByOrNull { (_, nif) ->
        when {
            nif.name.startsWith("wlan") -> 0
            nif.name.startsWith("eth") -> 1
            else -> 2
        }
    }
}

/**
 * Announces the speaker on the network the way UPnP devices do (SSDP): "alive" when it starts and every few minutes,
 * answers to searches (Home Assistant searches every so often, and when its DLNA integration is set up), and
 * "byebye" when it stops. Android drops multicast on Wi-Fi unless a multicast lock is held, so one is, while this
 * runs.
 */
class Ssdp(
    private val context: Context,
    private val udn: String,
    private val location: String,
    private val networkInterface: NetworkInterface,
) {
    private val targets = listOf("upnp:rootdevice", "uuid:$udn", MEDIA_RENDERER) + SERVICES.map { it.type }

    suspend fun run() = withContext(Dispatchers.IO) {
        val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
        val lock = wifi?.createMulticastLock("speaker")?.apply {
            setReferenceCounted(false)
            acquire()
        }
        val socket = MulticastSocket(null).apply {
            reuseAddress = true
            bind(InetSocketAddress(PORT))
            joinGroup(InetSocketAddress(GROUP, PORT), networkInterface)
            this.networkInterface = this@Ssdp.networkInterface
            timeToLive = 4
            soTimeout = 1_000
        }
        try {
            launch {
                while (isActive) {
                    targets.forEach { send(socket, notify(it, "ssdp:alive"), GROUP_ADDRESS) }
                    delay(ANNOUNCE_MS)
                }
            }
            val buffer = ByteArray(2048)
            while (isActive) {
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(packet)
                } catch (e: SocketTimeoutException) {
                    continue
                }
                val message = String(packet.data, 0, packet.length, Charsets.ISO_8859_1)
                val replies = searchReplies(message) ?: continue
                val sender = packet.socketAddress
                val wait = mx(message)
                launch {
                    // Spread over the time the searcher allows, as UPnP asks.
                    delay(Random.nextLong(0, wait * 1000L + 1))
                    replies.forEach { send(socket, it, sender) }
                }
            }
        } finally {
            targets.forEach { runCatching { send(socket, notify(it, "ssdp:byebye"), GROUP_ADDRESS) } }
            runCatching { socket.leaveGroup(InetSocketAddress(GROUP, PORT), networkInterface) }
            socket.close()
            lock?.release()
        }
    }

    /** Our answers to an M-SEARCH, or null if [message] isn't one we answer. */
    private fun searchReplies(message: String): List<String>? {
        if (!message.startsWith("M-SEARCH")) return null
        val headers = ssdpHeaders(message)
        if (headers["man"]?.trim('"') != "ssdp:discover") return null
        val st = headers["st"] ?: return null
        val matching = if (st == "ssdp:all") targets else targets.filter { it == st }
        return matching.map { target ->
            buildString {
                append("HTTP/1.1 200 OK\r\n")
                append("CACHE-CONTROL: max-age=$MAX_AGE\r\n")
                append("EXT:\r\n")
                append("LOCATION: $location\r\n")
                append("SERVER: $SERVER_HEADER\r\n")
                append("ST: $target\r\n")
                append("USN: ${usn(target)}\r\n")
                append("\r\n")
            }
        }
    }

    private fun notify(target: String, kind: String) = buildString {
        append("NOTIFY * HTTP/1.1\r\n")
        append("HOST: $GROUP:$PORT\r\n")
        if (kind == "ssdp:alive") {
            append("CACHE-CONTROL: max-age=$MAX_AGE\r\n")
            append("LOCATION: $location\r\n")
            append("SERVER: $SERVER_HEADER\r\n")
        }
        append("NT: $target\r\n")
        append("NTS: $kind\r\n")
        append("USN: ${usn(target)}\r\n")
        append("\r\n")
    }

    private fun usn(target: String) = if (target == "uuid:$udn") target else "uuid:$udn::$target"

    private fun send(socket: MulticastSocket, message: String, to: SocketAddress) {
        val bytes = message.toByteArray(Charsets.ISO_8859_1)
        try {
            socket.send(DatagramPacket(bytes, bytes.size, to))
        } catch (e: Exception) {
            Log.w(TAG, "SSDP send failed", e)
        }
    }

    private companion object {
        const val TAG = "SpeakerSsdp"
        const val GROUP = "239.255.255.250"
        const val PORT = 1900
        const val MAX_AGE = 1800
        const val ANNOUNCE_MS = 5 * 60_000L
        val GROUP_ADDRESS = InetSocketAddress(InetAddress.getByName(GROUP), PORT)
    }
}

/** An SSDP message's headers, names lower case. */
fun ssdpHeaders(message: String): Map<String, String> = message.split("\r\n", "\n").drop(1)
    .mapNotNull { line ->
        val colon = line.indexOf(':')
        if (colon <= 0) null else line.substring(0, colon).trim().lowercase() to line.substring(colon + 1).trim()
    }
    .toMap()

/** How long (seconds, 1–3) a search lets us take to answer. */
private fun mx(message: String): Int = ssdpHeaders(message)["mx"]?.toIntOrNull()?.coerceIn(1, 3) ?: 1
