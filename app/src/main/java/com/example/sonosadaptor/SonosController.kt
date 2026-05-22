package com.example.sonosadaptor

import java.net.HttpURLConnection
import java.net.URL

object SonosController {

    // Runs on whichever thread calls it — always call from a background thread.
    fun playUri(device: AudioOutput.SonosSpeaker, uri: String) {
        soap(device, "SetAVTransportURI",
            "<InstanceID>0</InstanceID>" +
            "<CurrentURI>$uri</CurrentURI>" +
            "<CurrentURIMetaData></CurrentURIMetaData>")
        soap(device, "Play",
            "<InstanceID>0</InstanceID>" +
            "<Speed>1</Speed>")
    }

    fun stop(device: AudioOutput.SonosSpeaker) {
        soap(device, "Stop", "<InstanceID>0</InstanceID>")
    }

    private fun soap(device: AudioOutput.SonosSpeaker, action: String, body: String) {
        try {
            val url = URL("http://${device.host}:${device.port}${device.avTransportControlUrl}")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "text/xml; charset=utf-8")
            connection.setRequestProperty(
                "SOAPACTION",
                "\"urn:schemas-upnp-org:service:AVTransport:1#$action\""
            )
            connection.doOutput = true
            connection.connectTimeout = 5000
            connection.readTimeout = 5000

            // Compact single-line envelope — matches the format Sonos accepts.
            val envelope =
                "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
                "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" " +
                "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">" +
                "<s:Body>" +
                "<u:$action xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\">" +
                body +
                "</u:$action>" +
                "</s:Body>" +
                "</s:Envelope>"

            connection.outputStream.use { it.write(envelope.toByteArray()) }
            val code = connection.responseCode
            android.util.Log.i("SonosController", "SOAP $action → HTTP $code")
            if (code >= 400) {
                val err = connection.errorStream?.bufferedReader()?.readText() ?: ""
                android.util.Log.e("SonosController", "SOAP error body: $err")
            }
            connection.disconnect()
        } catch (e: Exception) {
            android.util.Log.e("SonosController", "SOAP $action failed: $e")
        }
    }
}
