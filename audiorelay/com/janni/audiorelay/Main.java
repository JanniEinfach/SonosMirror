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
import java.util.concurrent.atomic.AtomicLong;

/**
 * Low-Latency REMOTE_SUBMIX → HTTP WAV Stream
 *
 * Diagnose-Modus: misst Lücken zwischen AudioRecord-Reads um festzustellen
 * ob Stutter von der Capture-Seite oder der Netzwerk-Seite kommt.
 * Pre-Fill: 1 Sekunde (~172 Chunks) für minimalen Delay bei maximaler Stabilität.
 */
public class Main {

    static final int SAMPLE_RATE    = 44100;
    static final int PORT           = 9877;
    static final int CHUNK_SIZE     = 1024;

    // 1 Sekunde Pre-Fill (~172 Chunks) — Kompromiss: ~1.5s Delay, stabil gegen WiFi-Jitter
    static final int PREFILL_CHUNKS = (int)(1.0 * SAMPLE_RATE * 4 / CHUNK_SIZE);

    static final int POOL_SIZE      = 32;
    static final int BATCH_SIZE     = 8; // 8 Chunks pro flush = ~46ms Batch

    static final AtomicBoolean running     = new AtomicBoolean(true);
    static final AtomicBoolean prefillReady = new AtomicBoolean(false);

    // Pre-allokierter Pool für Zero-Alloc Streaming
    static final byte[][] bufferPool   = new byte[POOL_SIZE][CHUNK_SIZE];
    static final BlockingQueue<byte[]> freeBuffers = new ArrayBlockingQueue<>(POOL_SIZE);
    static final BlockingQueue<byte[]> audioQueue  = new ArrayBlockingQueue<>(POOL_SIZE);

    // Stille-Chunk für Fallback wenn AudioRecord kurz hängt
    static final byte[] SILENCE = new byte[CHUNK_SIZE];

    // Pre-Fill-Puffer (Heap, kein Pool)
    static final List<byte[]> prefillBuffer = new ArrayList<>(PREFILL_CHUNKS);

