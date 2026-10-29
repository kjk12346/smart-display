package dev.smartdisplay.app.speaker

import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.xml.sax.InputSource

/*
 * The UPnP / DLNA side of the speaker, as plain text in and out: the device and service descriptions Home
 * Assistant's DLNA integration reads, SOAP actions and their answers, and the "LastChange" events it subscribes to.
 * Only what a renderer that plays one stream at a time needs (UPnP AV MediaRenderer:1).
 */

const val AV_TRANSPORT = "urn:schemas-upnp-org:service:AVTransport:1"
const val RENDERING_CONTROL = "urn:schemas-upnp-org:service:RenderingControl:1"
const val CONNECTION_MANAGER = "urn:schemas-upnp-org:service:ConnectionManager:1"
const val MEDIA_RENDERER = "urn:schemas-upnp-org:device:MediaRenderer:1"

/** A UPnP service: its type, where it lives on our server, and what it declares. */
data class UpnpService(
    val type: String,
    val id: String,
    /** The path its description, control and events hang off: `/<name>.xml`, `/<name>/control`, `/<name>/event`. */
    val name: String,
    val variables: List<StateVariable>,
    val actions: List<UpnpAction>,
) {
    val scpdPath get() = "/$name.xml"
    val controlPath get() = "/$name/control"
    val eventPath get() = "/$name/event"
}

data class StateVariable(
    val name: String,
    val type: String = "string",
    val evented: Boolean = false,
    val allowed: List<String> = emptyList(),
    val range: IntRange? = null,
)

/** An action's arguments: name to the state variable it relates to. */
data class UpnpAction(
    val name: String,
    val inputs: List<Pair<String, String>> = emptyList(),
    val outputs: List<Pair<String, String>> = emptyList(),
)

private val INSTANCE = "InstanceID" to "A_ARG_TYPE_InstanceID"
private val CHANNEL = "Channel" to "A_ARG_TYPE_Channel"

