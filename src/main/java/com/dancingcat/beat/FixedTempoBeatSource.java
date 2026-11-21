package com.dancingcat.beat;

/** Sabit BPM ile vuruş üretir. */
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
