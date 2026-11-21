package com.dancingcat.beat;

/** Şarkının enerjisine göre kedinin ruh hali. */
public enum Mood {
    CHILL("Sakin"),
    GROOVE("Keyifli"),
    HYPE("Coşkulu");

    private final String label;

    Mood(String label) { this.label = label; }

    public String label() { return label; }
}
