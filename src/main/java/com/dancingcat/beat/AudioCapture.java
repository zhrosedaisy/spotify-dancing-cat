package com.dancingcat.beat;

import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * build/native/audiotap yardımcısını çalıştırır, gelen ham sesi BeatTracker'a verir.
 * Yardımcı kapanırsa birkaç saniye sonra yeniden başlatır (izin reddedildiyse denemez).
 */
public final class AudioCapture {

    public enum Status { LISTENING, PERMISSION_DENIED, UNAVAILABLE }

    private static final String SPOTIFY_BUNDLE = "com.spotify.client";
    private static final int CHUNK_FLOATS = 512; // ~12 ms

    private final Path helper;
    private final BeatTracker tracker;
    private final Consumer<Status> onStatus;
    private volatile Process process;

    public AudioCapture(Path helper, BeatTracker tracker, Consumer<Status> onStatus) {
        this.helper = helper;
        this.tracker = tracker;
        this.onStatus = onStatus;
    }

    public void start() {
        if (!Files.isExecutable(helper)) {
            System.err.println("Ses yardımcısı bulunamadı (" + helper + "). Gradle ile derle: ./gradlew classes");
            onStatus.accept(Status.UNAVAILABLE);
            return;
        }
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            Process p = process;
            if (p != null) p.destroy();
        }));
        Thread.ofPlatform().daemon().name("audio-capture").start(this::runLoop);
    }

    private void runLoop() {
        while (true) {
            int exit = runOnce();
            if (exit == 3) {
                System.err.println("""
                        Ritim için ses kaydı izni gerekiyor:
                          Sistem Ayarları → Gizlilik ve Güvenlik → Ekran ve Sistem Sesi Kaydı
                          → uygulamayı başlattığın programı (IntelliJ IDEA veya Terminal) aç,
                          sonra kediyi yeniden başlat.""");
                onStatus.accept(Status.PERMISSION_DENIED);
                return;
            }
            try {
                Thread.sleep(5000); // Spotify kapanmış vb. → biraz sonra tekrar dene
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    private int runOnce() {
        try {
            Process p = new ProcessBuilder(helper.toString(), SPOTIFY_BUNDLE).start();
            process = p;
            Thread.ofVirtual().start(() -> readLog(p.getErrorStream()));

            DataInputStream in = new DataInputStream(p.getInputStream());
            byte[] bytes = new byte[CHUNK_FLOATS * 4];
            float[] samples = new float[CHUNK_FLOATS];
            ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
            try {
                while (true) {
                    in.readFully(bytes);
                    long arrival = System.nanoTime();
                    buffer.rewind();
                    buffer.asFloatBuffer().get(samples);
                    tracker.process(samples, CHUNK_FLOATS, arrival);
                }
            } catch (EOFException eof) {
                // yardımcı kapandı
            }
            return p.waitFor();
        } catch (IOException e) {
            System.err.println("Ses yardımcısı çalıştırılamadı: " + e.getMessage());
            return -1;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return -1;
        }
    }

    private void readLog(InputStream err) {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(err, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (!line.startsWith("[audiotap]")) continue; // sistemin gürültülü loglarını atla
                if (line.contains("ready")) onStatus.accept(Status.LISTENING);
                if (!line.contains("buffer-error")) System.out.println("🎧 " + line);
            }
        } catch (IOException ignored) {
        }
    }
}
