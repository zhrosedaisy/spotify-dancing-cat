package com.dancingcat.ui;

import com.dancingcat.ui.CatSprites.Eyes;
import com.dancingcat.ui.CatSprites.Pose;

import java.util.Random;

/**
 * Müzik yokken kedi hep uyumasın: arada bir uyanıp küçük bir şey yapsın.
 * Zaman 25 saniyelik dilimlere bölünür; her dilimin ortasında 4 saniyelik bir hareket olur.
 * Hangi hareketin olacağı dilim numarasından türetilir (aynı an → aynı kare, titreme yok).
 */
public final class IdleBehavior {

    public enum Action { YAWN, LICK, LOOK_AROUND, TAIL_WAG }

    private static final long SLOT_MS = 25_000;
    private static final long START_MS = 12_000;
    private static final long LENGTH_MS = 4_000;

    private IdleBehavior() {}

    /** O an yapılan hareketin pozu; uyku zamanıysa null. */
    public static Pose at(long nowMillis) {
        long slot = nowMillis / SLOT_MS;
        long t = nowMillis % SLOT_MS - START_MS;
        if (t < 0 || t >= LENGTH_MS) return null;
        return switch (actionFor(slot)) {
            case YAWN -> yawn(t);
            case LICK -> lick(t);
            case LOOK_AROUND -> lookAround(t);
            case TAIL_WAG -> tailWag(t);
        };
    }

    static Action actionFor(long slot) {
        Action[] all = Action.values();
        return all[new Random(slot * 0x2545F4914F6CDD1DL).nextInt(all.length)];
    }

    /** Uyanır, kocaman esner, gözlerini kırpıştırır. */
    private static Pose yawn(long t) {
        if (t < 700) return new Pose(0, 0, false, false, 0, Eyes.OPEN);
        if (t < 2500) return new Pose(1, 0, false, false, 0, Eyes.CLOSED, true, false); // hafif gerinme
        if (t < 2800) return new Pose(0, 0, false, false, 0, Eyes.CLOSED);
        return new Pose(0, 0, false, false, 1, Eyes.OPEN);
    }

    /** Patisini yalayıp yüzünü siler. */
    private static Pose lick(long t) {
        boolean pawAtMouth = (t / 300) % 2 == 0 && t < 3400;
        int head = (t / 600) % 2 == 0 ? 0 : -1;
        return new Pose(0, head, false, false, 0, Eyes.CLOSED, false, pawAtMouth);
    }

    /** Sağa sola bakar, göz kırpar. */
    private static Pose lookAround(long t) {
        int phase = (int) (t / 700);
        int head = switch (phase) {
            case 1 -> -1;
            case 3 -> 1;
            default -> 0;
        };
        boolean blink = t % 1400 > 1250;
        return new Pose(0, head, false, false, 0, blink ? Eyes.CLOSED : Eyes.OPEN);
    }

    /** Oturup kuyruğunu sallar. */
    private static Pose tailWag(long t) {
        int tail = (t / 250) % 2 == 0 ? 0 : 1;
        boolean blink = t % 2000 > 1850;
        return new Pose(0, 0, false, false, tail, blink ? Eyes.CLOSED : Eyes.OPEN);
    }
}
