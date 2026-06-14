package com.janni.sonosmirror

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.janni.sonosmirror.databinding.ActivityMainBinding
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var isStreaming = false
    private var selectedSonos: SonosDiscovery.SonosDevice? = null

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants.values.all { it }) startStream()
        else Toast.makeText(this, "Berechtigung nötig", Toast.LENGTH_SHORT).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnToggle.setOnClickListener {
            if (isStreaming) stopStream() else checkPermissionsAndStart()
        }

        binding.btnScan.setOnClickListener { runDiscovery() }

        // Beim Start automatisch scannen
        runDiscovery()
    }

    private fun runDiscovery() {
        binding.tvSonosDevice.text = "Suche..."
        binding.tvRelayStatus.text = "prüfe..."
        binding.btnScan.isEnabled = false

        Thread {
            // Parallel: Sonos scannen + Relay prüfen
            var sonos: List<SonosDiscovery.SonosDevice> = emptyList()
            var relayOk = false

            val t1 = Thread { sonos = SonosDiscovery.discover(4000) }
            val t2 = Thread { relayOk = isRelayAlive("127.0.0.1", 9877) }
            t1.start(); t2.start()
            t1.join(); t2.join()

            runOnUiThread {
                binding.btnScan.isEnabled = true

                // Relay-Status
                if (relayOk) {
                    binding.dotRelay.setBackgroundResource(R.drawable.dot_active)
                    binding.tvRelayStatus.text = "Aktiv (:9877)"
                    binding.tvRelayStatus.setTextColor(0xFF4CAF50.toInt())
                } else {
                    binding.dotRelay.setBackgroundResource(R.drawable.dot_idle)
                    binding.tvRelayStatus.text = "Offline — starte start-audiorelay.sh"
                    binding.tvRelayStatus.setTextColor(0xFFFF5722.toInt())
                }

                // Sonos-Ergebnis
                when {
                    sonos.isEmpty() -> {
                        binding.tvSonosDevice.text = "Nicht gefunden"
                        selectedSonos = null
                    }
                    sonos.size == 1 -> {
                        selectedSonos = sonos.first()
                        binding.tvSonosDevice.text = selectedSonos.toString()
                        // Automatisch starten wenn relay ok und noch nicht am streamen
                        if (relayOk && !isStreaming) {
                            checkPermissionsAndStart()
                        }
                    }
                    else -> {
                        // Mehrere Sonos: ersten nehmen, Namen alle anzeigen
                        selectedSonos = sonos.first()
                        binding.tvSonosDevice.text = sonos.joinToString(" | ") { it.name }
                    }
                }
            }
        }.apply { isDaemon = true; start() }
    }

    private fun checkPermissionsAndStart() {
        val perms = buildList {
            if (!hasPermission(Manifest.permission.RECORD_AUDIO))
                add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                !hasPermission(Manifest.permission.POST_NOTIFICATIONS))
                add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (perms.isEmpty()) startStream() else permLauncher.launch(perms.toTypedArray())
    }

    private fun startStream() {
        val sonos = selectedSonos
        if (sonos == null) {
            Toast.makeText(this, "Kein Sonos gefunden — erst scannen", Toast.LENGTH_SHORT).show()
            return
        }

        val intent = Intent(this, AudioMirrorService::class.java).apply {
            action = AudioMirrorService.ACTION_START
            putExtra(AudioMirrorService.EXTRA_SONOS_IP, sonos.ip)
            putExtra(AudioMirrorService.EXTRA_RELAY_PORT, 9877)
        }
        startForegroundService(intent)
        setStreamingState(true, sonos)
    }

    private fun stopStream() {
        startService(Intent(this, AudioMirrorService::class.java).apply {
            action = AudioMirrorService.ACTION_STOP
        })
        setStreamingState(false)
    }

    private fun setStreamingState(active: Boolean, sonos: SonosDiscovery.SonosDevice? = null) {
        isStreaming = active
        if (active && sonos != null) {
            binding.btnToggle.text = "Stream stoppen"
            binding.btnToggle.backgroundTintList =
                ContextCompat.getColorStateList(this, android.R.color.holo_red_dark)
            binding.tvStatus.text = "Streamt zu ${sonos.name}"
            binding.statusDot.setBackgroundResource(R.drawable.dot_active)
            binding.tvInfo.text = "http://<box-ip>:9877/stream.wav"
        } else {
            binding.btnToggle.text = "Stream starten"
            binding.btnToggle.backgroundTintList =
                ContextCompat.getColorStateList(this, android.R.color.holo_green_dark)
            binding.tvStatus.text = "Bereit"
            binding.statusDot.setBackgroundResource(R.drawable.dot_idle)
            binding.tvInfo.text = ""
        }
    }

    private fun isRelayAlive(host: String, port: Int): Boolean {
        return try {
            Socket().use { s -> s.connect(InetSocketAddress(host, port), 1500); true }
        } catch (e: IOException) { false }
    }

    private fun hasPermission(p: String) =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED
}
