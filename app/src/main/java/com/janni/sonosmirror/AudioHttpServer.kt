package com.janni.sonosmirror

import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class AudioHttpServer(
    private val port: Int,
    private val audioQueue: LinkedBlockingQueue<ByteArray>
) {
    companion object {
        private const val TAG = "AudioHttpServer"
        private const val SAMPLE_RATE  = 44100
        private const val CHANNELS     = 2
        private const val BITS         = 16
        private val BYTE_RATE  = SAMPLE_RATE * CHANNELS * (BITS / 8)
        private val BLOCK_ALIGN = CHANNELS * (BITS / 8)
    }

    private val isRunning       = AtomicBoolean(false)
    private val activeStreamer  = AtomicReference<Thread?>(null)
    private var serverSocket: ServerSocket? = null

    // Beep-Daten werden fresh für jede echte GET-Verbindung abgespielt
    private val introChunks = mutableListOf<ByteArray>()

    fun setIntroChunks(chunks: List<ByteArray>) {
        introChunks.clear()
        introChunks.addAll(chunks)
    }

    fun start() {
        isRunning.set(true)
        serverSocket = ServerSocket(port)
        Thread {
            while (isRunning.get()) {
                try {
                    val sock = serverSocket?.accept() ?: break
                    Thread { handleClient(sock) }.apply { isDaemon = true; start() }
                } catch (e: Exception) {
                    if (isRunning.get()) Log.e(TAG, "Accept-Fehler: ${e.message}")
                }
            }
        }.apply { isDaemon = true; name = "AudioHTTPAccept"; start() }
    }

    fun stop() {
        isRunning.set(false)
        activeStreamer.get()?.interrupt()
        try { serverSocket?.close() } catch (_: Exception) {}
    }

    private fun handleClient(socket: Socket) {
        try {
            socket.soTimeout = 5000
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))

            val firstLine = try { reader.readLine() } catch (_: SocketTimeoutException) { null } ?: run {
                socket.close(); return
            }
            // Header bis zur Leerzeile abarbeiten
            try {
                var line = reader.readLine()
                while (!line.isNullOrEmpty()) { line = reader.readLine() }
            } catch (_: Exception) {}

            val isHead = firstLine.startsWith("HEAD")
            val isGet  = firstLine.startsWith("GET")

            if (!isHead && !isGet) { socket.close(); return }

            val headers = buildString {
                append("HTTP/1.0 200 OK\r\n")
                append("Content-Type: audio/wav\r\n")
                append("Content-Length: 2147483647\r\n")
                append("Cache-Control: no-cache, no-store\r\n")
                append("Connection: close\r\n")
                append("icy-name: SonosMirror\r\n")
                append("\r\n")
            }.toByteArray()

            val out = socket.getOutputStream()

            if (isHead) {
                out.write(headers); out.flush()
                socket.close(); return
            }

            // --- GET: echter Streaming-Request ---
            // Vorherigen Streamer unterbrechen (nur eine aktive Verbindung gleichzeitig)
            activeStreamer.getAndSet(Thread.currentThread())?.interrupt()

            Log.d(TAG, "Sonos verbunden – starte WAV-Stream")

            out.write(headers)
            out.write(buildWavHeader())

            // Intro-Beeps direkt ausgeben (nicht aus Queue)
            for (chunk in introChunks) {
                out.write(chunk)
            }
            out.flush()

            // Live-Audio aus Queue streamen
            socket.soTimeout = 0  // kein Timeout beim Streamen
            while (isRunning.get() && !Thread.currentThread().isInterrupted) {
                try {
                    val chunk = audioQueue.poll(500, TimeUnit.MILLISECONDS)
                    if (chunk != null) {
                        out.write(chunk)
                        out.flush()
                    }
                } catch (_: InterruptedException) {
                    break
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "Stream beendet: ${e.message}")
        } finally {
            activeStreamer.compareAndSet(Thread.currentThread(), null)
            try { socket.close() } catch (_: Exception) {}
        }
    }

    private fun buildWavHeader(): ByteArray {
        val buf = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        buf.put("RIFF".toByteArray(Charsets.US_ASCII))
        buf.putInt(0x7FFFFFFF)
        buf.put("WAVE".toByteArray(Charsets.US_ASCII))
        buf.put("fmt ".toByteArray(Charsets.US_ASCII))
        buf.putInt(16)
        buf.putShort(1)
        buf.putShort(CHANNELS.toShort())
        buf.putInt(SAMPLE_RATE)
        buf.putInt(BYTE_RATE)
        buf.putShort(BLOCK_ALIGN.toShort())
        buf.putShort(BITS.toShort())
        buf.put("data".toByteArray(Charsets.US_ASCII))
        buf.putInt(0x7FFFFFFF)
        return buf.array()
    }
}
