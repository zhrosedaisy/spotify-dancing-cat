package com.dancingcat.ui;

import java.awt.Color;

/**
 * Butonların, balonların ve notaların renkleri. Albüm kapağının baskın renginden üretilir;
 * şarkı değişince eski temadan yenisine yumuşakça geçilir.
 */
public record Theme(Color buttonBg, Color buttonHover, Color accent, Color bubble, Color nextLine, Color[] notes) {

    public static final Theme DEFAULT = new Theme(
            new Color(0x3A, 0x33, 0x46, 220),
            new Color(0x6C, 0x5B, 0x8A, 235),
            new Color(0xE0, 0x6C, 0x9F, 235),
            new Color(0x3A, 0x33, 0x46, 225),
            new Color(0xC9, 0xBF, 0xDB),
            new Color[]{new Color(0xFF8FA3), new Color(0x8ECAE6), new Color(0xFFD166)});

    /** Tek bir renkten uyumlu bir tema üretir. Renk çok griyse varsayılan temayı döner. */
    public static Theme fromColor(Color base) {
        float[] hsb = Color.RGBtoHSB(base.getRed(), base.getGreen(), base.getBlue(), null);
        float h = hsb[0], s = hsb[1];
        if (s < 0.15f) return DEFAULT;
        float soft = Math.min(s, 0.55f);
        return new Theme(
                hsb(h, soft * 0.65f, 0.27f, 220),
                hsb(h, soft, 0.48f, 235),
                hsb(h, clamp(s, 0.5f, 0.85f), clamp(hsb[2], 0.8f, 0.95f), 235),
                hsb(h, soft * 0.7f, 0.22f, 225),
                hsb(h, 0.18f, 0.88f, 255),
                new Color[]{
                        hsb(h, clamp(s, 0.45f, 0.8f), 0.95f, 255),
                        hsb(h + 0.12f, 0.5f, 0.95f, 255),
                        hsb(h - 0.12f, 0.5f, 0.95f, 255)});
    }

    /** this → to arasında t (0..1) kadar ilerlemiş tema. */
    public Theme lerp(Theme to, double t) {
        if (t <= 0) return this;
        if (t >= 1) return to;
        Color[] n = new Color[notes.length];
        for (int i = 0; i < n.length; i++) n[i] = mix(notes[i], to.notes[i % to.notes.length], t);
        return new Theme(mix(buttonBg, to.buttonBg, t), mix(buttonHover, to.buttonHover, t),
                mix(accent, to.accent, t), mix(bubble, to.bubble, t), mix(nextLine, to.nextLine, t), n);
    }

    private static Color mix(Color a, Color b, double t) {
        return new Color(
                (int) Math.round(a.getRed() + (b.getRed() - a.getRed()) * t),
                (int) Math.round(a.getGreen() + (b.getGreen() - a.getGreen()) * t),
                (int) Math.round(a.getBlue() + (b.getBlue() - a.getBlue()) * t),
                (int) Math.round(a.getAlpha() + (b.getAlpha() - a.getAlpha()) * t));
    }

    private static Color hsb(float h, float s, float b, int alpha) {
        int rgb = Color.HSBtoRGB((h % 1 + 1) % 1, s, b);
        return new Color((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF, alpha);
    }

    private static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
