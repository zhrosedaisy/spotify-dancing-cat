package com.dancingcat.ui;

/**
 * Kedinin kostümleri. Her biri kafanın sol üst köşesine göre (offsetX, offsetY) kaydırılarak
 * kafanın üstüne damgalanır, bu yüzden dans, takla ve uyurken de kediyle birlikte hareket eder.
 * Harfler CatSprites paletindeki renklerdir ('.' = boş).
 */
public enum Costume {
    NONE("Kostüm yok", 0, 0, null),

    PARTY_HAT("Parti şapkası", 0, -4, new String[]{
            ".......RR.......",
            ".......KK.......",
            "......KYYK......",
            "......KBBK......",
            ".....KYYYYK.....",
            ".....KBBBBK.....",
            "....KYYYYYYK....",
            "....KKKKKKKK....",
    }),

    CROWN("Taç", 0, -2, new String[]{
            "....K..KK..K....",
            "....KYKYYKYK....",
            "....KYYYYYYK....",
            "....KYRYYRYK....",
            "....KKKKKKKK....",
    }),

    SUNGLASSES("Güneş gözlüğü", 0, 6, new String[]{
            "KKKKKK....KKKKKK",
            ".KSSGKKKKKKSSGK.",
            ".KSSSK....KSSSK.",
            "..KKK......KKK..",
    }),

    HEADPHONES("Kulaklık", -2, -1, new String[]{
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

    BOW("Fiyonk", 11, -1, new String[]{
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
