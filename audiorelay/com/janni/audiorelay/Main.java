package com.janni.audiorelay;

import android.content.AttributionSource;
import android.content.Context;
import android.content.ContextWrapper;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Looper;

import java.io.IOException;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Low-Latency REMOTE_SUBMIX → HTTP WAV Stream
 * Optimierungen:
 *  - CHUNK_SIZE=1024 → 5.8ms pro Chunk
 *  - AudioRecord-Puffer = genau minBuf (kein künstliches Maximum)
 *  - PERFORMANCE_MODE_LOW_LATENCY
 *  - TCP_NODELAY auf Client-Socket
 *  - Queue nur 8 Slots → max ~46ms Software-Puffer
 *  - flush() nach jedem Chunk
 *  - queue.clear() bei Connect UND Disconnect (nie veraltete Daten senden)
 */
public class Main {

    static final int SAMPLE_RATE = 44100;
    static final int PORT        = 9877;
    static final int CHUNK_SIZE  = 1024;   // 1024 = ~5.8ms pro Chunk (minimale Latenz)

    static final AtomicBoolean running = new AtomicBoolean(true);
    // 32 Slots = max ~185ms Software-Puffer — schützt gegen kurze GC-Pausen ohne merkbare Latenz
    static final BlockingQueue<byte[]> queue = new ArrayBlockingQueue<>(32);

    /** Context-Wrapper der getAttributionSource() auf com.android.shell umleitet */
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
                System.err.println("[ShellContext] AttributionSource Fehler: " + e);
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

        System.out.println("[audiorelay] Start (Low-Latency-Modus)...");

        Object at = callSystemMain();
        if (at == null) { System.err.println("FEHLER: systemMain()"); System.exit(1); }

        Context sysCtx = getSystemContext(at);
        if (sysCtx == null) { System.err.println("FEHLER: kein Context"); System.exit(1); }

        ShellContext shellCtx = new ShellContext(sysCtx);

        AudioRecord record = buildAudioRecord(shellCtx);
        if (record == null || record.getState() != AudioRecord.STATE_INITIALIZED) {
            System.err.println("[audiorelay] FEHLER: AudioRecord nicht initialisiert");
            System.exit(1);
        }

        record.startRecording();
        if (record.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
            System.err.println("[audiorelay] FEHLER: startRecording() gescheitert");
            System.exit(1);
        }

        int minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT);
        double latencyMs = (minBuf / 4.0 / SAMPLE_RATE) * 1000.0;
        System.out.printf("[audiorelay] OK! minBuf=%d bytes (≈%.1fms), chunk=%d bytes (≈%.1fms), Port=%d%n",
            minBuf, latencyMs, CHUNK_SIZE, (CHUNK_SIZE / 4.0 / SAMPLE_RATE) * 1000.0, PORT);

        // Capture-Thread: liest REMOTE_SUBMIX und füllt die Queue
        Thread captureThread = new Thread(() -> {
            byte[] buf = new byte[CHUNK_SIZE];
            int logCount = 0;
            while (running.get()) {
                int n = record.read(buf, 0, buf.length);
                if (n > 0) {
                    if (logCount++ % 1000 == 0) {
                        int maxAmp = 0;
                        for (int i = 0; i < n - 1; i += 2) {
                            int s = (buf[i] & 0xFF) | (buf[i + 1] << 8);
                            int a = Math.abs(s);
                            if (a > maxAmp) maxAmp = a;
                        }
                        System.out.println("[audiorelay] " + n + " bytes, maxAmp=" + maxAmp
                            + (maxAmp < 10 ? " (STILLE)" : " (AUDIO!)"));
                    }
                    byte[] chunk = new byte[n];
                    System.arraycopy(buf, 0, chunk, 0, n);
                    // Wenn Queue voll: älteste Daten verwerfen (lieber Lücke als Latenz)
                    if (!queue.offer(chunk)) {
                        queue.poll();
                        queue.offer(chunk);
                    }
                }
            }
        }, "CaptureThread");
        captureThread.setDaemon(true);
        captureThread.start();

        runHttpServer(record);
    }

    static AudioRecord buildAudioRecord(Context ctx) {
        try {
            // Genau minBuf verwenden — kein künstliches Maximum für niedrigste Latenz
            int minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_STEREO,
                AudioFormat.ENCODING_PCM_16BIT);

            AudioFormat format = new AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                .build();

            AudioRecord.Builder builder = new AudioRecord.Builder()
                .setContext(ctx)
                .setAudioSource(MediaRecorder.AudioSource.REMOTE_SUBMIX)
                .setAudioFormat(format)
                // minBuf*4 = ~93ms interner Reserve-Puffer gegen GC-Pausen
                .setBufferSizeInBytes(Math.max(minBuf * 4, CHUNK_SIZE * 8));

            // Low-Latency-Modus (API 29+)
            try {
                java.lang.reflect.Method m = AudioRecord.Builder.class
                    .getMethod("setPerformanceMode", int.class);
                m.invoke(builder, 1); // PERFORMANCE_MODE_LOW_LATENCY = 1
                System.out.println("[audiorelay] Low-Latency-Modus aktiviert");
            } catch (Exception ignored) {
                System.out.println("[audiorelay] Low-Latency nicht verfügbar, Standard-Modus");
            }

            return builder.build();
        } catch (Exception e) {
            System.err.println("[audiorelay] AudioRecord-Exception: " + e);
            return null;
        }
    }

    static Object callSystemMain() {
        try {
            Class<?> atClass = Class.forName("android.app.ActivityThread");
            java.lang.reflect.Method m = atClass.getMethod("systemMain");
            return m.invoke(null);
        } catch (Exception e) {
            System.err.println("systemMain Fehler: " + e);
            return null;
        }
    }

    static Context getSystemContext(Object activityThread) {
        try {
            Class<?> atClass = activityThread.getClass();
            java.lang.reflect.Method m = atClass.getMethod("getSystemContext");
            return (Context) m.invoke(activityThread);
        } catch (Exception e) {
            System.err.println("getSystemContext Fehler: " + e);
            return null;
        }
    }

    // ─── HTTP WAV Server ──────────────────────────────────────────────────────

    static void runHttpServer(AudioRecord record) {
        try (ServerSocket ss = new ServerSocket(PORT)) {
            ss.setReuseAddress(true);
            System.out.println("[audiorelay] Server läuft auf :" + PORT);
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
            socket.setTcpNoDelay(true); // sofortiges Senden, kein Nagle-Stall
            byte[] buf = new byte[2048];
            int n = socket.getInputStream().read(buf);
            boolean isGet = n > 0 && new String(buf, 0, n).startsWith("GET");

            OutputStream out = socket.getOutputStream();
            String header = "HTTP/1.0 200 OK\r\nContent-Type: audio/wav\r\nConnection: close\r\n\r\n";
            out.write(header.getBytes());

            if (!isGet) { return; }

            out.write(buildWavHeader());
            out.flush();

            // Veraltete Daten SOFORT verwerfen — neuer Client kriegt nur frisches Audio
            queue.clear();
            System.out.println("[audiorelay] Client verbunden, Queue geleert, streame live...");

            while (running.get()) {
                byte[] chunk = queue.take(); // Blockierend — kein Stille-Fallback
                out.write(chunk);
                out.flush(); // Sofort senden, nicht auf TCP-Puffer warten
            }
        } catch (Exception e) {
            System.out.println("[audiorelay] Client weg: " + e.getMessage());
            queue.clear(); // Veraltete Daten verwerfen für sauberen Reconnect
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
}
