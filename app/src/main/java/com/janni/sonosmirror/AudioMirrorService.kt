package com.janni.sonosmirror

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.core.app.NotificationCompat
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Foreground Service der den Sonos auf den audiorelay-Stream (Port 9877) zeigt.
 * audiorelay läuft als app_process/Shell-Prozess und erfasst REMOTE_SUBMIX.
 */
class AudioMirrorService : Service() {

    companion object {
        const val ACTION_START      = "com.janni.sonosmirror.START"
        const val ACTION_STOP       = "com.janni.sonosmirror.STOP"
        const val EXTRA_SONOS_IP    = "sonos_ip"
        const val EXTRA_STREAM_PORT = "stream_port"
        const val EXTRA_RELAY_PORT  = "relay_port"
        const val CHANNEL_ID = "sonos_mirror_channel"
        const val NOTIF_ID   = 42
        private const val TAG = "AudioMirrorService"
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private var tts: TextToSpeech? = null
    private val isRunning = AtomicBoolean(false)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> handleStart(intent)
            ACTION_STOP  -> handleStop()
        }
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) = super.onTaskRemoved(rootIntent)

    override fun onDestroy() {
        cleanup()
        super.onDestroy()
    }

    private fun handleStart(intent: Intent) {
        val sonosIp   = intent.getStringExtra(EXTRA_SONOS_IP)    ?: "192.168.178.76"
        val relayPort = intent.getIntExtra(EXTRA_RELAY_PORT, 9877)

        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification(sonosIp))

        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SonosMirror:Stream")
        wakeLock?.acquire(8 * 60 * 60 * 1000L)

        isRunning.set(true)

        Thread {
            connectSonos(sonosIp, relayPort)
            // Watchdog: reconnect falls Sonos den Stream verliert
            startWatchdog(sonosIp, relayPort)
        }.apply { isDaemon = true; name = "SonosThread"; start() }
    }

    private fun connectSonos(sonosIp: String, relayPort: Int) {
        // Box-eigene IP ermitteln
        val boxIp = getBoxIpAddress()
        val streamUrl = "http://$boxIp:$relayPort/stream.wav"
        Log.d(TAG, "Stream-URL: $streamUrl")

        // Prüfen ob audiorelay erreichbar
        if (!isRelayAlive(boxIp, relayPort)) {
            Log.w(TAG, "audiorelay auf :$relayPort nicht erreichbar!")
            updateNotification(sonosIp, "audiorelay nicht aktiv")
            return
        }

        Thread.sleep(300)
        SonosUPnP.playStream(sonosIp, streamUrl)
        Log.d(TAG, "Sonos spielt $streamUrl")
        updateNotification(sonosIp, "Aktiv ▶")
        Thread.sleep(1000)
        sayConnected()
    }

    private fun startWatchdog(sonosIp: String, relayPort: Int) {
        val boxIp = getBoxIpAddress()
        val streamUrl = "http://$boxIp:$relayPort/stream.wav"
        var failCount = 0
        while (isRunning.get()) {
            Thread.sleep(8000)
            if (!isRunning.get()) break
            val state = SonosUPnP.getTransportState(sonosIp)
            Log.d(TAG, "Watchdog: Sonos=$state failCount=$failCount")
            if (state == "STOPPED" || state == "ERROR") {
                failCount++
                // Erst nach 2 hintereinander STOPPED reconnecten —
                // ein einzelnes STOPPED ist oft nur kurzes Re-Buffering von Sonos
                if (failCount >= 2) {
                    Log.w(TAG, "Sonos wirklich gestoppt ($failCount×), reconnecte...")
                    updateNotification(sonosIp, "Reconnect...")
                    SonosUPnP.playStream(sonosIp, streamUrl)
                    updateNotification(sonosIp, "Aktiv ▶")
                    failCount = 0
                } else {
                    Log.d(TAG, "Sonos STOPPED — warte auf nächsten Check bevor reconnect")
                }
            } else {
                failCount = 0
            }
        }
    }

    private fun isRelayAlive(host: String, port: Int): Boolean {
        return try {
            Socket().use { s ->
                s.connect(InetSocketAddress(host, port), 1500)
                true
            }
        } catch (e: IOException) {
            false
        }
    }

    private fun handleStop() {
        cleanup()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun cleanup() {
        isRunning.set(false)
        try { wakeLock?.release() } catch (_: Exception) {}
        wakeLock = null
        tts?.stop(); tts?.shutdown(); tts = null
    }

    private fun sayConnected() {
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.GERMAN
                tts?.speak("Verbunden", TextToSpeech.QUEUE_FLUSH, null, "connected")
            }
        }
    }

    private fun getBoxIpAddress(): String {
        try {
            val ifaces = java.net.NetworkInterface.getNetworkInterfaces()
            while (ifaces.hasMoreElements()) {
                val addrs = ifaces.nextElement().inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (!addr.isLoopbackAddress && addr is java.net.Inet4Address)
                        return addr.hostAddress ?: continue
                }
            }
        } catch (_: Exception) {}
        return "127.0.0.1"
    }

    private fun createNotificationChannel() {
        val ch = NotificationChannel(CHANNEL_ID,
            getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW).apply {
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    private fun buildNotification(sonosIp: String, status: String = "Verbinde..."): Notification {
        val stopPi = PendingIntent.getService(this, 0,
            Intent(this, AudioMirrorService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText("$status — Sonos $sonosIp")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_media_pause, "Stop", stopPi)
            .build()
    }

    private fun updateNotification(sonosIp: String, status: String) {
        val notif = buildNotification(sonosIp, status)
        getSystemService(NotificationManager::class.java)
            .notify(NOTIF_ID, notif)
    }
}
