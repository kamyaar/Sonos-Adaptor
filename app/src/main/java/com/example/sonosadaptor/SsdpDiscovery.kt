package com.example.sonosadaptor

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.URL

object SsdpDiscovery {

    private const val SSDP_ADDRESS = "239.255.255.250"
    private const val SSDP_PORT = 1900
    private const val LISTEN_TIMEOUT_MS = 3000
    private const val TAG = "SsdpDiscovery"

    private val SEARCH_TARGETS = listOf(
        "urn:schemas-upnp-org:device:ZonePlayer:1",
        "urn:schemas-upnp-org:device:MediaRenderer:1"
    )

    // Runs on whichever thread calls it — always call from a background thread.
    fun discover(context: Context): List<AudioOutput.SonosSpeaker> {
        val wifiManager = context.applicationContext
            .getSystemService(Context.WIFI_SERVICE) as WifiManager
        val lock = wifiManager.createMulticastLock("ssdp_discovery").apply {
            setReferenceCounted(true)
            acquire()
        }

        val locations = mutableSetOf<String>()

        try {
            val socket = DatagramSocket()
            socket.soTimeout = LISTEN_TIMEOUT_MS
            val group = InetAddress.getByName(SSDP_ADDRESS)

            for (st in SEARCH_TARGETS) {
                Log.d(TAG, "Sending M-SEARCH for $st")
                val message = "M-SEARCH * HTTP/1.1\r\n" +
                        "HOST: $SSDP_ADDRESS:$SSDP_PORT\r\n" +
                        "MAN: \"ssdp:discover\"\r\n" +
                        "MX: 2\r\n" +
                        "ST: $st\r\n\r\n"
                val data = message.toByteArray()
                val packet = DatagramPacket(data, data.size, group, SSDP_PORT)
                socket.send(packet)
                Thread.sleep(100)
                socket.send(packet)
            }

            val buf = ByteArray(4096)
            try {
                while (true) {
                    val response = DatagramPacket(buf, buf.size)
                    socket.receive(response)
                    val text = String(response.data, 0, response.length)
                    Log.d(TAG, "Response from ${response.address}:\n$text")
                    text.lines()
                        .firstOrNull { it.startsWith("LOCATION:", ignoreCase = true) }
                        ?.substringAfter(":")
                        ?.trim()
                        ?.let { locations.add(it) }
                }
            } catch (_: SocketTimeoutException) {
                Log.d(TAG, "Timeout — collected ${locations.size} location(s)")
            }

            socket.close()
        } finally {
            lock.release()
        }

        val rawDevices = locations.mapNotNull { fetchDevice(it) }
        Log.d(TAG, "Raw devices: ${rawDevices.map { it.name }}")

        // For Sonos devices (port 1400), query zone group topology to get
        // real room names and merge grouped speakers into single entries.
        val sonosDevices = rawDevices.filter { it.port == 1400 }
        val otherDevices = rawDevices.filter { it.port != 1400 }

        val resolvedSonos = if (sonosDevices.isNotEmpty()) {
            // Fall back to raw devices if topology resolution fails OR returns nothing.
            resolveZoneGroups(sonosDevices).takeIf { !it.isNullOrEmpty() } ?: sonosDevices
        } else {
            emptyList()
        }

        return resolvedSonos + otherDevices
    }

    // ── Device description ────────────────────────────────────────────────────

    private fun fetchDevice(location: String): AudioOutput.SonosSpeaker? {
        return try {
            Log.d(TAG, "Fetching device description: $location")
            val url = URL(location)
            val host = url.host
            val port = if (url.port > 0) url.port else 1400

            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 3000
            connection.readTimeout = 3000
            val xml = connection.inputStream.bufferedReader().readText()
            connection.disconnect()

            parseDeviceXml(xml, host, port).also { Log.d(TAG, "Parsed: $it") }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch $location: $e")
            null
        }
    }