    // Diagnose: max. Lücke zwischen zwei aufeinanderfolgenden Captures
    static final AtomicLong maxGapNs = new AtomicLong(0);

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
                    AttributionSource.class.getDeclaredConstructor(int.class, String.class, String.class);
                ctor.setAccessible(true);
                tmp = ctor.newInstance(2000, "com.android.shell", null);
            } catch (Exception e) {
                System.err.println("[ShellContext] " + e);
            }
            shellSource = tmp;
        }
        @Override public AttributionSource getAttributionSource() { return shellSource; }
    }

    public static void main(String[] args) throws Exception {
        Looper.prepareMainLooper();
        System.out.println("[audiorelay] Start (1s PreFill + Diagnose)...");

        Object at = callSystemMain();
        if (at == null) { System.err.println("FEHLER: systemMain()"); System.exit(1); }
        Context sysCtx = getSystemContext(at);
        if (sysCtx == null) { System.err.println("FEHLER: Context"); System.exit(1); }

        AudioRecord record = buildAudioRecord(new ShellContext(sysCtx));
        if (record == null || record.getState() != AudioRecord.STATE_INITIALIZED) {
            System.err.println("[audiorelay] AudioRecord nicht initialisiert"); System.exit(1);
        }
        record.startRecording();

        int minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT);
        System.out.printf("[audiorelay] minBuf=%d×4=%d chunk=%d(~%.1fms) prefill=%d(~1s) pool=%d Port=%d%n",
            minBuf, minBuf*4, CHUNK_SIZE, (CHUNK_SIZE/4.0/SAMPLE_RATE)*1000.0,
            PREFILL_CHUNKS, POOL_SIZE, PORT);

        // Phase 1: 1s Pre-Fill sammeln
        System.out.println("[audiorelay] Sammle 1s Pre-Fill (" + PREFILL_CHUNKS + " chunks)...");
        for (int i = 0; i < PREFILL_CHUNKS; i++) {
            byte[] buf = new byte[CHUNK_SIZE];
            if (record.read(buf, 0, buf.length) > 0) prefillBuffer.add(buf);
        }
        prefillReady.set(true);
        System.out.println("[audiorelay] Pre-Fill fertig.");

        // Phase 2: Capture-Thread mit Timing-Diagnose
        Thread captureThread = new Thread(() -> {
            int logCount = 0;
            int overflowCount = 0;
            long lastReadEnd = System.nanoTime();
            long localMaxGap = 0;

            while (running.get()) {
                byte[] buf;
                try {
                    buf = freeBuffers.poll(50, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) { break; }

                if (buf == null) {
                    buf = audioQueue.poll();
                    if (buf == null) continue;
                    overflowCount++;
                }

                long readStart = System.nanoTime();
                int n = record.read(buf, 0, buf.length);
                long readEnd = System.nanoTime();

                if (n <= 0) { freeBuffers.offer(buf); continue; }

                // Lücke = Zeit ZWISCHEN zwei read()-Aufrufen (sollte ~5.8ms sein)
                long gapNs = readStart - lastReadEnd;
                if (gapNs > localMaxGap) {
                    localMaxGap = gapNs;
                    maxGapNs.set(localMaxGap);
                }
                lastReadEnd = readEnd;

                if (logCount++ % 1000 == 0) {
                    int maxAmp = 0;
                    for (int i = 0; i < n-1; i+=2) {
                        int s = (buf[i]&0xFF)|(buf[i+1]<<8);
                        int a = Math.abs(s); if (a > maxAmp) maxAmp = a;
                    }
                    long maxGapMs = maxGapNs.get() / 1_000_000;
                    System.out.printf("[audiorelay] maxAmp=%d %s overflows=%d maxGap=%dms%n",
                        maxAmp, maxAmp < 10 ? "STILLE" : "AUDIO", overflowCount, maxGapMs);
                    // MaxGap nach Log zurücksetzen für nächste 1000-Chunk-Periode
                    maxGapNs.set(0);
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
            AudioRecord.Builder b = new AudioRecord.Builder()
                .setContext(ctx)
                .setAudioSource(MediaRecorder.AudioSource.REMOTE_SUBMIX)
                .setAudioFormat(new AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_IN_STEREO).build())
                .setBufferSizeInBytes(Math.max(minBuf * 4, CHUNK_SIZE * 8));
            try {
                java.lang.reflect.Method m = AudioRecord.Builder.class.getMethod("setPerformanceMode", int.class);
                m.invoke(b, 1);
            } catch (Exception ignored) {}
            return b.build();
        } catch (Exception e) {
            System.err.println("[audiorelay] AudioRecord: " + e); return null;
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
            System.err.println("[audiorelay] Server: " + e);
        } finally {
            running.set(false);
            try { record.stop(); } catch (Exception ignored) {}
            record.release();
        }
    }

    static void handleClient(Socket socket) {
        try {
            socket.setTcpNoDelay(true);
            socket.setSendBufferSize(131072);

            byte[] req = new byte[2048];
            int n = socket.getInputStream().read(req);
            boolean isGet = n > 0 && new String(req, 0, n).startsWith("GET");

            BufferedOutputStream out = new BufferedOutputStream(socket.getOutputStream(), 65536);
            out.write("HTTP/1.0 200 OK\r\nContent-Type: audio/wav\r\nConnection: close\r\n\r\n".getBytes());
            out.write(buildWavHeader());

            if (!isGet) { out.flush(); return; }

            // Pre-Fill-Burst: 1s Audio sofort senden
            System.out.println("[audiorelay] Client verbunden, sende 1s Pre-Fill...");
            for (byte[] chunk : prefillBuffer) out.write(chunk);
            out.flush();

            // Aufgelaufene Frames während Pre-Fill-Send verwerfen
            byte[] stale;
            while ((stale = audioQueue.poll()) != null) freeBuffers.offer(stale);
            System.out.println("[audiorelay] Pre-Fill gesendet. Streame live...");

            // Live-Stream: 8 Chunks batchen, sonst Silence senden (verhindert TCP-Lücken)
            int batchCount = 0;
            while (running.get()) {
                byte[] chunk = audioQueue.poll(20, TimeUnit.MILLISECONDS);
                if (chunk == null) {
                    // AudioRecord-Verzögerung: Stille senden statt TCP-Lücke erzeugen
                    out.write(SILENCE);
                } else {
                    out.write(chunk);
                    freeBuffers.offer(chunk);
                }
                if (++batchCount >= BATCH_SIZE) {
                    out.flush();
                    batchCount = 0;
                }
            }
        } catch (Exception e) {
            System.out.println("[audiorelay] Client weg: " + e.getMessage());
            byte[] leftover;
            while ((leftover = audioQueue.poll()) != null) freeBuffers.offer(leftover);
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
        try { return Class.forName("android.app.ActivityThread").getMethod("systemMain").invoke(null); }
        catch (Exception e) { System.err.println("systemMain: " + e); return null; }
    }

    static Context getSystemContext(Object at) {
        try { return (Context) at.getClass().getMethod("getSystemContext").invoke(at); }
        catch (Exception e) { System.err.println("getSystemContext: " + e); return null; }
    }
}
