package com.dancingcat.ui;

import java.awt.image.BufferedImage;
import java.util.Map;

/**
 * Kedinin pixel art kareleri. Her parça bir karakter ızgarası olarak çizilir,
 * sonra 32x32'lik bir tuvale "damgalanır". İleride bunları Piskel/Aseprite'ta
 * çizilmiş bir PNG sprite sheet ile değiştirebiliriz.
 */
public final class CatSprites {

    public static final int SIZE = 32;

    private static final Map<Character, Integer> PALETTE = Map.ofEntries(
            Map.entry('K', 0xFF3A3346), // dış çizgi
            Map.entry('O', 0xFFF1EDF3), // beyaz tüy (hafif gri, göğüsten ayrılsın diye)
            Map.entry('D', 0xFFD5CFDD), // açık gri lekeler
            Map.entry('W', 0xFFFFFFFF), // bembeyaz göğüs / ağız
            Map.entry('P', 0xFFFF8FA3), // pembe kulak, burun, yanak, dil
            Map.entry('E', 0xFF2B2233), // göz
            Map.entry('G', 0xFFFFFFFF), // göz parıltısı
            Map.entry('R', 0xFFE63946), // kırmızı (kalp, fiyonk)
            // Kostüm renkleri
            Map.entry('Y', 0xFFFFD166), // sarı / altın
            Map.entry('B', 0xFF8ECAE6), // açık mavi
            Map.entry('S', 0xFF151225), // güneş gözlüğü camı
            Map.entry('M', 0xFF6C5B8A), // kulaklık bandı
            Map.entry('C', 0xFFE06C9F)  // kulaklık pedi
    );

    private static final String[] HEAD_OPEN = {
            "..KK........KK..",
            ".KPPK......KPPK.",
            ".KOPOK....KOPOK.",
            ".KOOOOKKKKOOOOK.",
            "KOODOOOOOOOODOOK",
            "KOOOOOOOOOOOOOOK",
            "KOOEEOOOOOOEEOOK",
            "KOOEGOOOOOOEGOOK",
            "KOOOOOWPPWOOOOOK",
            "KPOOOWWWWWWOOOPK",
            ".KOOOOWWWWOOOOK.",
            "..KKKKKKKKKKKK..",
    };

    private static final String[] HEAD_CLOSED = withRows(HEAD_OPEN, 6,
            "KOOOOOOOOOOOOOOK",
            "KOOKKOOOOOOKKOOK");

    /** Esneme: gözler kapalı, ağız açık (pembe dil). */
    private static final String[] HEAD_YAWN = withRows(HEAD_CLOSED, 9,
            "KPOOOWKKKKWOOOPK",
            ".KOOOOKPPKOOOOK.");

    /** Yüzünü yalarken ağzına götürdüğü pati. */
    private static final String[] PAW_LICK = {
            ".KK.",
            "KWWK",
            "KWWK",
            "KOOK",
            "KOOK",
    };

    private static final String[] BODY = {
            ".KOOOOOOOOOOK.",
            "KOODOWWWWODOOK",
            "KOOOWWWWWWOOOK",
            "KODOWWWWWWODOK",
            "KOOOWWWWWWOOOK",
            "KOOOOWWWWOOOOK",
            "KOOOOOOOOOOOOK",
            "KOWWKOOOOKWWOK",
            "KWWWWKKKKWWWWK",
            ".KKKK....KKKK.",
    };

    private static final String[] PAW_UP = {
            "KKK..",
            "KWWK.",
            "KWWK.",
            ".KOOK",
            ".KOOK",
            "..KOK",
            "..KOK",
            "...KK",
    };

    private static final String[] TAIL = {
            "..KK.",
            ".KOOK",
            ".KODK",
            "..KOK",
            "..KOK",
            ".KOOK",
            "KOOK.",
            "KOK..",
            ".K...",
    };

    private static final String[] LOAF = {
            "...KKKKKKKKKKKKKK...",
            "..KOOOODOOOODOOOOK..",
            ".KOOOOOODOOOODOOOOK.",
            "KOOOOOOOOOOOOOOOOOOK",
            "KOOOOOOOOOOOOOOOOOOK",
            "KOWWWOOOOOOOOOOOOOOK",
            "KWWWWKOOOOOOOOOOOOK.",
            ".KKKK.KKKKKKKKKKKK..",
    };

    private static final String[] HEART = {
            ".KK.KK.",
            "KRRKRRK",
            "KRRRRRK",
            ".KRRRK.",
            "..KRK..",
            "...K...",
    };

    // Kontrol ikonları (7x7, beyaz)
    private static final String[] ICON_PREV = {
            "X.....X",
            "X....XX",
            "X...XXX",
            "X..XXXX",
            "X...XXX",
            "X....XX",
            "X.....X",
    };
    private static final String[] ICON_PLAY = {
            ".X.....",
            ".XX....",
            ".XXX...",
            ".XXXX..",
            ".XXX...",
            ".XX....",
            ".X.....",
    };
    private static final String[] ICON_PAUSE = {
            ".XX.XX.",
            ".XX.XX.",
            ".XX.XX.",
            ".XX.XX.",
            ".XX.XX.",
            ".XX.XX.",
            ".XX.XX.",
    };

    private static final String[] ICON_MIC = {
            "..XXX..",
            "..XXX..",
            "..XXX..",
            "X.XXX.X",
            ".X...X.",
            "..XXX..",
            "...X...",
    };

    private static final String[] ICON_HEART = {
            ".XX.XX.",
            "XXXXXXX",
            "XXXXXXX",
            ".XXXXX.",
            "..XXX..",
            "...X...",
            ".......",
    };

