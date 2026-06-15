package com.janni.audiorelay;

import android.content.AttributionSource;
import android.content.Context;
import android.content.ContextWrapper;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Looper;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Low-Latency REMOTE_SUBMIX → HTTP WAV Stream
 *
 * Architektur:
 *  - Zero-Allocation Pool: 32 pre-allokierte Puffer, kein GC-Druck
 *  - Pre-Fill-Puffer: 3s Audio werden gesammelt bevor der HTTP-Server
 *    startet → Sonos kriegt beim Connect sofort einen 3s-Puffer-Burst,
 *    sodass WiFi-Jitter bis ~3s das Audio nicht unterbricht
 *  - Batch-Write: 8 Chunks (46ms) pro flush() → weniger Syscalls
 */
public class Main {

    static final int SAMPLE_RATE    = 44100;
    static final int PORT           = 9877;
    static final int CHUNK_SIZE     = 1024;  // ~5.8ms pro Chunk (AudioRecord-Granularität)

    // Anzahl Chunks für den Start-Pre-Fill-Puffer (~3 Sekunden)
    static final int PREFILL_CHUNKS = (int)(3.0 * SAMPLE_RATE * 4 / CHUNK_SIZE); // ≈517

    // Fortlaufender Streaming-Pool (185ms = 32 × 5.8ms)
    static final int POOL_SIZE      = 32;

    // Batching: 8 Chunks auf einmal senden, dann flush() → ~21 flushes/sec
    static final int BATCH_SIZE     = 8;

    static final AtomicBoolean running    = new AtomicBoolean(true);
    static final AtomicBoolean prefillReady = new AtomicBoolean(false);

    static final byte[][] bufferPool   = new byte[POOL_SIZE][CHUNK_SIZE];
    static final BlockingQueue<byte[]> freeBuffers = new ArrayBlockingQueue<>(POOL_SIZE);
    static final BlockingQueue<byte[]> audioQueue  = new ArrayBlockingQueue<>(POOL_SIZE);

    // Pre-Fill: separater Heap-Puffer (einmalig beim Start, kein Hot-Path)
    static final List<byte[]> prefillBuffer = new ArrayList<>(PREFILL_CHUNKS);

    static {
        for (byte[] buf : bufferPool) freeBuffers.offer(buf);
    }

    static class ShellContext extends ContextWrapper {
        private final AttributionSource shellSource;

        ShellContext(Context base) {
            super(base);
            AttributionSource tmp = null;
            try {
                java.lang.reflect.Constructor<AttributionSource> ctor =
                    AttributionSource.class.getDeclaredConstructor(
                        int.class, String.class, String.class);
                ctor.setAccessible(true);
                tmp = ctor.newInstance(2000, "com.android.shell", null);
            } catch (Exception e) {
                System.err.println("[ShellContext] Fehler: " + e);
            }
            shellSource = tmp;
        }

        @Override
        public AttributionSource getAttributionSource() {
            return shellSource;
        }
    }

    public static void main(String[] args) throws Exception {
        Looper.prepareMainLooper();
        System.out.println("[audiorelay] Start...");

        Object at = callSystemMain();
        if (at == null) { System.err.println("FEHLER: systemMain()"); System.exit(1); }

        Context sysCtx = getSystemContext(at);
        if (sysCtx == null) { System.err.println("FEHLER: kein Context"); System.exit(1); }

        AudioRecord record = buildAudioRecord(new ShellContext(sysCtx));
        if (record == null || record.getState() != AudioRecord.STATE_INITIALIZED) {
            System.err.println("[audiorelay] AudioRecord nicht initialisiert");
            System.exit(1);
        }

        record.startRecording();
        if (record.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
            System.err.println("[audiorelay] startRecording() gescheitert");
            System.exit(1);
        }

        int minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT);
        System.out.printf("[audiorelay] minBuf=%d (×4=%d), chunk=%d bytes (~%.1fms), prefill=%d chunks (~3s), pool=%d%n",
            minBuf, minBuf * 4, CHUNK_SIZE,
            (CHUNK_SIZE / 4.0 / SAMPLE_RATE) * 1000.0,
            PREFILL_CHUNKS, POOL_SIZE);

        // Phase 1: Pre-Fill-Puffer befüllen (auf eigenem Heap, nicht vom Pool)
        System.out.println("[audiorelay] Sammle Pre-Fill (" + PREFILL_CHUNKS + " chunks = ~3s)...");
        for (int i = 0; i < PREFILL_CHUNKS; i++) {
            byte[] buf = new byte[CHUNK_SIZE];
            int n = record.read(buf, 0, buf.length);
            if (n > 0) prefillBuffer.add(buf);
        }
        prefillReady.set(true);
        System.out.println("[audiorelay] Pre-Fill fertig. Starte HTTP-Server...");

