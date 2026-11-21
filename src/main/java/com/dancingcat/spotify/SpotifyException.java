package com.dancingcat.spotify;

import java.io.IOException;

/** An error from Spotify. retryAfterSeconds > 0 means "too many requests" (429). */
public class SpotifyException extends IOException {

    private final int status;
    private final int retryAfterSeconds;

    public SpotifyException(String message) {
        this(message, 0, 0);
    }

    public SpotifyException(String message, int status, int retryAfterSeconds) {
        super(message);
        this.status = status;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public int status() { return status; }

    public int retryAfterSeconds() { return retryAfterSeconds; }
}
