package com.dancingcat.beat;

/**
 * A source of beats. Every frame the cat asks it "which beat are we on right now?".
 * Implemented by a fixed-tempo source and by the live BeatTracker.
 */
public interface BeatSource {

    /** Number of beats since the start (e.g. 3.5 = halfway through the fourth beat). */
    double beatPosition(long nowMillis);
}