        // Phase 2: Capture-Thread (Zero-Alloc-Pool)
        Thread captureThread = new Thread(() -> {
            int logCount = 0;
            int overflowCount = 0;
            while (running.get()) {
                byte[] buf;
                try {
                    buf = freeBuffers.poll(50, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    break;
                }
                if (buf == null) {
                    buf = audioQueue.poll();
                    if (buf == null) continue;
                    overflowCount++;
                }

                int n = record.read(buf, 0, buf.length);
                if (n <= 0) {
                    freeBuffers.offer(buf);
                    continue;
                }

                if (logCount++ % 1000 == 0) {
                    int maxAmp = 0;
                    for (int i = 0; i < n - 1; i += 2) {
                        int s = (buf[i] & 0xFF) | (buf[i + 1] << 8);
                        int a = Math.abs(s);
                        if (a > maxAmp) maxAmp = a;
                    }
                    System.out.println("[audiorelay] " + n + " bytes maxAmp=" + maxAmp
                        + (maxAmp < 10 ? " STILLE" : " AUDIO")
                        + " overflows=" + overflowCount);
                }

                if (!audioQueue.offer(buf)) {
                    byte[] dropped = audioQueue.poll();
                    if (dropped != null) freeBuffers.offer(dropped);
                    audioQueue.offer(buf);
                }
            }
        }, "CaptureThread");
        captureThread.setDaemon(true);
        captureThread.start();

        runHttpServer(record);
    }

    static AudioRecord buildAudioRecord(Context ctx) {
        try {
            int minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT);

            AudioRecord.Builder builder = new AudioRecord.Builder()
                .setContext(ctx)
                .setAudioSource(MediaRecorder.AudioSource.REMOTE_SUBMIX)
                .setAudioFormat(new AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                    .build())
                .setBufferSizeInBytes(Math.max(minBuf * 4, CHUNK_SIZE * 8));

            try {
                java.lang.reflect.Method m = AudioRecord.Builder.class
                    .getMethod("setPerformanceMode", int.class);
                m.invoke(builder, 1);
                System.out.println("[audiorelay] Low-Latency-Modus");
            } catch (Exception ignored) {}

            return builder.build();
        } catch (Exception e) {
            System.err.println("[audiorelay] AudioRecord-Fehler: " + e);
            return null;
        }
    }

    static void runHttpServer(AudioRecord record) {
        try (ServerSocket ss = new ServerSocket(PORT)) {
            ss.setReuseAddress(true);
            System.out.println("[audiorelay] HTTP-Server auf :" + PORT);
            while (running.get()) {
                try {
                    Socket client = ss.accept();
                    new Thread(() -> handleClient(client), "HttpClient").start();
                } catch (IOException e) {
                    if (running.get()) System.err.println("[audiorelay] Accept: " + e);
                }
            }
        } catch (IOException e) {
            System.err.println("[audiorelay] Server-Fehler: " + e);
        } finally {
            running.set(false);
            try { record.stop(); } catch (Exception ignored) {}
            record.release();
        }
    }

    static void handleClient(Socket socket) {
        try {
            socket.setTcpNoDelay(true);
            socket.setSendBufferSize(131072); // 128KB TCP-Sendepuffer

            byte[] reqBuf = new byte[2048];
            int n = socket.getInputStream().read(reqBuf);
            boolean isGet = n > 0 && new String(reqBuf, 0, n).startsWith("GET");

            // 64KB buffered output → reduziert flush()-Overhead drastisch
            BufferedOutputStream out = new BufferedOutputStream(socket.getOutputStream(), 65536);

            out.write(("HTTP/1.0 200 OK\r\nContent-Type: audio/wav\r\nConnection: close\r\n\r\n").getBytes());
            out.write(buildWavHeader());

            if (!isGet) {
                out.flush();
                return;
            }

            // Pre-Fill-Burst: sende ~3s Audio auf einmal → Sonos baut stabilen Puffer auf
            System.out.println("[audiorelay] Client verbunden, sende Pre-Fill (" + prefillBuffer.size() + " chunks)...");
            for (byte[] chunk : prefillBuffer) {
                out.write(chunk);
            }
            out.flush();

            // Während Pre-Fill-Send hat CaptureThread weitergelaufen → alte Frames entsorgen
            byte[] stale;
            while ((stale = audioQueue.poll()) != null) {
                freeBuffers.offer(stale);
            }
            System.out.println("[audiorelay] Pre-Fill gesendet. Streame live...");

            // Live-Stream: 8 Chunks batchen, dann flush
            int batchCount = 0;
            while (running.get()) {
                byte[] chunk = audioQueue.take();
                out.write(chunk);
                freeBuffers.offer(chunk);
                if (++batchCount >= BATCH_SIZE) {
                    out.flush();
                    batchCount = 0;
                }
            }

        } catch (Exception e) {
            System.out.println("[audiorelay] Client weg: " + e.getMessage());
            byte[] leftover;
            while ((leftover = audioQueue.poll()) != null) {
                freeBuffers.offer(leftover);
            }
        } finally {
            try { socket.close(); } catch (IOException ignored) {}
        }
    }

    static byte[] buildWavHeader() {
        ByteBuffer b = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes()); b.putInt(0x7FFFFFFF);
        b.put("WAVE".getBytes());
        b.put("fmt ".getBytes()); b.putInt(16);
        b.putShort((short)1); b.putShort((short)2);
        b.putInt(SAMPLE_RATE); b.putInt(SAMPLE_RATE * 4);
        b.putShort((short)4); b.putShort((short)16);
        b.put("data".getBytes()); b.putInt(0x7FFFFFFF);
        return b.array();
    }

    static Object callSystemMain() {
        try {
            Class<?> c = Class.forName("android.app.ActivityThread");
            return c.getMethod("systemMain").invoke(null);
        } catch (Exception e) {
            System.err.println("systemMain: " + e);
            return null;
        }
    }

    static Context getSystemContext(Object at) {
        try {
            return (Context) at.getClass().getMethod("getSystemContext").invoke(at);
        } catch (Exception e) {
            System.err.println("getSystemContext: " + e);
            return null;
        }
    }
}
