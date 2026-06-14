package com.janni.sonosmirror

import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException

object SonosDiscovery {

    private const val TAG = "SonosDiscovery"
    private const val SSDP_ADDR = "239.255.255.250"
    private const val SSDP_PORT = 1900
    private const val SSDP_QUERY = "M-SEARCH * HTTP/1.1\r\n" +
        "HOST: 239.255.255.250:1900\r\n" +
        "MAN: \"ssdp:discover\"\r\n" +
        "MX: 3\r\n" +
        "ST: urn:schemas-upnp-org:device:ZonePlayer:1\r\n\r\n"

    data class SonosDevice(val ip: String, val name: String) {
        override fun toString() = "$name ($ip)"
    }

    fun discover(timeoutMs: Int = 4000): List<SonosDevice> {
        val found = mutableMapOf<String, SonosDevice>()
        try {
            val socket = DatagramSocket()
            socket.soTimeout = 500
            socket.broadcast = true

            val addr = InetAddress.getByName(SSDP_ADDR)
            val msgBytes = SSDP_QUERY.toByteArray()
            socket.send(DatagramPacket(msgBytes, msgBytes.size, addr, SSDP_PORT))

            val deadline = System.currentTimeMillis() + timeoutMs
            val buf = ByteArray(2048)

            while (System.currentTimeMillis() < deadline) {
                try {
                    val pkt = DatagramPacket(buf, buf.size)
                    socket.receive(pkt)
                    val resp = String(pkt.data, 0, pkt.length)
                    val ip = pkt.address.hostAddress ?: continue

                    if (ip in found) continue
                    if ("Sonos" !in resp && "ZonePlayer" !in resp && "rincon" !in resp.lowercase()) continue

                    val name = fetchFriendlyName(ip) ?: "Sonos ($ip)"
                    found[ip] = SonosDevice(ip, name)
                    Log.d(TAG, "Gefunden: $name @ $ip")
                } catch (e: SocketTimeoutException) {
                    // weitermachen bis deadline
                }
            }
            socket.close()
        } catch (e: Exception) {
            Log.e(TAG, "SSDP Fehler: ${e.message}")
        }
        return found.values.toList()
    }

    private fun fetchFriendlyName(ip: String): String? {
        return try {
            val url = java.net.URL("http://$ip:1400/xml/device_description.xml")
            val conn = url.openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 1500
            conn.readTimeout = 1500
            val xml = conn.inputStream.bufferedReader().readText()
            conn.disconnect()
            Regex("<friendlyName>([^<]+)").find(xml)?.groupValues?.get(1)
        } catch (e: Exception) {
            null
        }
    }
}
