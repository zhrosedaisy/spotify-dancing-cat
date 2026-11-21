package com.dancingcat.spotify;

/**
 * The track that is currently playing. trackId may be null (ads, podcasts, ...).
 * progressMs is the position at fetchedAtMillis; progressAt() estimates the current position
 * (used to keep lyrics in sync).
 */
public record NowPlaying(String trackId, String title, String artist, String primaryArtist, String album,
                         String albumImageUrl, long durationMs, long progressMs, boolean playing,
                         long fetchedAtMillis) {

    public long progressAt(long nowMillis) {
        if (!playing) return progressMs;
        long p = progressMs + (nowMillis - fetchedAtMillis);
        return durationMs > 0 ? Math.min(p, durationMs) : p;
    }

    /** A key identifying the song (when it changes, it is a new song). */
    public String key() {
        return trackId != null ? trackId : label();
    }

    public String label() {
        return artist.isEmpty() ? title : title + " — " + artist;
    }
}
