package com.dancingcat.spotify;

/**
 * O an çalan şarkı. trackId null olabilir (reklam, podcast vb.).
 * progressMs, fetchedAtMillis anındaki konumdur; progressAt() ile şimdiki konumu tahmin ederiz
 * (sözleri senkronlamak için lazım olacak).
 */
public record NowPlaying(String trackId, String title, String artist, String primaryArtist, String album,
                         String albumImageUrl, long durationMs, long progressMs, boolean playing,
                         long fetchedAtMillis) {

    public long progressAt(long nowMillis) {
        if (!playing) return progressMs;
        long p = progressMs + (nowMillis - fetchedAtMillis);
        return durationMs > 0 ? Math.min(p, durationMs) : p;
    }

    /** Şarkıyı tanımlayan anahtar (değişince "yeni şarkı" deriz). */
    public String key() {
        return trackId != null ? trackId : label();
    }

    public String label() {
        return artist.isEmpty() ? title : title + " — " + artist;
    }
}
