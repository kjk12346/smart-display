package dev.smartdisplay.app.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

class DiscoveryException(val errorCode: Int) : Exception("NSD discovery failed: $errorCode")

/** Finds Home Assistant servers on the local network over mDNS / DNS-SD, the way Home Assistant's own apps do. */
class HomeAssistantDiscovery(context: Context) {
    private val nsd = context.getSystemService(NsdManager::class.java)

    /**
     * Searches while collected, emitting every server found so far (starting with an empty list). Fails with
     * [DiscoveryException] if the search can't start.
     */
    fun servers(): Flow<List<DiscoveredServer>> = channelFlow {
        val found = ConcurrentHashMap<String, DiscoveredServer>()
        fun snapshot() = found.values.sortedBy { it.name.lowercase() }

        // Before API 34 only one resolve can run at a time, so found services are resolved one by one.
        val toResolve = Channel<NsdServiceInfo>(Channel.UNLIMITED)

        val listener = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(info: NsdServiceInfo) {
                toResolve.trySend(info)
            }

            override fun onServiceLost(info: NsdServiceInfo) {
                if (found.remove(info.serviceName) != null) trySend(snapshot())
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                close(DiscoveryException(errorCode))
            }

            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        }

        send(emptyList())
        nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)

        launch {
            for (info in toResolve) {
                val server = resolve(info)?.toServer() ?: continue
                found[server.serviceName] = server
                send(snapshot())
            }
        }

        awaitClose {
            toResolve.close()
            runCatching { nsd.stopServiceDiscovery(listener) }
        }
    }

    private suspend fun resolve(info: NsdServiceInfo): NsdServiceInfo? {
        repeat(RESOLVE_ATTEMPTS) {
            when (val result = resolveOnce(info)) {
                is ResolveResult.Resolved -> return result.info
                is ResolveResult.Busy -> delay(RESOLVE_RETRY_MS)
                is ResolveResult.Failed -> return null
            }
        }
        return null
    }

    private sealed interface ResolveResult {
        data class Resolved(val info: NsdServiceInfo) : ResolveResult
        data object Busy : ResolveResult
        data object Failed : ResolveResult
    }

    // resolveService is deprecated from API 34 but still works, and is the only option below it.
    @Suppress("DEPRECATION")
    private suspend fun resolveOnce(info: NsdServiceInfo): ResolveResult =
        withTimeoutOrNull(RESOLVE_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                nsd.resolveService(info, object : NsdManager.ResolveListener {
                    override fun onServiceResolved(resolved: NsdServiceInfo) {
                        cont.resume(ResolveResult.Resolved(resolved))
                    }

                    override fun onResolveFailed(failed: NsdServiceInfo, errorCode: Int) {
                        cont.resume(
                            if (errorCode == NsdManager.FAILURE_ALREADY_ACTIVE) ResolveResult.Busy
                            else ResolveResult.Failed
                        )
                    }
                })
            }
        } ?: ResolveResult.Failed

    private fun NsdServiceInfo.toServer(): DiscoveredServer? {
        val txt = attributes.mapNotNull { (key, value) ->
            value?.let { key to it.decodeToString() }
        }.toMap()
        val urls = candidateUrls(txt, preferredAddress()?.hostAddress, port)
        if (urls.isEmpty()) return null
        return DiscoveredServer(
            serviceName = serviceName,
            name = txt["location_name"]?.takeIf { it.isNotBlank() } ?: serviceName,
            version = txt["version"],
            uuid = txt["uuid"],
            urls = urls,
        )
    }

    // IPv4 first: an IPv6 link-local address needs a scope ID that URLs can't carry.
    private fun NsdServiceInfo.preferredAddress(): InetAddress? {
        val addresses = if (Build.VERSION.SDK_INT >= 34) {
            hostAddresses
        } else {
            @Suppress("DEPRECATION")
            listOfNotNull(host)
        }
        return addresses.firstOrNull { it is Inet4Address }
            ?: addresses.firstOrNull { !it.isLinkLocalAddress }
    }

    private companion object {
        const val SERVICE_TYPE = "_home-assistant._tcp"
        const val RESOLVE_ATTEMPTS = 5
        const val RESOLVE_RETRY_MS = 500L
        const val RESOLVE_TIMEOUT_MS = 10_000L
    }
}
