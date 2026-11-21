package com.dancingcat.spotify;

/**
 * Smoothly estimates the playback position with millisecond precision.
 *
 * The position Spotify reports once per second is a bit "jittery" (network latency varies).
 * Using every report directly would sometimes move the estimate backwards and make the lyrics
 * jump back to the previous line. This class:
 *  - corrects small differences gradually,
 *  - snaps immediately on big differences (seeking, track change),
 *  - never runs backwards while playing (if needed it briefly "waits").
 */
public final class PlaybackClock {

    /** Jumping forward more than this = the user seeked; snap to it. */
    private static final long HARD_SYNC_MS = 1500;
    /** Jumping back is only trusted when it's big (a real seek), not when it's network jitter. */
    private static final long HARD_SYNC_BACK_MS = 3000;

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
        if (error > HARD_SYNC_MS || error < -HARD_SYNC_BACK_MS) {
            hardSync(np, sample, now); // seeked
        } else {
            anchorPos = estimate + error / 3; // gentle correction
            anchorTime = now;
        }
    }

    /** Current position (ms). While playing, never smaller than the previously returned value. */
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