val AV_TRANSPORT_SERVICE = UpnpService(
    type = AV_TRANSPORT,
    id = "urn:upnp-org:serviceId:AVTransport",
    name = "AVTransport",
    variables = listOf(
        StateVariable(
            "TransportState",
            allowed = listOf("STOPPED", "PLAYING", "PAUSED_PLAYBACK", "TRANSITIONING", "NO_MEDIA_PRESENT"),
        ),
        StateVariable("TransportStatus", allowed = listOf("OK", "ERROR_OCCURRED")),
        StateVariable("TransportPlaySpeed", allowed = listOf("1")),
        StateVariable("PlaybackStorageMedium", allowed = listOf("NONE", "NETWORK")),
        StateVariable("RecordStorageMedium", allowed = listOf("NOT_IMPLEMENTED")),
        StateVariable("PossiblePlaybackStorageMedia"),
        StateVariable("PossibleRecordStorageMedia"),
        StateVariable("RecordMediumWriteStatus", allowed = listOf("NOT_IMPLEMENTED")),
        StateVariable("CurrentRecordQualityMode", allowed = listOf("NOT_IMPLEMENTED")),
        StateVariable("PossibleRecordQualityModes"),
        StateVariable("CurrentPlayMode", allowed = listOf("NORMAL")),
        StateVariable("NumberOfTracks", type = "ui4", range = 0..1),
        StateVariable("CurrentTrack", type = "ui4", range = 0..1),
        StateVariable("CurrentTrackDuration"),
        StateVariable("CurrentMediaDuration"),
        StateVariable("CurrentTrackMetaData"),
        StateVariable("CurrentTrackURI"),
        StateVariable("AVTransportURI"),
        StateVariable("AVTransportURIMetaData"),
        StateVariable("NextAVTransportURI"),
        StateVariable("NextAVTransportURIMetaData"),
        StateVariable("RelativeTimePosition"),
        StateVariable("AbsoluteTimePosition"),
        StateVariable("RelativeCounterPosition", type = "i4"),
        StateVariable("AbsoluteCounterPosition", type = "i4"),
        StateVariable("CurrentTransportActions"),
        StateVariable("LastChange", evented = true),
        StateVariable("A_ARG_TYPE_SeekMode", allowed = listOf("REL_TIME", "ABS_TIME", "TRACK_NR")),
        StateVariable("A_ARG_TYPE_SeekTarget"),
        StateVariable("A_ARG_TYPE_InstanceID", type = "ui4"),
    ),
    actions = listOf(
        UpnpAction(
            "SetAVTransportURI",
            inputs = listOf(
                INSTANCE,
                "CurrentURI" to "AVTransportURI",
                "CurrentURIMetaData" to "AVTransportURIMetaData",
            ),
        ),
        UpnpAction(
            "GetMediaInfo",
            inputs = listOf(INSTANCE),
            outputs = listOf(
                "NrTracks" to "NumberOfTracks",
                "MediaDuration" to "CurrentMediaDuration",
                "CurrentURI" to "AVTransportURI",
                "CurrentURIMetaData" to "AVTransportURIMetaData",
                "NextURI" to "NextAVTransportURI",
                "NextURIMetaData" to "NextAVTransportURIMetaData",
                "PlayMedium" to "PlaybackStorageMedium",
                "RecordMedium" to "RecordStorageMedium",
                "WriteStatus" to "RecordMediumWriteStatus",
            ),
        ),
        UpnpAction(
            "GetTransportInfo",
            inputs = listOf(INSTANCE),
            outputs = listOf(
                "CurrentTransportState" to "TransportState",
                "CurrentTransportStatus" to "TransportStatus",
                "CurrentSpeed" to "TransportPlaySpeed",
            ),
        ),
        UpnpAction(
            "GetPositionInfo",
            inputs = listOf(INSTANCE),
            outputs = listOf(
                "Track" to "CurrentTrack",
                "TrackDuration" to "CurrentTrackDuration",
                "TrackMetaData" to "CurrentTrackMetaData",
                "TrackURI" to "CurrentTrackURI",
                "RelTime" to "RelativeTimePosition",
                "AbsTime" to "AbsoluteTimePosition",
                "RelCount" to "RelativeCounterPosition",
                "AbsCount" to "AbsoluteCounterPosition",
            ),
        ),
        UpnpAction(
            "GetDeviceCapabilities",
            inputs = listOf(INSTANCE),
            outputs = listOf(
                "PlayMedia" to "PossiblePlaybackStorageMedia",
                "RecMedia" to "PossibleRecordStorageMedia",
                "RecQualityModes" to "PossibleRecordQualityModes",
            ),
        ),
        UpnpAction(
            "GetTransportSettings",
            inputs = listOf(INSTANCE),
            outputs = listOf("PlayMode" to "CurrentPlayMode", "RecQualityMode" to "CurrentRecordQualityMode"),
        ),
        UpnpAction(
            "GetCurrentTransportActions",
            inputs = listOf(INSTANCE),
            outputs = listOf("Actions" to "CurrentTransportActions"),
        ),
        UpnpAction("Stop", inputs = listOf(INSTANCE)),
        UpnpAction("Play", inputs = listOf(INSTANCE, "Speed" to "TransportPlaySpeed")),
        UpnpAction("Pause", inputs = listOf(INSTANCE)),
        UpnpAction(
            "Seek",
            inputs = listOf(INSTANCE, "Unit" to "A_ARG_TYPE_SeekMode", "Target" to "A_ARG_TYPE_SeekTarget"),
        ),
    ),
)