    // Drop anında etrafa saçılan yıldız (5x5)
    private static final String[] STAR = {
            "..X..",
            ".XXX.",
            "XXXXX",
            ".XXX.",
            "..X..",
    };

    /** Verilen renkte yıldız resmi. */
    public static BufferedImage star(int argb) {
        BufferedImage img = new BufferedImage(5, 5, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 5; y++)
            for (int x = 0; x < 5; x++)
                if (STAR[y].charAt(x) == 'X') img.setRGB(x, y, argb);
        return img;
    }

    // Dans ederken uçuşan nota (6x7)
    private static final String[] NOTE = {
            "...X..",
            "...XX.",
            "...X.X",
            "...X..",
            ".XXX..",
            "XXXX..",
            ".XX...",
    };

    public enum Eyes { OPEN, CLOSED }

    /** Verilen renkte nota resmi. */
    public static BufferedImage note(int argb) {
        BufferedImage img = new BufferedImage(6, 7, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < NOTE.length; y++)
            for (int x = 0; x < NOTE[y].length(); x++)
                if (NOTE[y].charAt(x) == 'X') img.setRGB(x, y, argb);
        return img;
    }

    /** Butonun ikonu. PLAY_PAUSE için çalıyorsa "durdur", değilse "oynat" ikonu. */
    public static BufferedImage icon(Control control, boolean playing) {
        String[] grid = switch (control) {
            case PREVIOUS -> ICON_PREV;
            case NEXT -> mirror(ICON_PREV);
            case PLAY_PAUSE -> playing ? ICON_PAUSE : ICON_PLAY;
            case LYRICS -> ICON_MIC;
            case LIKE -> ICON_HEART;
        };
        BufferedImage img = new BufferedImage(7, 7, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 7; y++)
            for (int x = 0; x < 7; x++)
                if (grid[y].charAt(x) == 'X') img.setRGB(x, y, 0xFFFFFFFF);
        return img;
    }

    private static String[] mirror(String[] grid) {
        String[] out = new String[grid.length];
        for (int i = 0; i < grid.length; i++) out[i] = new StringBuilder(grid[i]).reverse().toString();
        return out;
    }

    /** Oturan/dans eden kedinin duruşu. mouthOpen: esneme, licking: pati yalama. */
    public record Pose(int bounce, int headShift, boolean leftPawUp, boolean rightPawUp,
                       int tailShift, Eyes eyes, boolean mouthOpen, boolean licking) {
        public static final Pose IDLE = new Pose(0, 0, false, false, 0, Eyes.OPEN);

        public Pose(int bounce, int headShift, boolean leftPawUp, boolean rightPawUp, int tailShift, Eyes eyes) {
            this(bounce, headShift, leftPawUp, rightPawUp, tailShift, eyes, false, false);
        }
    }

    private CatSprites() {}

    public static BufferedImage sitting(Pose p, Costume costume) {
        BufferedImage img = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB);
        int bodyX = 9, bodyY = 20 - p.bounce();
        int headX = 8 + p.headShift(), headY = 9 - p.bounce();

        String[] head = p.mouthOpen() ? HEAD_YAWN : p.eyes() == Eyes.OPEN ? HEAD_OPEN : HEAD_CLOSED;
        stamp(img, TAIL, 22 + p.tailShift(), bodyY + 1, false);
        stamp(img, BODY, bodyX, bodyY, false);
        stamp(img, head, headX, headY, false);
        stampCostume(img, costume, headX, headY);
        if (p.leftPawUp()) stamp(img, PAW_UP, bodyX - 5, bodyY - 6, false);
        if (p.rightPawUp()) stamp(img, PAW_UP, bodyX + 14, bodyY - 6, true);
        if (p.licking()) stamp(img, PAW_LICK, headX + 4, headY + 8, false);
        return img;
    }

    /** Uyuyan kedi; breath 0/1 nefes alıp vermeyi gösterir. */
    public static BufferedImage sleeping(int breath, Costume costume) {
        BufferedImage img = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB);
        int headX = 2, headY = 13 + breath;
        stamp(img, LOAF, 10, 22 - breath, false);
        stamp(img, HEAD_CLOSED, headX, headY, false);
        stampCostume(img, costume, headX, headY);
        return img;
    }

    private static void stampCostume(BufferedImage img, Costume costume, int headX, int headY) {
        if (costume != null && costume.grid() != null) {
            stamp(img, costume.grid(), headX + costume.offsetX(), headY + costume.offsetY(), false);
        }
    }

    public static BufferedImage heart() {
        BufferedImage img = new BufferedImage(HEART[0].length(), HEART.length, BufferedImage.TYPE_INT_ARGB);
        stamp(img, HEART, 0, 0, false);
        return img;
    }

    private static void stamp(BufferedImage img, String[] grid, int x, int y, boolean mirror) {
        for (int row = 0; row < grid.length; row++) {
            String line = grid[row];
            for (int col = 0; col < line.length(); col++) {
                Integer argb = PALETTE.get(line.charAt(col));
                if (argb == null) continue;
                int px = x + (mirror ? line.length() - 1 - col : col);
                int py = y + row;
                if (px >= 0 && py >= 0 && px < img.getWidth() && py < img.getHeight()) {
                    img.setRGB(px, py, argb);
                }
            }
        }
    }

    private static String[] withRows(String[] base, int from, String... rows) {
        String[] copy = base.clone();
        System.arraycopy(rows, 0, copy, from, rows.length);
        return copy;
    }
}
