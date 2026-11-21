package com.dancingcat.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Where the app keeps its files.
 *
 * - Run from the project (IntelliJ / ./gradlew run): config.properties and tokens.json live in the
 *   project folder, and the audio helper is build/native/audiotap.
 * - Run as "Dancing Cat.app": the working directory is "/", so files live in
 *   ~/Library/Application Support/Dancing Cat, and the app tells us where its bundled audio
 *   helper is via -Ddancingcat.audiotap.
 */
public final class AppPaths {

    private AppPaths() {}

    /** Folder containing config.properties and tokens.json. */
    public static Path dataDir() {
        String override = System.getProperty("dancingcat.home");
        if (override != null) return Path.of(override);
        if (Files.exists(Path.of("config.properties"))) return Path.of("").toAbsolutePath(); // running from the project
        Path dir = Path.of(System.getProperty("user.home"), "Library", "Application Support", "Dancing Cat");
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            System.err.println("Could not create " + dir + ": " + e.getMessage());
        }
        return dir;
    }

    public static Path configFile() {
        return dataDir().resolve("config.properties");
    }

    public static Path tokenFile() {
        return dataDir().resolve("tokens.json");
    }

    /** The ScreenCaptureKit helper that captures Spotify's audio. */
    public static Path audioTap() {
        String bundled = System.getProperty("dancingcat.audiotap");
        return bundled != null ? Path.of(bundled) : Path.of("build/native/audiotap");
    }
}