val RENDERING_CONTROL_SERVICE = UpnpService(
    type = RENDERING_CONTROL,
    id = "urn:upnp-org:serviceId:RenderingControl",
    name = "RenderingControl",
    variables = listOf(
        StateVariable("LastChange", evented = true),
        StateVariable("Mute", type = "boolean"),
        StateVariable("Volume", type = "ui2", range = 0..100),
        StateVariable("A_ARG_TYPE_Channel", allowed = listOf("Master")),
        StateVariable("A_ARG_TYPE_InstanceID", type = "ui4"),
    ),
    actions = listOf(
        UpnpAction("GetMute", inputs = listOf(INSTANCE, CHANNEL), outputs = listOf("CurrentMute" to "Mute")),
        UpnpAction("SetMute", inputs = listOf(INSTANCE, CHANNEL, "DesiredMute" to "Mute")),
        UpnpAction("GetVolume", inputs = listOf(INSTANCE, CHANNEL), outputs = listOf("CurrentVolume" to "Volume")),
        UpnpAction("SetVolume", inputs = listOf(INSTANCE, CHANNEL, "DesiredVolume" to "Volume")),
    ),
)

val CONNECTION_MANAGER_SERVICE = UpnpService(
    type = CONNECTION_MANAGER,
    id = "urn:upnp-org:serviceId:ConnectionManager",
    name = "ConnectionManager",
    variables = listOf(
        StateVariable("SourceProtocolInfo", evented = true),
        StateVariable("SinkProtocolInfo", evented = true),
        StateVariable("CurrentConnectionIDs", evented = true),
        StateVariable(
            "A_ARG_TYPE_ConnectionStatus",
            allowed = listOf("OK", "ContentFormatMismatch", "InsufficientBandwidth", "UnreliableChannel", "Unknown"),
        ),
        StateVariable("A_ARG_TYPE_ConnectionManager"),
        StateVariable("A_ARG_TYPE_Direction", allowed = listOf("Input", "Output")),
        StateVariable("A_ARG_TYPE_ProtocolInfo"),
        StateVariable("A_ARG_TYPE_ConnectionID", type = "i4"),
        StateVariable("A_ARG_TYPE_AVTransportID", type = "i4"),
        StateVariable("A_ARG_TYPE_RcsID", type = "i4"),
    ),
    actions = listOf(
        UpnpAction(
            "GetProtocolInfo",
            outputs = listOf("Source" to "SourceProtocolInfo", "Sink" to "SinkProtocolInfo"),
        ),
        UpnpAction("GetCurrentConnectionIDs", outputs = listOf("ConnectionIDs" to "CurrentConnectionIDs")),
        UpnpAction(
            "GetCurrentConnectionInfo",
            inputs = listOf("ConnectionID" to "A_ARG_TYPE_ConnectionID"),
            outputs = listOf(
                "RcsID" to "A_ARG_TYPE_RcsID",
                "AVTransportID" to "A_ARG_TYPE_AVTransportID",
                "ProtocolInfo" to "A_ARG_TYPE_ProtocolInfo",
                "PeerConnectionManager" to "A_ARG_TYPE_ConnectionManager",
                "PeerConnectionID" to "A_ARG_TYPE_ConnectionID",
                "Direction" to "A_ARG_TYPE_Direction",
                "Status" to "A_ARG_TYPE_ConnectionStatus",
            ),
        ),
    ),
)

val SERVICES = listOf(AV_TRANSPORT_SERVICE, RENDERING_CONTROL_SERVICE, CONNECTION_MANAGER_SERVICE)

/** What this speaker plays: audio over HTTP, the usual formats, then any audio Android's player may manage. */
val SINK_PROTOCOL_INFO = listOf(
    "audio/mpeg", "audio/mp3", "audio/mp4", "audio/x-m4a", "audio/aac", "audio/flac", "audio/x-flac", "audio/ogg",
    "audio/opus", "audio/wav", "audio/x-wav", "audio/webm", "audio/L16", "audio/*",
).joinToString(",") { "http-get:*:$it:*" }

