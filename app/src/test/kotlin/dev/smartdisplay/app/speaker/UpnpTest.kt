package dev.smartdisplay.app.speaker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpnpTest {
    @Test
    fun `a SOAP call gives its action and arguments`() {
        val body = """<?xml version="1.0"?>
            <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"
                s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
              <s:Body>
                <u:SetAVTransportURI xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
                  <InstanceID>0</InstanceID>
                  <CurrentURI>http://ha.local/media/song.mp3?authSig=a&amp;b</CurrentURI>
                  <CurrentURIMetaData>&lt;DIDL-Lite/&gt;</CurrentURIMetaData>
                </u:SetAVTransportURI>
              </s:Body>
            </s:Envelope>"""
        val call = parseSoapCall(body)!!
        assertEquals("SetAVTransportURI", call.action)
        assertEquals("http://ha.local/media/song.mp3?authSig=a&b", call.args["CurrentURI"])
        assertEquals("<DIDL-Lite/>", call.args["CurrentURIMetaData"])
        assertNull(parseSoapCall("not xml"))
    }

    @Test
    fun `answers and faults are SOAP envelopes, with values escaped`() {
        val answer = soapResponse(AV_TRANSPORT, "GetMediaInfo", listOf("CurrentURI" to "a&b"))
        assertTrue(answer.contains("<u:GetMediaInfoResponse xmlns:u=\"$AV_TRANSPORT\"><CurrentURI>a&amp;b</CurrentURI>"))
        assertTrue(soapFault(701, "Transition not available").contains("<errorCode>701</errorCode>"))
    }

    @Test
    fun `DIDL-Lite metadata gives title, artist, album and art`() {
        val didl = """<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/"
            xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/">
            <item id="0" parentID="-1" restricted="1">
              <dc:title>Holocene</dc:title>
              <upnp:artist>Bon Iver</upnp:artist>
              <upnp:album>Bon Iver</upnp:album>
              <upnp:albumArtURI>http://ha.local/art.jpg</upnp:albumArtURI>
              <upnp:class>object.item.audioItem.musicTrack</upnp:class>
            </item>
          </DIDL-Lite>"""
        assertEquals(TrackInfo("Holocene", "Bon Iver", "Bon Iver", "http://ha.local/art.jpg"), parseDidl(didl))
        // A creator stands in for a missing artist; nothing usable gives nothing.
        val creator = """<DIDL-Lite xmlns:dc="http://purl.org/dc/elements/1.1/"><item><dc:title>News</dc:title>
            <dc:creator>NPR</dc:creator></item></DIDL-Lite>"""
        assertEquals(TrackInfo("News", "NPR"), parseDidl(creator))
        assertEquals(TrackInfo(), parseDidl(""))
        assertEquals(TrackInfo(), parseDidl("NOT_IMPLEMENTED"))
        assertEquals(TrackInfo(), parseDidl("<broken"))
    }

    @Test
    fun `times read and write as H MM SS`() {
        assertEquals("0:03:07", formatUpnpTime(187_400))
        assertEquals("1:00:00", formatUpnpTime(3_600_000))
        assertEquals(187_000L, parseUpnpTime("0:03:07"))
        assertEquals(187_500L, parseUpnpTime("00:03:07.500"))
        assertEquals(65_000L, parseUpnpTime("1:05"))
        assertNull(parseUpnpTime("NOT_IMPLEMENTED"))
    }

    @Test
    fun `events carry LastChange escaped inside the property set`() {
        val change = lastChange("RCS", listOf("Volume" to "40", "Mute" to "0"))
        assertEquals(
            """<Event xmlns="urn:schemas-upnp-org:metadata-1-0/RCS/"><InstanceID val="0">""" +
                """<Volume channel="Master" val="40"/><Mute channel="Master" val="0"/></InstanceID></Event>""",
            change,
        )
        val body = propertySet(listOf("LastChange" to change))
        assertTrue(body.contains("<e:property><LastChange>&lt;Event xmlns="))
    }

    @Test
    fun `descriptions declare every argument's state variable`() {
        for (service in SERVICES) {
            val names = service.variables.map { it.name }.toSet()
            for (action in service.actions) {
                for ((_, variable) in action.inputs + action.outputs) {
                    assertTrue("${service.name}.${action.name}: $variable", variable in names)
                }
            }
            assertTrue(serviceDescription(service).contains("<actionList>"))
        }
        val description = deviceDescription("Kitchen & co", "1234", "0.1.17")
        assertTrue(description.contains("<friendlyName>Kitchen &amp; co</friendlyName>"))
        assertTrue(description.contains("<UDN>uuid:1234</UDN>"))
        assertTrue(description.contains("<controlURL>/AVTransport/control</controlURL>"))
    }

    @Test
    fun `SSDP headers are read case-insensitively`() {
        val search = "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\n" +
            "ST: urn:schemas-upnp-org:device:MediaRenderer:1\r\n\r\n"
        val headers = ssdpHeaders(search)
        assertEquals("\"ssdp:discover\"", headers["man"])
        assertEquals(MEDIA_RENDERER, headers["st"])
    }
}
