package com.janni.sonosmirror

import android.util.Log
import java.net.HttpURLConnection
import java.net.URL

object SonosUPnP {

    private const val TAG = "SonosUPnP"

    fun getTransportState(sonosIp: String): String {
        return try {
            val body = buildSoap("GetTransportInfo", "urn:schemas-upnp-org:service:AVTransport:1") {
                "<InstanceID>0</InstanceID>"
            }
            val resp = sendSoapRaw(sonosIp, "AVTransport", "GetTransportInfo", body)
            Regex("<CurrentTransportState>([^<]+)").find(resp)?.groupValues?.get(1) ?: "UNKNOWN"
        } catch (e: Exception) { "ERROR" }
    }

    fun playStream(sonosIp: String, streamUrl: String) {
        try {
            // Erst stoppen (überschreibt Spotify Connect, Airplay etc.)
            Log.d(TAG, "Stop")
            stop(sonosIp)
            Thread.sleep(400)

            Log.d(TAG, "SetAVTransportURI → $streamUrl")
            setAvTransportUri(sonosIp, streamUrl)
            Thread.sleep(300)

            Log.d(TAG, "Play")
            play(sonosIp)
        } catch (e: Exception) {
            Log.e(TAG, "Fehler: ${e.message}")
        }
    }

    fun stop(sonosIp: String) {
        try {
            sendSoap(sonosIp, "AVTransport", "Stop",
                buildSoap("Stop", "urn:schemas-upnp-org:service:AVTransport:1") {
                    "<InstanceID>0</InstanceID><Speed>1</Speed>"
                })
        } catch (_: Exception) {}
    }

    private fun setAvTransportUri(sonosIp: String, uri: String) {
        sendSoap(sonosIp, "AVTransport", "SetAVTransportURI",
            buildSoap("SetAVTransportURI", "urn:schemas-upnp-org:service:AVTransport:1") {
                """<InstanceID>0</InstanceID>
                   <CurrentURI>$uri</CurrentURI>
                   <CurrentURIMetaData></CurrentURIMetaData>"""
            })
    }

    private fun play(sonosIp: String) {
        sendSoap(sonosIp, "AVTransport", "Play",
            buildSoap("Play", "urn:schemas-upnp-org:service:AVTransport:1") {
                "<InstanceID>0</InstanceID><Speed>1</Speed>"
            })
    }

    private fun buildSoap(action: String, xmlns: String, body: () -> String) = """
        <?xml version="1.0" encoding="utf-8"?>
        <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"
                    s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
          <s:Body><u:$action xmlns:u="$xmlns">${body()}</u:$action></s:Body>
        </s:Envelope>""".trimIndent()

    private fun sendSoap(sonosIp: String, service: String, action: String, body: String) {
        sendSoapRaw(sonosIp, service, action, body)
    }

    private fun sendSoapRaw(sonosIp: String, service: String, action: String, body: String): String {
        val conn = (URL("http://$sonosIp:1400/MediaRenderer/$service/Control")
            .openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 4000
            readTimeout    = 4000
            setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
            setRequestProperty("SOAPAction",
                "\"urn:schemas-upnp-org:service:$service:1#$action\"")
            doOutput = true
        }
        return try {
            conn.outputStream.write(body.toByteArray(Charsets.UTF_8))
            val code = conn.responseCode
            Log.d(TAG, "$action → HTTP $code")
            conn.inputStream.bufferedReader().readText()
        } catch (e: Exception) {
            try { conn.errorStream?.bufferedReader()?.readText() ?: "" } catch (_: Exception) { "" }
        } finally {
            conn.disconnect()
        }
    }
}