/** The device description at `/description.xml`. */
fun deviceDescription(name: String, udn: String, version: String): String = buildString {
    append("""<?xml version="1.0" encoding="utf-8"?>""")
    append("""<root xmlns="urn:schemas-upnp-org:device-1-0" xmlns:dlna="urn:schemas-dlna-org:device-1-0">""")
    append("<specVersion><major>1</major><minor>0</minor></specVersion>")
    append("<device>")
    append("<deviceType>$MEDIA_RENDERER</deviceType>")
    append("<friendlyName>${xmlEscape(name)}</friendlyName>")
    append("<manufacturer>Smart display</manufacturer>")
    append("<modelName>Smart display</modelName>")
    append("<modelNumber>${xmlEscape(version)}</modelNumber>")
    append("<UDN>uuid:$udn</UDN>")
    append("<dlna:X_DLNADOC>DMR-1.50</dlna:X_DLNADOC>")
    append("<serviceList>")
    for (service in SERVICES) {
        append("<service>")
        append("<serviceType>${service.type}</serviceType>")
        append("<serviceId>${service.id}</serviceId>")
        append("<SCPDURL>${service.scpdPath}</SCPDURL>")
        append("<controlURL>${service.controlPath}</controlURL>")
        append("<eventSubURL>${service.eventPath}</eventSubURL>")
        append("</service>")
    }
    append("</serviceList>")
    append("</device>")
    append("</root>")
}

/** A service's description (SCPD): its actions and state variables. */
fun serviceDescription(service: UpnpService): String = buildString {
    append("""<?xml version="1.0" encoding="utf-8"?>""")
    append("""<scpd xmlns="urn:schemas-upnp-org:service-1-0">""")
    append("<specVersion><major>1</major><minor>0</minor></specVersion>")
    append("<actionList>")
    for (action in service.actions) {
        append("<action><name>${action.name}</name><argumentList>")
        for ((name, variable) in action.inputs) argument(name, "in", variable)
        for ((name, variable) in action.outputs) argument(name, "out", variable)
        append("</argumentList></action>")
    }
    append("</actionList>")
    append("<serviceStateTable>")
    for (variable in service.variables) {
        append("""<stateVariable sendEvents="${if (variable.evented) "yes" else "no"}">""")
        append("<name>${variable.name}</name><dataType>${variable.type}</dataType>")
        if (variable.allowed.isNotEmpty()) {
            append("<allowedValueList>")
            variable.allowed.forEach { append("<allowedValue>$it</allowedValue>") }
            append("</allowedValueList>")
        }
        variable.range?.let {
            append("<allowedValueRange><minimum>${it.first}</minimum><maximum>${it.last}</maximum><step>1</step>")
            append("</allowedValueRange>")
        }
        append("</stateVariable>")
    }
    append("</serviceStateTable>")
    append("</scpd>")
}

private fun StringBuilder.argument(name: String, direction: String, variable: String) {
    append("<argument><name>$name</name><direction>$direction</direction>")
    append("<relatedStateVariable>$variable</relatedStateVariable></argument>")
}

/** A SOAP call: the action's name and its arguments. */
data class SoapCall(val action: String, val args: Map<String, String>)

/** Reads a SOAP request's body; null if it isn't one. */
fun parseSoapCall(body: String): SoapCall? = try {
    val document = parseXml(body)
    val bodyElement = document.documentElement.children().firstOrNull { it.localName == "Body" }
    val action = bodyElement?.children()?.firstOrNull()
    action?.let { SoapCall(it.localName, it.children().associate { arg -> arg.localName to arg.textContent }) }
} catch (e: Exception) {
    null
}

/** The answer to [action] of [serviceType], with its out arguments in order. */
fun soapResponse(serviceType: String, action: String, outputs: List<Pair<String, String>>): String = buildString {
    append("""<?xml version="1.0" encoding="utf-8"?>""")
    append("""<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" """)
    append("""s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/"><s:Body>""")
    append("""<u:${action}Response xmlns:u="$serviceType">""")
    for ((name, value) in outputs) append("<$name>${xmlEscape(value)}</$name>")
    append("</u:${action}Response></s:Body></s:Envelope>")
}

