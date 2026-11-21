package com.dancingcat.ui;

/**
 * The cat's costumes. Each one is stamped onto the head, offset (offsetX, offsetY) from the head's
 * top-left corner, so it moves with the cat while dancing, flipping and sleeping.
 * Letters are colors from the CatSprites palette ('.' = transparent).
 */
public enum Costume {
    NONE("No costume", 0, 0, null),

    PARTY_HAT("Party hat", 0, -4, new String[]{
            ".......RR.......",
            ".......KK.......",
            "......KYYK......",
            "......KBBK......",
            ".....KYYYYK.....",
            ".....KBBBBK.....",
            "....KYYYYYYK....",
            "....KKKKKKKK....",
    }),

    CROWN("Crown", 0, -2, new String[]{
            "....K..KK..K....",
            "....KYKYYKYK....",
            "....KYYYYYYK....",
            "....KYRYYRYK....",
            "....KKKKKKKK....",
    }),

    SUNGLASSES("Sunglasses", 0, 6, new String[]{
            "KKKKKK....KKKKKK",
            ".KSSGKKKKKKSSGK.",
            ".KSSSK....KSSSK.",
            "..KKK......KKK..",
    }),

    HEADPHONES("Headphones", -2, -1, new String[]{
            "......KKKKKKKK......",
            ".....KMMMMMMMMK.....",
            "....KMK......KMK....",
            "...KMK........KMK...",
            "..KMK..........KMK..",
            "..KMK..........KMK..",
            "KKKK............KKKK",
            "KCCK............KCCK",
            "KCCK............KCCK",
            "KCCK............KCCK",
            "KKKK............KKKK",
    }),

    BOW("Bow", 11, -1, new String[]{
            "KK.KK",
            "KRKRK",
            "KK.KK",
    });

    private final String label;
    private final int offsetX;
    private final int offsetY;
    private final String[] grid;

    Costume(String label, int offsetX, int offsetY, String[] grid) {
        this.label = label;
        this.offsetX = offsetX;
        this.offsetY = offsetY;
        this.grid = grid;
    }

    public String label() { return label; }

    int offsetX() { return offsetX; }

    int offsetY() { return offsetY; }

    String[] grid() { return grid; }
}
