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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Low-Latency REMOTE_SUBMIX → HTTP WAV Stream
 *
 * Zero-Allocation Hot-Path: pre-allocierter Buffer-Pool verhindert GC-Pausen
 * im Capture-Thread, die sonst zu AudioRecord-Overflows führen.
 */
public class Main {

    static final int SAMPLE_RATE = 44100;
    static final int PORT        = 9877;
    static final int CHUNK_SIZE  = 1024;  // ~5.8ms pro Chunk

    // Pool-Größe: genug Puffer für ~185ms ohne neue Allokation
    static final int POOL_SIZE   = 32;

    static final AtomicBoolean running = new AtomicBoolean(true);

    // Pre-allozierte Puffer — werden NIEMALS neu allokiert
    static final byte[][] bufferPool = new byte[POOL_SIZE][CHUNK_SIZE];

    // Freie Puffer, die der Capture-Thread holen kann
    static final BlockingQueue<byte[]> freeBuffers = new ArrayBlockingQueue<>(POOL_SIZE);
    // Gefüllte Puffer, die der HTTP-Thread senden soll
    static final BlockingQueue<byte[]> audioQueue  = new ArrayBlockingQueue<>(POOL_SIZE);

    static {
        // Pool einmalig befüllen — danach nie wieder new byte[]
        for (byte[] buf : bufferPool) {
            freeBuffers.offer(buf);
        }
    }

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
        System.out.println("[audiorelay] Start (Zero-Alloc Pool-Modus)...");

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
        System.out.printf("[audiorelay] OK! minBuf=%d bytes (×4=%d), chunk=%d bytes (≈%.1fms), pool=%d bufs, Port=%d%n",
            minBuf, minBuf * 4, CHUNK_SIZE, (CHUNK_SIZE / 4.0 / SAMPLE_RATE) * 1000.0, POOL_SIZE, PORT);

        // Capture-Thread: KEINERLEI Allokation im Hot-Path
        Thread captureThread = new Thread(() -> {
            int logCount = 0;
            int overflowCount = 0;
            while (running.get()) {
                // Freien Puffer aus dem Pool holen (kurzer Timeout statt ewig blockieren)
                byte[] buf;
                try {
                    buf = freeBuffers.poll(50, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    break;
                }
                if (buf == null) {
                    // Pool leer = HTTP-Thread hängt, alten Frame droppen
                    buf = audioQueue.poll();
                    if (buf == null) continue;
                    overflowCount++;
                }

                int n = record.read(buf, 0, buf.length);
                if (n <= 0) {
                    freeBuffers.offer(buf); // Puffer zurücklegen
                    continue;
                }

                if (logCount++ % 1000 == 0) {
                    int maxAmp = 0;
                    for (int i = 0; i < n - 1; i += 2) {
                        int s = (buf[i] & 0xFF) | (buf[i + 1] << 8);
                        int a = Math.abs(s);
                        if (a > maxAmp) maxAmp = a;
                    }
                    System.out.println("[audiorelay] " + n + " bytes, maxAmp=" + maxAmp
                        + (maxAmp < 10 ? " (STILLE)" : " (AUDIO!)")
                        + " overflows=" + overflowCount);
                }

                // Puffer an HTTP-Thread übergeben
                if (!audioQueue.offer(buf)) {
                    // Queue voll: ältesten Frame rauswerfen, zurück in Pool
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
                // 4× minBuf (~93ms) gegen GC-Pausen im Kernel-Buffer
                .setBufferSizeInBytes(Math.max(minBuf * 4, CHUNK_SIZE * 8));

            try {
                java.lang.reflect.Method m = AudioRecord.Builder.class
                    .getMethod("setPerformanceMode", int.class);
                m.invoke(builder, 1); // PERFORMANCE_MODE_LOW_LATENCY = 1
                System.out.println("[audiorelay] Low-Latency-Modus aktiviert");
            } catch (Exception ignored) {
                System.out.println("[audiorelay] Standard-Modus");
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
            socket.setTcpNoDelay(true);
            socket.setSendBufferSize(65536); // Großer TCP-Sendepuffer gegen Netzwerk-Jitter

            byte[] reqBuf = new byte[2048];
            int n = socket.getInputStream().read(reqBuf);
            boolean isGet = n > 0 && new String(reqBuf, 0, n).startsWith("GET");

            OutputStream out = socket.getOutputStream();
            String header = "HTTP/1.0 200 OK\r\nContent-Type: audio/wav\r\nConnection: close\r\n\r\n";
            out.write(header.getBytes());

            if (!isGet) return;

            out.write(buildWavHeader());
            out.flush();

            // Alle noch im audioQueue befindlichen alten Frames verwerfen,
            // Puffer zurück in den Free-Pool
            byte[] stale;
            while ((stale = audioQueue.poll()) != null) {
                freeBuffers.offer(stale);
            }
            System.out.println("[audiorelay] Client verbunden, Queue gespült, streame live...");

            while (running.get()) {
                byte[] chunk = audioQueue.take(); // blockiert bis Daten da
                out.write(chunk);
                out.flush();
                freeBuffers.offer(chunk); // Puffer sofort zurück in den Pool
            }
        } catch (Exception e) {
            System.out.println("[audiorelay] Client weg: " + e.getMessage());
            // Alle hängenden Frames zurück in den Pool
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
}