/** A UPnP error, sent with HTTP 500. */
fun soapFault(code: Int, description: String): String = buildString {
    append("""<?xml version="1.0" encoding="utf-8"?>""")
    append("""<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" """)
    append("""s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/"><s:Body><s:Fault>""")
    append("<faultcode>s:Client</faultcode><faultstring>UPnPError</faultstring><detail>")
    append("""<UPnPError xmlns="urn:schemas-upnp-org:control-1-0">""")
    append("<errorCode>$code</errorCode><errorDescription>${xmlEscape(description)}</errorDescription>")
    append("</UPnPError></detail></s:Fault></s:Body></s:Envelope>")
}

/**
 * A "LastChange" value for [service] ("AVT" or "RCS"): each changed variable as `<Name val="…"/>` (volume and mute
 * with their channel). It goes in an event, escaped again there.
 */
fun lastChange(service: String, values: List<Pair<String, String>>): String = buildString {
    append("""<Event xmlns="urn:schemas-upnp-org:metadata-1-0/$service/"><InstanceID val="0">""")
    for ((name, value) in values) {
        val channel = if (name == "Volume" || name == "Mute") """ channel="Master"""" else ""
        append("""<$name$channel val="${xmlEscape(value)}"/>""")
    }
    append("</InstanceID></Event>")
}

/** An event's body: each evented variable and its value. */
fun propertySet(values: List<Pair<String, String>>): String = buildString {
    append("""<?xml version="1.0" encoding="utf-8"?>""")
    append("""<e:propertyset xmlns:e="urn:schemas-upnp-org:event-1-0">""")
    for ((name, value) in values) append("<e:property><$name>${xmlEscape(value)}</$name></e:property>")
    append("</e:propertyset>")
}

/** What a stream is, from the DIDL-Lite metadata Home Assistant sends with it; all optional. */
data class TrackInfo(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val art: String? = null,
)

/** Reads DIDL-Lite metadata; empty for none or for metadata that isn't XML. */
fun parseDidl(metadata: String?): TrackInfo {
    if (metadata.isNullOrBlank() || metadata == "NOT_IMPLEMENTED") return TrackInfo()
    return try {
        val item = parseXml(metadata).documentElement.children().firstOrNull() ?: return TrackInfo()
        fun first(vararg names: String) = names.firstNotNullOfOrNull { name ->
            item.children().firstOrNull { it.localName == name }?.textContent?.trim()?.takeIf { it.isNotEmpty() }
        }
        TrackInfo(
            title = first("title"),
            artist = first("artist", "creator"),
            album = first("album"),
            art = first("albumArtURI"),
        )
    } catch (e: Exception) {
        TrackInfo()
    }
}

/** A UPnP time, "H:MM:SS". */
fun formatUpnpTime(millis: Long): String {
    val seconds = (millis.coerceAtLeast(0) / 1000)
    return "%d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60)
}

/** Reads "H:MM:SS", "H:MM:SS.mmm" or "MM:SS" as milliseconds; null if it isn't a time. */
fun parseUpnpTime(text: String): Long? {
    val parts = text.trim().split(":")
    if (parts.size !in 2..3) return null
    val numbers = parts.map { it.toDoubleOrNull() ?: return null }
    val seconds = numbers.fold(0.0) { total, part -> total * 60 + part }
    return (seconds * 1000).toLong()
}

fun xmlEscape(text: String): String = buildString(text.length) {
    for (c in text) {
        when (c) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            '\'' -> append("&apos;")
            else -> append(c)
        }
    }
}

private fun parseXml(text: String) = DocumentBuilderFactory.newInstance().apply {
    isNamespaceAware = true
    // Metadata comes from the network: no DOCTYPEs (so no outside entities). Android's parser doesn't know the
    // feature, and doesn't fetch outside entities anyway.
    runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
}.newDocumentBuilder().parse(InputSource(StringReader(text)))

private fun Element.children(): List<Element> =
    (0 until childNodes.length).mapNotNull { childNodes.item(it) as? Element }
