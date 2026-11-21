package com.dancingcat.spotify;

/**
 * Şarkının neresinde olduğumuzu milisaniye hassasiyetle, pürüzsüz tahmin eder.
 *
 * Spotify'dan saniyede bir gelen konum biraz "titrek"tir (ağ gecikmesi değişir). Her gelen
 * değeri doğrudan kullanırsak tahmin bazen geri gider ve sözler bir önceki satıra zıplar.
 * Bu sınıf:
 *  - küçük farkları yavaşça düzeltir,
 *  - büyük farklarda (ileri/geri sarma, şarkı değişimi) hemen eşitler,
 *  - çalarken asla geriye gitmez (gerekirse bir an "bekler").
 */
public final class PlaybackClock {

    private static final long HARD_SYNC_MS = 1500;

    private String key;
    private boolean playing;
    private long anchorPos;
    private long anchorTime;
    private long lastReturned;

    public synchronized void update(NowPlaying np, long now) {
        if (np == null) {
            key = null;
            playing = false;
            return;
        }
        long sample = np.progressAt(now);
        boolean sameTrack = np.key().equals(key);
        if (!sameTrack || np.playing() != playing || !np.playing()) {
            hardSync(np, sample, now);
            return;
        }
        long estimate = rawPosition(now);
        long error = sample - estimate;
        if (Math.abs(error) > HARD_SYNC_MS) {
            hardSync(np, sample, now); // sarıldı
        } else {
            anchorPos = estimate + error / 3; // yumuşak düzeltme
            anchorTime = now;
        }
    }

    /** Şimdiki konum (ms). Çalarken asla bir önceki değerden küçük dönmez. */
    public synchronized long positionAt(long now) {
        long p = rawPosition(now);
        if (playing) {
            p = Math.max(p, lastReturned);
            lastReturned = p;
        }
        return p;
    }

    private long rawPosition(long now) {
        return playing ? anchorPos + (now - anchorTime) : anchorPos;
    }

    private void hardSync(NowPlaying np, long sample, long now) {
        key = np.key();
        playing = np.playing();
        anchorPos = sample;
        anchorTime = now;
        lastReturned = sample;
    }
}
