package com.dancingcat.beat;

/** The cat's mood, based on the song's energy. */
public enum Mood {
    CHILL("Chill"),
    GROOVE("Groove"),
    HYPE("Hype");

    private final String label;

    Mood(String label) { this.label = label; }

    public String label() { return label; }
}
