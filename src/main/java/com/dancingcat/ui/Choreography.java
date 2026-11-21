package com.dancingcat.ui;

import com.dancingcat.beat.Mood;
import com.dancingcat.ui.CatSprites.Eyes;
import com.dancingcat.ui.CatSprites.Pose;

import java.util.Random;

/**
 * Dans koreografisi. Şarkı 4 vuruşluk ölçülere bölünür, her ölçüye bir figür seçilir.
 * Seçim ölçü numarası + ruh halinden türetildiği için aynı an hep aynı kareyi verir (titreme olmaz).
 *
 * Ruh hali figür seçimini etkiler:
 *  - Sakin:    çoğunlukla yavaş sallanma, yarım hızda temel dans
 *  - Keyifli:  karışık
 *  - Coşkulu:  bol takla, dönüş, el sallama; zıplamalar daha yüksek
 */
public final class Choreography {

    public enum Move { BASIC, SLOW, SWAY, SIDE_STEP, SPIN, WAVE, FLIP }

    /**
     * Bir dans karesi.
     * dx/dy: sprite pikseli cinsinden kayma (dy negatif = yukarı),
     * rotStep: 30°'lik dönüş adımı (0-11), mirror: aynalı çiz (arkasını dönme).
     */
    public record Frame(Pose pose, int dx, int dy, int rotStep, boolean mirror) {}

    private Choreography() {}

    public static Frame at(double beatPos, Mood mood) {
        int bar = (int) Math.floor(beatPos / 4);
        double inBar = beatPos - bar * 4.0;
        Frame f = switch (moveForBar(bar, mood)) {
            case BASIC -> basic(beatPos);
            case SLOW -> basic(beatPos / 2);
            case SWAY -> sway(beatPos);
            case SIDE_STEP -> sideStep(beatPos);
            case SPIN -> spin(beatPos);
            case WAVE -> wave(beatPos);
            case FLIP -> inBar < 2 ? flip(inBar / 2) : basic(beatPos);
        };
        if (mood == Mood.HYPE && f.pose().bounce() > 0) { // coşkulu: daha yüksek zıpla
            Pose p = f.pose();
            f = new Frame(new Pose(p.bounce() + 1, p.headShift(), p.leftPawUp(), p.rightPawUp(),
                    p.tailShift(), p.eyes()), f.dx(), f.dy(), f.rotStep(), f.mirror());
        }
        return f;
    }

    public static Move moveForBar(int bar, Mood mood) {
        Move m = rawMove(bar, mood);
        if (m == Move.FLIP && rawMove(bar - 1, mood) == Move.FLIP) return Move.BASIC; // art arda iki takla yok
        return m;
    }

    private static Move rawMove(int bar, Mood mood) {
        if (bar < 2) return mood == Mood.CHILL ? Move.SWAY : Move.BASIC; // ısınma
        int v = new Random(bar * 0x9E3779B97F4A7C15L).nextInt(100);
        return switch (mood) {
            case CHILL -> v < 55 ? Move.SWAY : v < 90 ? Move.SLOW : Move.SIDE_STEP;
            case GROOVE -> v < 35 ? Move.BASIC : v < 55 ? Move.SIDE_STEP : v < 70 ? Move.SPIN
                    : v < 87 ? Move.WAVE : Move.FLIP;
            case HYPE -> v < 15 ? Move.BASIC : v < 30 ? Move.SIDE_STEP : v < 50 ? Move.SPIN
                    : v < 72 ? Move.WAVE : Move.FLIP;
        };
    }

    /** Vuruşta aşağı + tek pati yukarı, arada zıpla. */
    private static Frame basic(double beatPos) {
        int step = (int) Math.floor(beatPos * 2) & 3;
        Pose p = switch (step) {
            case 0 -> new Pose(0, -1, true, false, 0, Eyes.OPEN);
            case 1 -> new Pose(2, 0, false, false, 1, Eyes.CLOSED);
            case 2 -> new Pose(0, 1, false, true, 0, Eyes.OPEN);
            default -> new Pose(2, 0, false, false, 1, Eyes.CLOSED);
        };
        return new Frame(p, 0, 0, 0, false);
    }

    /** Sakin sallanma: gözler kapalı, her vuruşta bir yana yaslanır. */
    private static Frame sway(double beatPos) {
        boolean left = ((int) Math.floor(beatPos) & 1) == 0;
        Pose p = new Pose(0, left ? -1 : 1, false, false, left ? 0 : 1, Eyes.CLOSED);
        return new Frame(p, left ? -1 : 1, 0, 0, false);
    }

    /** Sağa sola adım. */
    private static Frame sideStep(double beatPos) {
        int beat = (int) Math.floor(beatPos);
        boolean offBeat = ((int) Math.floor(beatPos * 2) & 1) == 1;
        boolean left = (beat & 1) == 0;
        Pose p = new Pose(offBeat ? 1 : 0, left ? -1 : 1, left, !left, offBeat ? 1 : 0, Eyes.OPEN);
        return new Frame(p, left ? -3 : 3, 0, 0, !left);
    }

    /** Her vuruşta arkasını dönüp geri döner. */
    private static Frame spin(double beatPos) {
        Frame f = basic(beatPos);
        return new Frame(f.pose(), 0, 0, 0, ((int) Math.floor(beatPos) & 1) == 1);
    }

    /** İki patiyle hızlı el sallama (vuruş başına 4 kare). */
    private static Frame wave(double beatPos) {
        int q = (int) Math.floor(beatPos * 4) & 3;
        Pose p = switch (q) {
            case 0 -> new Pose(1, -1, true, true, 0, Eyes.CLOSED);
            case 1 -> new Pose(0, 0, true, false, 1, Eyes.CLOSED);
            case 2 -> new Pose(1, 1, true, true, 0, Eyes.CLOSED);
            default -> new Pose(0, 0, false, true, 1, Eyes.CLOSED);
        };
        return new Frame(p, 0, 0, 0, false);
    }

    /** Takla: 2 vuruş boyunca zıplayıp 360° döner. t = 0..1 */
    private static Frame flip(double t) {
        int rot = Math.min(11, (int) (t * 12));
        int jump = (int) Math.round(Math.sin(Math.PI * t) * 8);
        Pose p = new Pose(0, 0, true, true, 0, Eyes.CLOSED);
        return new Frame(p, 0, -jump, rot, false);
    }

    /** Drop anı: dev zıplama + takla, ağız açık (heyecan!). t = 0..1 */
    public static Frame drop(double t) {
        int rot = t < 0.85 ? Math.min(11, (int) (t / 0.85 * 12)) : 0;
        int jump = (int) Math.round(Math.sin(Math.PI * t) * 12);
        Pose p = new Pose(0, 0, true, true, 0, Eyes.CLOSED, true, false);
        return new Frame(p, 0, -jump, rot, false);
    }
}