    private fun parseDeviceXml(xml: String, host: String, port: Int): AudioOutput.SonosSpeaker? {
        var friendlyName: String? = null
        var avTransportControlUrl: String? = null
        var inAvTransport = false

        val parser = Xml.newPullParser()
        parser.setInput(StringReader(xml))

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "friendlyName" -> if (friendlyName == null) friendlyName = parser.nextText()
                    "serviceType" -> inAvTransport = parser.nextText().contains("AVTransport")
                    "controlURL" -> if (inAvTransport && avTransportControlUrl == null) {
                        avTransportControlUrl = parser.nextText()
                    }
                }
            }
            event = parser.next()
        }

        val name = friendlyName ?: return null
        val controlUrl = avTransportControlUrl ?: return null
        return AudioOutput.SonosSpeaker(name, host, port, controlUrl)
    }

    // ── Zone group topology (Sonos-specific) ──────────────────────────────────

    /**
     * Queries ZoneGroupTopology from any of the discovered Sonos devices and
     * returns one [AudioOutput.SonosSpeaker] per active zone group, named after
     * all the rooms in that group (e.g. "Living Room" or "Kitchen + Bedroom").
     * The target host is always the group coordinator.
     */
    private fun resolveZoneGroups(
        devices: List<AudioOutput.SonosSpeaker>
    ): List<AudioOutput.SonosSpeaker>? {
        val deviceByHost = devices.associateBy { it.host }

        val stateXml = devices.firstNotNullOfOrNull { getZoneGroupState(it) }
            ?: return null

        Log.d(TAG, "ZoneGroupState XML:\n$stateXml")
        return parseZoneGroups(stateXml, deviceByHost)
    }

    private fun getZoneGroupState(device: AudioOutput.SonosSpeaker): String? {
        return try {
            val url = URL("http://${device.host}:${device.port}/ZoneGroupTopology/Control")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "text/xml; charset=utf-8")
            connection.setRequestProperty(
                "SOAPACTION",
                "\"urn:schemas-upnp-org:service:ZoneGroupTopology:1#GetZoneGroupState\""
            )
            connection.doOutput = true
            connection.connectTimeout = 3000
            connection.readTimeout = 3000

            val envelope = "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
                    "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" " +
                    "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">" +
                    "<s:Body><u:GetZoneGroupState " +
                    "xmlns:u=\"urn:schemas-upnp-org:service:ZoneGroupTopology:1\">" +
                    "</u:GetZoneGroupState></s:Body></s:Envelope>"

            connection.outputStream.use { it.write(envelope.toByteArray()) }
            val response = connection.inputStream.bufferedReader().readText()
            connection.disconnect()

            // The ZoneGroupState element contains entity-encoded XML; nextText() decodes it.
            val parser = Xml.newPullParser()
            parser.setInput(StringReader(response))
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG && parser.name == "ZoneGroupState") {
                    return parser.nextText()
                }
                event = parser.next()
            }
            null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get ZoneGroupState from ${device.host}: $e")
            null
        }
    }

    private fun parseZoneGroups(
        xml: String,
        deviceByHost: Map<String, AudioOutput.SonosSpeaker>
    ): List<AudioOutput.SonosSpeaker> {
        val groups = mutableListOf<AudioOutput.SonosSpeaker>()

        var coordinatorUuid: String? = null
        var coordinatorHost: String? = null
        var coordinatorPort = 1400
        var memberNames = mutableListOf<String>()

        val parser = Xml.newPullParser()
        parser.setInput(StringReader(xml))

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "ZoneGroup" -> {
                        coordinatorUuid = parser.getAttributeValue(null, "Coordinator")
                        coordinatorHost = null
                        memberNames = mutableListOf()
                    }
                    "ZoneGroupMember" -> {
                        val uuid = parser.getAttributeValue(null, "UUID") ?: ""
                        val zoneName = parser.getAttributeValue(null, "ZoneName") ?: ""
                        val location = parser.getAttributeValue(null, "Location") ?: ""
                        val invisible = parser.getAttributeValue(null, "Invisible") ?: "0"

                        // Invisible members are stereo-pair secondaries — skip their name
                        // but still use them to locate the coordinator if needed.
                        if (zoneName.isNotEmpty() && invisible != "1") memberNames.add(zoneName)

                        if (uuid == coordinatorUuid && location.isNotEmpty()) {
                            try {
                                val locUrl = URL(location)
                                coordinatorHost = locUrl.host
                                coordinatorPort = if (locUrl.port > 0) locUrl.port else 1400
                            } catch (_: Exception) {}
                        }
                    }
                }
                XmlPullParser.END_TAG -> if (parser.name == "ZoneGroup" && coordinatorHost != null) {
                    val host = coordinatorHost!!
                    val controlUrl = deviceByHost[host]?.avTransportControlUrl
                        ?: "/MediaRenderer/AVTransport/Control"
                    val displayName = memberNames.distinct().joinToString(" + ").ifEmpty { host }
                    groups.add(AudioOutput.SonosSpeaker(displayName, host, coordinatorPort, controlUrl))
                    Log.d(TAG, "Group: '$displayName' → coordinator $host")
                }
            }
            event = parser.next()
        }

        return groups
    }
}
