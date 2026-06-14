# SonosMirror

Stream your Android TV box's system audio to a Sonos speaker — wirelessly, no HDMI-ARC required.

Built for the Formuler Z12 Ultra (Android 12), but works on any Android 10+ device with ADB access.

---

## What it does

Your Android TV box plays MOL TV, YouTube, or whatever. SonosMirror captures **all system audio** (including apps that block recording) and streams it in real-time to your Sonos speaker over Wi-Fi via HTTP WAV.

**Latency:** ~5.8ms per audio chunk on the software side. Total perceived delay is dominated by Sonos's internal playback buffer (~300–800ms), which is hardware-fixed and cannot be reduced further.

---

## Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                     Android TV Box                           │
│                                                              │
│  MOL TV / YouTube / any app                                  │
│         │                                                    │
│         ▼  REMOTE_SUBMIX (AudioSource 8)                     │
│  ┌─────────────────────────────────┐                         │
│  │  audiorelay (DEX / app_process) │  ◄── shell uid=2000     │
│  │  ShellContext via Reflection    │                         │
│  │  1024-byte chunks / 5.8ms each  │                         │
│  │  HTTP WAV server :9877          │                         │
│  └────────────────┬────────────────┘                         │
│                   │                                          │
│  ┌────────────────▼────────────────┐                         │
│  │  SonosMirror Android App        │                         │
│  │  Foreground Service + WakeLock  │                         │
│  │  SSDP Discovery for Sonos       │                         │
│  │  UPnP AVTransport SOAP control  │                         │
│  │  Watchdog (reconnect every 8s)  │                         │
│  └────────────────┬────────────────┘                         │
└───────────────────┼─────────────────────────────────────────┘
                    │ HTTP WAV stream (LAN)
                    ▼
          ┌─────────────────┐
          │   Sonos Play:3  │
          │  (or any Sonos) │
          └─────────────────┘
```

### Why `REMOTE_SUBMIX`?

Standard Android APIs (`AudioPlaybackCapture`, `CAPTURE_AUDIO_OUTPUT`) fail on the Formuler Z12 Ultra — either blocked by `FLAG_CAPTURE_PRIVATE` or restricted to managed roles on Android 12. `REMOTE_SUBMIX` (AudioSource 8) captures the entire audio mix before it hits the hardware DAC, bypassing those restrictions.

To use it, the `AudioRecord` must run with shell UID (2000). This is achieved by running the DEX via `app_process` as the ADB shell user and routing `getAttributionSource()` through a `ShellContext` wrapper that uses reflection to instantiate the hidden `AttributionSource(int, String, String)` constructor.

---

## Components

### `audiorelay` — DEX on the box

Java source in [`audiorelay/`](audiorelay/). Runs as a background `app_process` on the TV box.

- Captures `REMOTE_SUBMIX` with `PERFORMANCE_MODE_LOW_LATENCY`
- Serves the audio as a continuous HTTP WAV stream on port 9877
- 1024-byte chunks (≈5.8ms each), queue of 8 slots maximum
- `TCP_NODELAY` + `flush()` per chunk — no Nagle buffering
- Queue is cleared on every new client connect (no stale audio)

### `SonosMirror App` — Android APK

Installed on the TV box itself.

- **`AudioMirrorService`** — Foreground service that orchestrates everything
- **`SonosDiscovery`** — SSDP multicast (239.255.255.250:1900) to find Sonos on the LAN
- **`SonosUPnP`** — UPnP AVTransport SOAP: Stop → SetAVTransportURI → Play
- **`AudioHttpServer`** — Kotlin HTTP server (mirrors audiorelay's stream format)
- Watchdog thread checks Sonos transport state every 8s and reconnects if stopped

---

## Requirements

- Android TV box with ADB over TCP enabled (port 5555)
- ADB installed on your Mac/PC
- Sonos speaker on the same Wi-Fi network
- Android 10+ on the box (for `PERFORMANCE_MODE_LOW_LATENCY` API)

---

## Setup

### 1 — Build and deploy `audiorelay`

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
ANDROID_JAR="$HOME/.android-sdk/platforms/android-34/android.jar"
D8="$HOME/.android-sdk/build-tools/34.0.0/d8"

cd audiorelay
mkdir -p bin
$JAVA_HOME/bin/javac -source 8 -target 8 \
    -cp "$ANDROID_JAR" \
    -d bin \
    src/com/janni/audiorelay/Main.java

$D8 --output bin bin/com/janni/audiorelay/*.class

adb -s <BOX_IP>:5555 push bin/classes.dex /data/local/tmp/audiorelay.dex
```

### 2 — Grant audio permissions on the box

```bash
adb -s <BOX_IP>:5555 shell \
  "cmd appops set com.android.shell RECORD_AUDIO_OUTPUT allow"
```

This resets on reboot. The Mac watchdog script handles it automatically.

### 3 — Start audiorelay

```bash
adb -s <BOX_IP>:5555 shell \
  "nohup app_process \
    -Djava.class.path=/data/local/tmp/audiorelay.dex \
    /data/local/tmp \
    com.janni.audiorelay.Main \
    > /data/local/tmp/ar.log 2>&1 &"
```

Or use the helper script:

```bash
./start-audiorelay.sh
```

### 4 — Install and start the app

Build the APK in Android Studio (`./gradlew assembleDebug`), install on the box, and tap **Start**. The app discovers Sonos via SSDP and starts playback automatically.

### 5 — Optional: Mac watchdog

A shell script + LaunchAgent keeps audiorelay alive across box reboots. It checks every 20s via ADB, re-grants the AppOp, and restarts the DEX if needed.

---

## Verify the stream

```bash
# Watch audiorelay logs live
adb -s <BOX_IP>:5555 shell "tail -f /data/local/tmp/ar.log"

# Test the HTTP stream from your Mac
curl -o /dev/null -s -w 'Bytes received: %{size_download}\n' \
  --max-time 3 http://<BOX_IP>:9877/stream.wav
```

---

## Known limitations

**Sonos internal buffer:** Sonos buffers HTTP audio streams to absorb network jitter. This introduces ~300–800ms of inherent latency depending on the Sonos model. It is hardware-fixed and cannot be reduced — our software pipeline adds less than 75ms on top.

**AppOps reset on reboot:** The `RECORD_AUDIO_OUTPUT` AppOp is reset every time the box reboots. The watchdog script handles this automatically.

**ADB requirement:** Running as shell UID requires ADB access. There is no way around this on a non-rooted device with a restrictive OEM like Formuler.

**Single client:** The HTTP server streams to one active connection at a time. A new Sonos connect drops the previous one.

---

## Why not AirPlay / Bluetooth / HDMI-ARC?

| Option | Why it doesn't work |
|--------|---------------------|
| AirPlay | Sonos Play:3 has no AirPlay support |
| Bluetooth | Formuler Z12 BT audio output is broken on this firmware |
| HDMI-ARC | Requires a TV with proper CEC passthrough — unreliable |

HTTP WAV to Sonos UPnP is the only path that works end-to-end without root.

---

## License

MIT
