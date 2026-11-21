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
 * Runs the build/native/audiotap helper and feeds its raw audio into the BeatTracker.
 * Restarts the helper a few seconds after it exits (unless permission was denied).
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
            System.err.println("Audio helper not found (" + helper + "). Build it with: ./gradlew classes");
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
                        Live beat detection needs audio recording permission:
                          System Settings → Privacy & Security → Screen & System Audio Recording
                          → enable the app you launched the cat from (IntelliJ IDEA or Terminal),
                          then restart the cat.""");
                onStatus.accept(Status.PERMISSION_DENIED);
                return;
            }
            try {
                Thread.sleep(5000); // Spotify closed etc. -> try again a bit later
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
                // the helper exited
            }
            return p.waitFor();
        } catch (IOException e) {
            System.err.println("Could not start the audio helper: " + e.getMessage());
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
                if (!line.startsWith("[audiotap]")) continue; // skip the system's noisy log lines
                if (line.contains("ready")) onStatus.accept(Status.LISTENING);
                if (!line.contains("buffer-error")) System.out.println("🎧 " + line);
            }
        } catch (IOException ignored) {
        }
    }
}
