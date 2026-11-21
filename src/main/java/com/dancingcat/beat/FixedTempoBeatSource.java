package com.dancingcat.beat;

/** Produces beats at a fixed BPM. */
public final class FixedTempoBeatSource implements BeatSource {

    private final double bpm;
    private final long startMillis = System.currentTimeMillis();

    public FixedTempoBeatSource(double bpm) {
        this.bpm = bpm;
    }

    @Override
    public double beatPosition(long nowMillis) {
        return (nowMillis - startMillis) / 60000.0 * bpm;
    }
}
