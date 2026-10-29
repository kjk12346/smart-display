package dev.smartdisplay.app.speaker

import android.content.Context
import android.database.ContentObserver
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings as AndroidSettings
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** What the speaker is playing, for the now-playing bar. */
data class NowPlaying(val track: TrackInfo, val playing: Boolean)

/**
 * The display as a network speaker (a DLNA media renderer): Home Assistant finds it on the network (SSDP), and its
 * DLNA integration then plays to it like any other speaker, from the media panel, automations or Music Assistant.
 * One stream at a time, played with Media3; volume is Android's media volume.
 *
 * Runs in the app while [configure]d on, whichever screen is showing. The player is only touched on the main thread.
 */
class Speaker(
    private val context: Context,
    private val http: OkHttpClient,
    private val scope: CoroutineScope,
    private val version: String,
) {
    /** On or off, the name Home Assistant shows, and the speaker's lasting identity (its UPnP UDN). */
    data class Settings(val enabled: Boolean, val name: String, val udn: String)

    private var settings: Settings? = null
    private var job: Job? = null

    private val _nowPlaying = MutableStateFlow<NowPlaying?>(null)

    /** Null unless something is playing or paused (not once stopped or finished). */
    val nowPlaying: StateFlow<NowPlaying?> = _nowPlaying.asStateFlow()

    private val audio = context.getSystemService(AudioManager::class.java)
    private var player: ExoPlayer? = null
    private var uri: String? = null
    private var metadata = ""
    private var track = TrackInfo()

    /** Play was asked for since the stream was set or stopped: ready but not playing is then paused, not stopped. */
    private var started = false
    private val subscriptions = ConcurrentHashMap<String, Subscription>()
    private var lastAvt: List<Pair<String, String>>? = null

    /** Starts, restarts (a new name) or stops the speaker. Call on the main thread. */
    fun configure(new: Settings) {
        if (new == settings) return
        settings = new
        val old = job
        old?.cancel()
        // The old server lets go of its port before the new one takes it, so a rename keeps the same address.
        job = if (new.enabled) scope.launch { old?.join(); run(new) } else null
        if (!new.enabled) stop()
    }

    fun togglePause() {
        val p = player ?: return
        if (p.isPlaying) p.pause() else if (uri != null) play()
    }

    fun stop() {
        player?.stop()
        started = false
        changed()
    }

    private suspend fun run(settings: Settings) {
        // The volume buttons (or anything else) changing the media volume: Home Assistant should see it too.
        val volumeWatch = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) = notifyRendering()
        }
        context.contentResolver.registerContentObserver(AndroidSettings.System.CONTENT_URI, true, volumeWatch)
        try {
            serve(settings)
        } finally {
            context.contentResolver.unregisterContentObserver(volumeWatch)
        }
    }

    private suspend fun serve(settings: Settings) {
        while (true) {
            val (address, networkInterface) = localAddress() ?: run {
                Log.w(TAG, "No local network address; trying again in a minute")
                delay(60_000)
                null
            } ?: continue
            val server = MiniHttpServer { request -> answer(settings, request) }
            val port = withContext(Dispatchers.IO) { server.bind(PREFERRED_PORT) }
            val location = "http://${address.hostAddress}:$port/description.xml"
            Log.i(TAG, "Speaker \"${settings.name}\" at $location")
            try {
                coroutineScope {
                    launch { server.serve() }
                    launch { Ssdp(context, settings.udn, location, networkInterface).run() }
                    // An address that changes (a new DHCP lease, another network) means starting again with it.
                    while (localAddress()?.first == address) delay(60_000)
                    Log.i(TAG, "Network address changed; restarting")
                    coroutineContext.cancelChildren()
                }
            } finally {
                server.stop()
            }
        }
    }

    private suspend fun answer(settings: Settings, request: HttpRequest): HttpResponse {
        val service = SERVICES.firstOrNull { request.path.startsWith("/${it.name}") }
        return when {
            request.method == "GET" && request.path == "/description.xml" ->
                HttpResponse.ok(deviceDescription(settings.name, settings.udn, version))
            request.method == "GET" && service != null && request.path == service.scpdPath ->
                HttpResponse.ok(serviceDescription(service))
            request.method == "POST" && service != null && request.path == service.controlPath ->
                control(service, request)
            request.method == "SUBSCRIBE" && service != null && request.path == service.eventPath ->
                subscribe(service, request)
            request.method == "UNSUBSCRIBE" && service != null && request.path == service.eventPath -> {
                val sid = request.headers["sid"]
                if (sid != null && subscriptions.remove(sid) != null) {
                    HttpResponse(200, "OK")
                } else {
                    HttpResponse(412, "Precondition Failed")
                }
            }
            else -> HttpResponse.NOT_FOUND
        }
    }

    private suspend fun control(service: UpnpService, request: HttpRequest): HttpResponse {
        val call = parseSoapCall(request.body) ?: return fault(402, "Invalid Args")
        if (service.actions.none { it.name == call.action }) return fault(401, "Invalid Action")
        return withContext(Dispatchers.Main) {
            try {
                val outputs = when (service) {
                    AV_TRANSPORT_SERVICE -> transport(call)
                    RENDERING_CONTROL_SERVICE -> rendering(call)
                    else -> connection(call)
                }
                HttpResponse.ok(soapResponse(service.type, call.action, outputs))
            } catch (e: UpnpError) {
                fault(e.code, e.message.orEmpty())
            }
        }
    }

    private fun transport(call: SoapCall): List<Pair<String, String>> = when (call.action) {
        "SetAVTransportURI" -> {
            val newUri = call.args["CurrentURI"]?.trim().orEmpty()
            if (newUri.isEmpty()) throw UpnpError(716, "Resource not found")
            uri = newUri
            metadata = call.args["CurrentURIMetaData"].orEmpty()
            track = parseDidl(metadata)
            started = false
            playerOrCreate().apply {
                setMediaItem(MediaItem.fromUri(newUri))
                playWhenReady = false
                prepare()
            }
            Log.i(TAG, "Stream set: ${track.title ?: newUri}")
            changed()
            emptyList()
        }
        "Play" -> {
            if (uri == null) throw UpnpError(701, "Transition not available")
            play()
            emptyList()
        }
        "Pause" -> {
            player?.pause()
            changed()
            emptyList()
        }
        "Stop" -> {
            stop()
            emptyList()
        }
        "Seek" -> {
            val p = player ?: throw UpnpError(701, "Transition not available")
            val unit = call.args["Unit"]
            if (unit != "REL_TIME" && unit != "ABS_TIME") throw UpnpError(710, "Seek mode not supported")
            val target = call.args["Target"]?.let(::parseUpnpTime) ?: throw UpnpError(711, "Illegal seek target")
            p.seekTo(target)
            changed()
            emptyList()
        }
        "GetTransportInfo" -> listOf(
            "CurrentTransportState" to transportState(),
            "CurrentTransportStatus" to "OK",
            "CurrentSpeed" to "1",
        )
        "GetPositionInfo" -> listOf(
            "Track" to if (uri != null) "1" else "0",
            "TrackDuration" to duration(),
            "TrackMetaData" to metadata,
            "TrackURI" to uri.orEmpty(),
            "RelTime" to formatUpnpTime(player?.currentPosition ?: 0),
            "AbsTime" to formatUpnpTime(player?.currentPosition ?: 0),
            "RelCount" to NOT_COUNTED,
            "AbsCount" to NOT_COUNTED,
        )
        "GetMediaInfo" -> listOf(
            "NrTracks" to if (uri != null) "1" else "0",
            "MediaDuration" to duration(),
            "CurrentURI" to uri.orEmpty(),
            "CurrentURIMetaData" to metadata,
            "NextURI" to "",
            "NextURIMetaData" to "",
            "PlayMedium" to if (uri != null) "NETWORK" else "NONE",
            "RecordMedium" to "NOT_IMPLEMENTED",
            "WriteStatus" to "NOT_IMPLEMENTED",
        )
        "GetDeviceCapabilities" -> listOf(
            "PlayMedia" to "NETWORK",
            "RecMedia" to "NOT_IMPLEMENTED",
            "RecQualityModes" to "NOT_IMPLEMENTED",
        )
        "GetTransportSettings" -> listOf("PlayMode" to "NORMAL", "RecQualityMode" to "NOT_IMPLEMENTED")
        "GetCurrentTransportActions" -> listOf("Actions" to transportActions())
        else -> throw UpnpError(401, "Invalid Action")
    }

    private fun rendering(call: SoapCall): List<Pair<String, String>> = when (call.action) {
        "GetVolume" -> listOf("CurrentVolume" to volume().toString())
        "SetVolume" -> {
            val wanted = call.args["DesiredVolume"]?.toIntOrNull()?.coerceIn(0, 100)
                ?: throw UpnpError(402, "Invalid Args")
            val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, (wanted * max / 100.0).roundToInt(), 0)
            notifyRendering()
            emptyList()
        }
        "GetMute" -> listOf("CurrentMute" to if (muted()) "1" else "0")
        "SetMute" -> {
            val mute = call.args["DesiredMute"].let { it == "1" || it.equals("true", ignoreCase = true) }
            val direction = if (mute) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE
            audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, 0)
            notifyRendering()
            emptyList()
        }
        else -> throw UpnpError(401, "Invalid Action")
    }

    private fun connection(call: SoapCall): List<Pair<String, String>> = when (call.action) {
        "GetProtocolInfo" -> listOf("Source" to "", "Sink" to SINK_PROTOCOL_INFO)
        "GetCurrentConnectionIDs" -> listOf("ConnectionIDs" to "0")
        "GetCurrentConnectionInfo" -> listOf(
            "RcsID" to "0",
            "AVTransportID" to "0",
            "ProtocolInfo" to "",
            "PeerConnectionManager" to "",
            "PeerConnectionID" to "-1",
            "Direction" to "Input",
            "Status" to "OK",
        )
        else -> throw UpnpError(401, "Invalid Action")
    }

    private fun play() {
        val p = playerOrCreate()
        if (p.playbackState == Player.STATE_IDLE || p.playbackState == Player.STATE_ENDED) {
            p.seekTo(0)
            p.prepare()
        }
        started = true
        p.play()
        changed()
    }

    private fun playerOrCreate(): ExoPlayer = player ?: ExoPlayer.Builder(context)
        .setAudioAttributes(
            AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
            // Pauses for an alarm or another app's sound, and resumes after.
            true,
        )
        .build()
        .also { created ->
            created.addListener(object : Player.Listener {
                override fun onEvents(player: Player, events: Player.Events) {
                    if (player.playbackState == Player.STATE_ENDED) started = false
                    changed()
                }

                override fun onPlayerError(error: PlaybackException) {
                    Log.w(TAG, "Couldn't play ${uri.orEmpty()}", error)
                    started = false
                }
            })
            player = created
        }

    private fun transportState(): String {
        val p = player
        return when {
            uri == null -> "NO_MEDIA_PRESENT"
            p == null -> "STOPPED"
            p.isPlaying -> "PLAYING"
            p.playbackState == Player.STATE_BUFFERING && p.playWhenReady -> "TRANSITIONING"
            started && p.playbackState == Player.STATE_READY -> "PAUSED_PLAYBACK"
            else -> "STOPPED"
        }
    }

    private fun transportActions(): String {
        val seek = if (player?.isCurrentMediaItemSeekable == true) ",Seek" else ""
        return when (transportState()) {
            "PLAYING" -> "Pause,Stop$seek"
            "PAUSED_PLAYBACK" -> "Play,Stop$seek"
            "TRANSITIONING" -> "Stop"
            "STOPPED" -> "Play$seek"
            else -> ""
        }
    }

    private fun duration(): String {
        val length = player?.duration ?: C.TIME_UNSET
        return if (length == C.TIME_UNSET) "0:00:00" else formatUpnpTime(length)
    }

    private fun volume(): Int {
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        return (audio.getStreamVolume(AudioManager.STREAM_MUSIC) * 100.0 / max).roundToInt()
    }

    private fun muted() = audio.isStreamMute(AudioManager.STREAM_MUSIC)

    /** The transport's evented state, as it goes in AVTransport's LastChange. */
    private fun transportValues(): List<Pair<String, String>> = listOf(
        "TransportState" to transportState(),
        "TransportStatus" to "OK",
        "TransportPlaySpeed" to "1",
        "CurrentPlayMode" to "NORMAL",
        "CurrentTransportActions" to transportActions(),
        "NumberOfTracks" to if (uri != null) "1" else "0",
        "CurrentTrack" to if (uri != null) "1" else "0",
        "AVTransportURI" to uri.orEmpty(),
        "AVTransportURIMetaData" to metadata,
        "CurrentTrackURI" to uri.orEmpty(),
        "CurrentTrackMetaData" to metadata,
        "CurrentTrackDuration" to duration(),
        "CurrentMediaDuration" to duration(),
    )

    private fun renderingValues() = listOf("Volume" to volume().toString(), "Mute" to if (muted()) "1" else "0")

    /** Something about the stream changed: tell subscribers (if it shows) and the now-playing bar. */
    private fun changed() {
        val playing = player?.isPlaying == true
        _nowPlaying.value = if (uri != null && (started || playing)) NowPlaying(track, playing) else null
        val values = transportValues()
        if (values == lastAvt) return
        lastAvt = values
        notify(AV_TRANSPORT_SERVICE)
    }

    private var lastRendering: List<Pair<String, String>>? = null

    /** Volume or mute changed (perhaps): tell subscribers, if it did. */
    private fun notifyRendering() {
        val values = renderingValues()
        if (values == lastRendering) return
        lastRendering = values
        notify(RENDERING_CONTROL_SERVICE)
    }

    /** The event body for [service]'s subscribers: everything evented, as it is now. */
    private fun eventBody(service: UpnpService): String = when (service) {
        AV_TRANSPORT_SERVICE -> propertySet(listOf("LastChange" to lastChange("AVT", transportValues())))
        RENDERING_CONTROL_SERVICE -> propertySet(listOf("LastChange" to lastChange("RCS", renderingValues())))
        else -> propertySet(
            listOf("SourceProtocolInfo" to "", "SinkProtocolInfo" to SINK_PROTOCOL_INFO, "CurrentConnectionIDs" to "0"),
        )
    }

    private fun notify(service: UpnpService) {
        val now = System.currentTimeMillis()
        subscriptions.values.removeIf { it.expires < now }
        val targets = subscriptions.values.filter { it.service == service }
        if (targets.isEmpty()) return
        val body = eventBody(service)
        targets.forEach { send(it, body) }
    }

    private suspend fun subscribe(service: UpnpService, request: HttpRequest): HttpResponse {
        val seconds = request.headers["timeout"]?.removePrefix("Second-")?.toIntOrNull()
            ?.coerceIn(60, MAX_SUBSCRIPTION) ?: MAX_SUBSCRIPTION
        val expires = System.currentTimeMillis() + seconds * 1000L
        val sid = request.headers["sid"]
        val subscription = if (sid != null) {
            // A renewal.
            subscriptions[sid]?.also { it.expires = expires } ?: return HttpResponse(412, "Precondition Failed")
        } else {
            val callback = request.headers["callback"]?.let { Regex("<([^>]+)>").find(it)?.groupValues?.get(1) }
                ?: return HttpResponse(412, "Precondition Failed")
            Subscription("uuid:${UUID.randomUUID()}", service, callback, expires).also {
                subscriptions[it.sid] = it
                // The first event, with everything, goes once the subscriber has its SID.
                scope.launch {
                    delay(300)
                    send(it, withContext(Dispatchers.Main) { eventBody(service) })
                }
            }
        }
        return HttpResponse(
            200,
            "OK",
            headers = listOf("SID" to subscription.sid, "TIMEOUT" to "Second-$seconds"),
        )
    }

    private fun send(subscription: Subscription, body: String) {
        val seq = subscription.seq++
        scope.launch(Dispatchers.IO) {
            val request = Request.Builder()
                .url(subscription.callback)
                .method("NOTIFY", body.toRequestBody("text/xml; charset=\"utf-8\"".toMediaType()))
                .header("NT", "upnp:event")
                .header("NTS", "upnp:propchange")
                .header("SID", subscription.sid)
                .header("SEQ", seq.toString())
                .build()
            try {
                http.newCall(request).execute().close()
            } catch (e: Exception) {
                Log.w(TAG, "Event to ${subscription.callback} failed", e)
            }
        }
    }

    private fun fault(code: Int, description: String) =
        HttpResponse(500, "Internal Server Error", soapFault(code, description))

    private class Subscription(val sid: String, val service: UpnpService, val callback: String, var expires: Long) {
        var seq = 0L
    }

    private class UpnpError(val code: Int, message: String) : Exception(message)

    private companion object {
        const val TAG = "Speaker"
        const val PREFERRED_PORT = 49494
        const val MAX_SUBSCRIPTION = 1800

        /** The UPnP value for a counter this renderer doesn't keep. */
        const val NOT_COUNTED = "2147483647"
    }
}

/** A new speaker identity: made once and kept (KioskStore), so Home Assistant keeps seeing the same speaker. */
fun newSpeakerUdn(): String = UUID.randomUUID().toString()
