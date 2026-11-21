package com.dancingcat.config;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/** Settings from config.properties. */
public record AppConfig(String spotifyClientId, String spotifyRedirectUri, double danceBpm, int catScale) {

    private static final String PLACEHOLDER = "YOUR_CLIENT_ID";

    public static AppConfig load(Path file) {
        Properties props = new Properties();
        if (Files.exists(file)) {
            try (Reader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                props.load(in);
            } catch (IOException e) {
                System.err.println("Could not read config.properties: " + e.getMessage());
            }
        } else {
            System.err.println("Warning: " + file.toAbsolutePath()
                    + " not found. Copy config.properties.example and fill it in.");
        }
        return new AppConfig(
                props.getProperty("spotify.clientId", PLACEHOLDER).trim(),
                props.getProperty("spotify.redirectUri", "http://127.0.0.1:8888/callback").trim(),
                Double.parseDouble(props.getProperty("dance.bpm", "120").trim()),
                Math.max(1, Integer.parseInt(props.getProperty("cat.scale", "3").trim())));
    }

    public boolean hasSpotifyClientId() {
        return !spotifyClientId.isBlank() && !spotifyClientId.equals(PLACEHOLDER);
    }
}
