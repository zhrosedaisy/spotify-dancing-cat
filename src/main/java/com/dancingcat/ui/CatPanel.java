package com.dancingcat.ui;

import com.dancingcat.beat.BeatSource;
import com.dancingcat.beat.Mood;
import com.dancingcat.lyrics.Lyrics;
import com.dancingcat.spotify.NowPlaying;
import com.dancingcat.spotify.PlaybackClock;
import com.dancingcat.ui.CatSprites.Eyes;
import com.dancingcat.ui.CatSprites.Pose;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Polygon;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Renders the cat, the control buttons, the song-title bubble and the lyrics into an image.
 * It knows nothing about windows: CatWindow calls {@link #tick()} every frame and, when it
 * returns true, {@link #render} to get a fresh picture (we only redraw when something changed).
 * All methods are meant to be called on the JavaFX application thread.
 *
 * Layout: normally the cat sits at the bottom with buttons/lyrics above it. When the cat is
 * dragged to the top of the screen ("flipped"), the cat moves up and buttons/lyrics are drawn
 * below it, so the cat can sit right under the menu bar.
 */
public final class CatPanel {

    private static final long REACTION_MS = 700;
    private static final int REACTION_STEPS = 14;
    private static final long PARTICLE_STEP_MS = 125;  // Zs and notes move at ~8 FPS for a pixel-art feel
    private static final long CONTROLS_MS = 5000;      // buttons hide 5 s after the last interaction
    private static final long TOAST_MS = 3500;
    private static final long TOAST_FADE_MS = 500;
    private static final int TOAST_FADE_STEPS = 6;
    private static final int LYRIC_ANIM_STEPS = 8;
    private static final long LYRIC_ANIM_STEP_MS = 35;

    private static final long DROP_MS = 1400;
    private static final int DROP_STEPS = 16;
    private static final long LIKE_MS = 1100;
    private static final int LIKE_STEPS = 14;
    private static final int THEME_STEPS = 10;
    private static final long THEME_STEP_MS = 60;

    private final BeatSource beat;
    private final int scale;
    private final int catPx;
    private final int buttonPx;
    private final int overlayPx;

    private volatile CatState state = CatState.SLEEPING;
    private volatile long reactionStart = -1;
    private volatile long controlsUntil = 0;
    private volatile Control hover;
    private volatile String toastText;
    private volatile long toastStart;
    private volatile boolean flipped;
    private volatile boolean lyricsEnabled;
    private volatile Lyrics lyrics;
    private volatile long lyricOffsetMs = 400; // positive = show lyrics earlier
    private volatile Costume costume = Costume.NONE;
    private volatile Mood mood = Mood.GROOVE;
    private volatile boolean liked;
    private volatile long dropStart = -1;
    private volatile long likeStart = -1;
    private volatile Theme themeFrom = Theme.DEFAULT;
    private volatile Theme themeTo = Theme.DEFAULT;
    private volatile long themeChangeStart;
    private Theme paintTheme = Theme.DEFAULT; // theme used while painting (in-between during a transition)
    private final PlaybackClock clock = new PlaybackClock();

    private final int width;
    private final int height;
    private Scene scene;

    /** Extra motion for a dance frame (offset, rotation, mirroring). */
    private record Fx(int dx, int dy, int rotStep, boolean mirror) {
        static final Fx NONE = new Fx(0, 0, 0, false);
    }

    /**
     * The current lyrics state. When the line changes, step runs from 0 to LYRIC_ANIM_STEPS;
     * meanwhile the previous line slides up and fades out while the new one slides in from below.
     */
    private record LyricFrame(int index, String previous, String current, String next, int step) {}

    /** Describes what is on screen; if it didn't change, there is no need to repaint. */
    private record Scene(String kind, Pose pose, Fx fx, int breath, int tick,
                         boolean controls, Control hover, boolean playing, boolean lyricsOn,
                         String toast, int toastFade, LyricFrame lyric, boolean flipped,
                         Costume costume, Mood mood, boolean liked, Theme theme, int themeStep) {}

    /** Regions inside the panel. */
    private record Layout(Rectangle cat, int buttonsY, int toastY, int lyricsAnchorY,
                          boolean lyricsGrowUp, int lyricsMaxH) {}

    public CatPanel(BeatSource beat, int scale) {
        this.beat = beat;
        this.scale = scale;
        this.catPx = CatSprites.SIZE * scale;
        this.buttonPx = 10 * scale;
        this.overlayPx = 44 * scale;
        this.width = Math.max(320, catPx + 200);
        this.height = catPx + overlayPx;
        scene = sceneAt(System.currentTimeMillis());
    }

    /** Size of the rendered picture in logical (point) pixels. */
    public int width() { return width; }

    public int height() { return height; }

    // ---------------------------------------------------------------- state

    public CatState state() { return state; }

    public void setState(CatState state) { this.state = state; }

    public void react() { reactionStart = System.currentTimeMillis(); }

    public boolean controlsVisible() { return System.currentTimeMillis() < controlsUntil; }

    public void showControls() { controlsUntil = System.currentTimeMillis() + CONTROLS_MS; }

    public void hideControls() { controlsUntil = 0; hover = null; }

    public void setHover(Control control) {
        hover = control;
        if (control != null) showControls();
    }

    public void showToast(String text) {
        toastText = text;
        toastStart = System.currentTimeMillis();
    }

    public void setLyricsEnabled(boolean enabled) { lyricsEnabled = enabled; }

    public void setLyrics(Lyrics lyrics) { this.lyrics = lyrics; }

    public void setNowPlaying(NowPlaying nowPlaying) { clock.update(nowPlaying, System.currentTimeMillis()); }

    public void setLyricOffsetMs(long offsetMs) { lyricOffsetMs = offsetMs; }

    public void setCostume(Costume costume) { this.costume = costume; }

    public void setMood(Mood mood) { this.mood = mood; }

    public void setLiked(boolean liked) { this.liked = liked; }

    /** A drop was detected: giant jump + flip + stars. */
    public void triggerDrop() { dropStart = System.currentTimeMillis(); }

    /** The song was liked: a burst of hearts. */
    public void triggerLike() { likeStart = System.currentTimeMillis(); }

    /** Starts a smooth transition to a new (album-color) theme. */
    public void setTheme(Theme theme) {
        long now = System.currentTimeMillis();
        themeFrom = currentTheme(now);
        themeTo = theme;
        themeChangeStart = now;
    }

    private int themeStep(long now) {
        return (int) Math.min(THEME_STEPS, (now - themeChangeStart) / THEME_STEP_MS);
    }

    private Theme currentTheme(long now) {
        return themeFrom.lerp(themeTo, themeStep(now) / (double) THEME_STEPS);
    }

    /** True when the cat is near the top: buttons/lyrics are drawn below it. */
    public void setFlipped(boolean flipped) {
        this.flipped = flipped;
    }

    /** Height of the area reserved for buttons/lyrics. */
    public int overlayHeight() { return overlayPx; }

    public int catPixelSize() { return catPx; }

    public Rectangle catBounds() { return currentLayout().cat(); }

    public Optional<Control> controlAt(Point p) {
        if (!controlsVisible()) return Optional.empty();
        return buttonBounds(currentLayout()).entrySet().stream()
                .filter(e -> e.getValue().contains(p))
                .map(Map.Entry::getKey)
                .findFirst();
    }

    // ---------------------------------------------------------------- layout

    private Layout currentLayout() {
        int w = width;
        int h = height;
        int catX = (w - catPx) / 2;
        int toastH = 7 * scale;
        if (!flipped) {
            int catY = h - catPx;
            int buttonsY = catY + 7 * scale - buttonPx;   // right above the head
            int toastY = 2;
            int anchor = buttonsY - 2 * scale;            // lyrics grow upwards
            return new Layout(new Rectangle(catX, catY, catPx, catPx), buttonsY, toastY,
                    anchor, true, anchor - (toastY + toastH + 2 * scale));
        }
        int buttonsY = catPx + scale;                     // right below the cat
        int toastY = h - toastH - 2;
        int anchor = buttonsY + buttonPx + 2 * scale;     // lyrics grow downwards
        return new Layout(new Rectangle(catX, 0, catPx, catPx), buttonsY, toastY,
                anchor, false, toastY - 2 * scale - anchor);
    }

    private Map<Control, Rectangle> buttonBounds(Layout l) {
        int gap = 2 * scale;
        int count = Control.values().length;
        int total = count * buttonPx + (count - 1) * gap;
        int x = (l.cat().x + catPx / 2) - total / 2;
        Map<Control, Rectangle> map = new EnumMap<>(Control.class);
        for (Control c : Control.values()) {
            map.put(c, new Rectangle(x, l.buttonsY(), buttonPx, buttonPx));
            x += buttonPx + gap;
        }
        return map;
    }

    // ---------------------------------------------------------------- scene

    /** Advances the animation. Returns true if the picture changed and needs to be rendered. */
    public boolean tick() {
        Scene next = sceneAt(System.currentTimeMillis());
        if (next.equals(scene)) return false;
        scene = next;
        return true;
    }

    private Scene sceneAt(long now) {
        boolean controls = now < controlsUntil;
        boolean playing = state == CatState.DANCING;

        String toast = null;
        int toastFade = 0;
        if (toastText != null) {
            long age = now - toastStart;
            if (age < TOAST_MS) {
                toast = toastText;
                long fadeStart = TOAST_MS - TOAST_FADE_MS;
                toastFade = age < fadeStart ? 0 : (int) ((age - fadeStart) * TOAST_FADE_STEPS / TOAST_FADE_MS);
            } else {
                toastText = null;
            }
        }
        LyricFrame lyric = playing ? lyricAt(now) : null;
        int particleTick = (int) (now / PARTICLE_STEP_MS);
        Costume c = costume;
        Mood m = mood;
        boolean lk = liked;
        Theme theme = themeTo;
        int ts = themeStep(now);

        // Priority: drop > like > click reaction > dance > idle action > sleep
        if (dropStart >= 0) {
            int step = (int) ((now - dropStart) * DROP_STEPS / DROP_MS);
            if (step <= DROP_STEPS) {
                Choreography.Frame f = Choreography.drop(step / (double) DROP_STEPS);
                return new Scene("drop", f.pose(), new Fx(f.dx(), f.dy(), f.rotStep(), f.mirror()), 0, step,
                        controls, hover, playing, lyricsEnabled, toast, toastFade, lyric, flipped, c, m, lk, theme, ts);
            }
            dropStart = -1;
        }
        if (likeStart >= 0) {
            int step = (int) ((now - likeStart) * LIKE_STEPS / LIKE_MS);
            if (step <= LIKE_STEPS) {
                int jump = (int) Math.round(Math.sin(Math.PI * Math.min(1, step / 8.0)) * 5);
                return new Scene("like", new Pose(jump, 0, true, true, 0, Eyes.CLOSED), Fx.NONE, 0, step,
                        controls, hover, playing, lyricsEnabled, toast, toastFade, lyric, flipped, c, m, lk, theme, ts);
            }
            likeStart = -1;
        }
        if (reactionStart >= 0) {
            int step = (int) ((now - reactionStart) * REACTION_STEPS / REACTION_MS);
            if (step <= REACTION_STEPS) {
                int jump = (int) Math.round(Math.sin(Math.PI * step / REACTION_STEPS) * 4);
                return new Scene("react", new Pose(jump, 0, true, true, 0, Eyes.CLOSED), Fx.NONE, 0, step,
                        controls, hover, playing, lyricsEnabled, toast, toastFade, lyric, flipped, c, m, lk, theme, ts);
            }
            reactionStart = -1;
        }
        if (playing) {
            Choreography.Frame f = Choreography.at(beat.beatPosition(now), m);
            return new Scene("dance", f.pose(), new Fx(f.dx(), f.dy(), f.rotStep(), f.mirror()), 0, particleTick,
                    controls, hover, true, lyricsEnabled, toast, toastFade, lyric, flipped, c, m, lk, theme, ts);
        }
        Pose idle = IdleBehavior.at(now);
        if (idle != null) {
            return new Scene("idle", idle, Fx.NONE, 0, 0,
                    controls, hover, false, lyricsEnabled, toast, toastFade, null, flipped, c, m, lk, theme, ts);
        }
        return new Scene("sleep", null, Fx.NONE, (int) ((now / 1200) % 2), particleTick,
                controls, hover, false, lyricsEnabled, toast, toastFade, null, flipped, c, m, lk, theme, ts);
    }

    private LyricFrame lyricAt(long now) {
        Lyrics l = lyrics;
        if (!lyricsEnabled || l == null) return null;
        if (l.instrumental()) return new LyricFrame(0, null, "♪ instrumental ♪", null, LYRIC_ANIM_STEPS);

        long ms = clock.positionAt(now) + lyricOffsetMs;
        int i = l.indexAt(ms);
        List<Lyrics.Line> lines = l.lines();
        if (i < 0) { // before the first line: show the intro and preview the first line
            return new LyricFrame(-1, null, "♪ ♪ ♪", lineText(lines, 0), LYRIC_ANIM_STEPS);
        }
        String previous = i > 0 ? displayText(lines.get(i - 1).text()) : "♪ ♪ ♪";
        String current = displayText(lines.get(i).text());
        String next = lineText(lines, i + 1);
        int step = (int) Math.min(LYRIC_ANIM_STEPS, (ms - lines.get(i).timeMs()) / LYRIC_ANIM_STEP_MS);
        return new LyricFrame(i, step < LYRIC_ANIM_STEPS ? previous : null, current, next, step);
    }

    private static String displayText(String text) {
        return text.isBlank() ? "♪ ♪ ♪" : text;
    }

    private static String lineText(List<Lyrics.Line> lines, int i) {
        return i < lines.size() && !lines.get(i).text().isBlank() ? lines.get(i).text() : null;
    }

    // ---------------------------------------------------------------- painting

    /**
     * Renders the current scene into target, whose size must be (width × pixelScale,
     * height × pixelScale). pixelScale is 2 on Retina screens, so text stays crisp.
     */
    public void render(BufferedImage target, double pixelScale) {
        Scene s = scene;
        Graphics2D g2 = target.createGraphics();
        g2.setComposite(AlphaComposite.Clear);
        g2.fillRect(0, 0, target.getWidth(), target.getHeight());
        g2.setComposite(AlphaComposite.SrcOver);
        g2.scale(pixelScale, pixelScale);
        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);

        Layout l = currentLayout();
        Rectangle cat = l.cat();
        Fx fx = s.fx();
        paintTheme = themeFrom.lerp(s.theme(), s.themeStep() / (double) THEME_STEPS);

        // Shadow (shrinks while jumping)
        double shrink = 1 - Math.min(0.5, Math.abs(fx.dy()) / 16.0);
        int sw = (int) (20 * scale * shrink);
        g2.setColor(new Color(0, 0, 0, 50));
        g2.fillOval(cat.x + catPx / 2 - sw / 2 + fx.dx() * scale, cat.y + 29 * scale, sw, 3 * scale);

        if (s.kind().equals("dance")) drawNotes(g2, cat, s.tick(), s.mood());
        if (s.kind().equals("drop")) drawStars(g2, cat, s.tick() / (double) DROP_STEPS);

        BufferedImage frame = s.kind().equals("sleep")
                ? CatSprites.sleeping(s.breath(), s.costume())
                : CatSprites.sitting(s.pose(), s.costume());
        drawCat(g2, frame, cat, fx);

        if (s.kind().equals("like")) drawHeartBurst(g2, cat, s.tick() / (double) LIKE_STEPS);
        if (s.controls()) {
            drawControls(g2, l, s);
        } else if (s.kind().equals("react")) {
            drawHeart(g2, cat, s.tick() / (double) REACTION_STEPS);
        } else if (s.kind().equals("sleep")) {
            drawZzz(g2, cat, s.tick());
        }
        if (s.lyric() != null) drawLyrics(g2, l, s.lyric());
        if (s.toast() != null) drawToast(g2, l, s.toast(), 1f - s.toastFade() / (float) TOAST_FADE_STEPS);
        g2.dispose();
    }

    private void drawCat(Graphics2D g2, BufferedImage frame, Rectangle cat, Fx fx) {
        int x = cat.x + fx.dx() * scale;
        int y = cat.y + fx.dy() * scale;
        AffineTransform old = g2.getTransform();
        if (fx.rotStep() != 0) {
            // rotate around the middle of the body
            g2.rotate(Math.toRadians(fx.rotStep() * 30), x + catPx / 2.0, y + 20 * scale);
        }
        if (fx.mirror()) g2.drawImage(frame, x + catPx, y, -catPx, catPx, null);
        else g2.drawImage(frame, x, y, catPx, catPx, null);
        g2.setTransform(old);
    }

    private void drawControls(Graphics2D g2, Layout l, Scene s) {
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        int iconScale = Math.max(1, (buttonPx - 4 * scale) / 7);
        int iconPx = 7 * iconScale;
        for (Map.Entry<Control, Rectangle> e : buttonBounds(l).entrySet()) {
            Rectangle r = e.getValue();
            boolean active = (e.getKey() == Control.LYRICS && s.lyricsOn()) || (e.getKey() == Control.LIKE && s.liked());
            Color bg = e.getKey() == s.hover() ? paintTheme.buttonHover()
                    : active ? paintTheme.accent() : paintTheme.buttonBg();
            g2.setColor(bg);
            g2.fillRoundRect(r.x, r.y, r.width, r.height, 3 * scale, 3 * scale);
            BufferedImage icon = CatSprites.icon(e.getKey(), s.playing());
            g2.drawImage(icon, r.x + (r.width - iconPx) / 2, r.y + (r.height - iconPx) / 2, iconPx, iconPx, null);
        }
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
    }

    private void drawLyrics(Graphics2D g2, Layout l, LyricFrame lf) {
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        Font curFont = new Font(Font.SANS_SERIF, Font.BOLD, (int) Math.round(4.6 * scale));
        Font nextFont = new Font(Font.SANS_SERIF, Font.PLAIN, (int) Math.round(3.5 * scale));
        FontMetrics fmCur = g2.getFontMetrics(curFont);
        FontMetrics fmNext = g2.getFontMetrics(nextFont);
        int pad = 2 * scale;
        int maxTextW = width - 2 * pad - 6;

        // During a transition: old block (previous line + its preview) -> new block. The bubble resizes smoothly.
        double a = smooth(lf.step() / (double) LYRIC_ANIM_STEPS);
        LyricBlock cur = layoutBlock(lf.current(), lf.next(), fmCur, fmNext, maxTextW, pad, l.lyricsMaxH());
        LyricBlock prev = lf.previous() == null ? null
                : layoutBlock(lf.previous(), lf.current(), fmCur, fmNext, maxTextW, pad, l.lyricsMaxH());
        int w = prev == null ? cur.w() : (int) Math.round(prev.w() + (cur.w() - prev.w()) * a);
        int h = prev == null ? cur.h() : (int) Math.round(prev.h() + (cur.h() - prev.h()) * a);
        int x = (width - w) / 2;
        int y = l.lyricsGrowUp() ? l.lyricsAnchorY() - h : l.lyricsAnchorY();

        // Bubble + a small tail pointing at the cat
        g2.setColor(paintTheme.bubble());
        g2.fillRoundRect(x, y, w, h, 4 * scale, 4 * scale);
        int cx = width / 2, t = 2 * scale;
        Polygon tail = l.lyricsGrowUp()
                ? new Polygon(new int[]{cx - t, cx + t, cx}, new int[]{y + h, y + h, y + h + t}, 3)
                : new Polygon(new int[]{cx - t, cx + t, cx}, new int[]{y, y, y - t}, 3);
        g2.fill(tail);

        // The old line slides up and fades out while the new one slides in from below (never empty).
        // (the old line is gone after 60% of the transition, the new one appears after 30% -> little overlap)
        int travel = 9 * scale;
        Shape oldClip = g2.getClip();
        g2.clipRect(x, y, w, h);
        if (prev != null) {
            float prevAlpha = (float) Math.max(0, 1 - a / 0.6);
            drawBlock(g2, prev, x, y - (int) Math.round(travel * a), w, pad, curFont, nextFont, fmCur, fmNext, prevAlpha);
        }
        float curAlpha = prev == null ? 1f : (float) Math.max(0, (a - 0.3) / 0.7);
        drawBlock(g2, cur, x, y + (prev == null ? 0 : (int) Math.round(travel * (1 - a))), w, pad,
                curFont, nextFont, fmCur, fmNext, curAlpha);
        g2.setComposite(AlphaComposite.SrcOver);
        g2.setClip(oldClip);
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
    }

    /** A measured lyric block: main line(s) + a dimmed preview line. */
    private record LyricBlock(List<String> lines, String next, int w, int h) {}

    private LyricBlock layoutBlock(String text, String nextText, FontMetrics fmCur, FontMetrics fmNext,
                                   int maxTextW, int pad, int maxH) {
        List<String> lines = wrap(text, fmCur, maxTextW, 2);
        String next = nextText == null ? null : ellipsize(nextText, fmNext, maxTextW);
        int curH = lines.size() * fmCur.getHeight();
        int h = pad + curH + (next != null ? fmNext.getHeight() + scale : 0) + pad;
        if (h > maxH && next != null) { // drop the preview if it doesn't fit
            next = null;
            h = pad + curH + pad;
        }
        int textW = 0;
        for (String s : lines) textW = Math.max(textW, fmCur.stringWidth(s));
        if (next != null) textW = Math.max(textW, fmNext.stringWidth(next));
        return new LyricBlock(lines, next, textW + 2 * pad, h);
    }

    private void drawBlock(Graphics2D g2, LyricBlock b, int x, int y, int w, int pad, Font curFont, Font nextFont,
                           FontMetrics fmCur, FontMetrics fmNext, float alpha) {
        if (alpha <= 0.01f) return;
        g2.setComposite(AlphaComposite.SrcOver.derive(Math.min(1f, alpha)));
        g2.setFont(curFont);
        g2.setColor(Color.WHITE);
        int ty = y + pad + fmCur.getAscent();
        for (String s : b.lines()) {
            g2.drawString(s, x + (w - fmCur.stringWidth(s)) / 2, ty);
            ty += fmCur.getHeight();
        }
        if (b.next() != null) {
            g2.setComposite(AlphaComposite.SrcOver.derive(Math.min(1f, 0.75f * alpha)));
            g2.setFont(nextFont);
            g2.setColor(paintTheme.nextLine());
            int baseline = y + pad + b.lines().size() * fmCur.getHeight() + scale + fmNext.getAscent();
            g2.drawString(b.next(), x + (w - fmNext.stringWidth(b.next())) / 2, baseline);
        }
    }

    /** Ease-in-out curve (0..1). */
    private static double smooth(double t) {
        t = Math.max(0, Math.min(1, t));
        return t * t * (3 - 2 * t);
    }

    private void drawToast(Graphics2D g2, Layout l, String text, float alpha) {
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g2.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 4 * scale));
        FontMetrics fm = g2.getFontMetrics();
        int pad = 2 * scale;
        String shown = ellipsize(text, fm, width - 4 * pad);
        int w = fm.stringWidth(shown) + 2 * pad;
        int h = fm.getHeight() + pad;
        int x = (width - w) / 2;
        int y = l.toastY();
        g2.setComposite(AlphaComposite.SrcOver.derive(Math.max(0f, alpha)));
        g2.setColor(paintTheme.bubble());
        g2.fillRoundRect(x, y, w, h, 3 * scale, 3 * scale);
        g2.setColor(Color.WHITE);
        g2.drawString(shown, x + pad, y + pad / 2 + fm.getAscent());
        g2.setComposite(AlphaComposite.SrcOver);
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
    }

    private void drawNotes(Graphics2D g2, Rectangle cat, int tick, Mood mood) {
        double cycle = (mood == Mood.HYPE ? 1400.0 : 2000.0) / PARTICLE_STEP_MS; // how long one note takes to rise
        int ns = Math.max(1, scale - 1);
        int count = switch (mood) { case CHILL -> 1; case GROOVE -> 2; case HYPE -> 3; };
        for (int i = 0; i < count; i++) {
            double t = ((tick / cycle) + i / (double) count) % 1.0;
            int round = (int) Math.floor(tick / cycle + i / (double) count);
            boolean left = ((round + i) & 1) == 0;
            int baseX = left ? cat.x + 2 * scale : cat.x + catPx - 8 * scale;
            int x = baseX + (int) (Math.sin(t * Math.PI * 2) * 2 * scale);
            int y = cat.y + 16 * scale - (int) (t * 14 * scale);
            float alpha = (float) Math.sin(Math.PI * t);
            Color[] colors = paintTheme.notes();
            BufferedImage note = CatSprites.note(colors[Math.floorMod(round + i, colors.length)].getRGB() | 0xFF000000);
            g2.setComposite(AlphaComposite.SrcOver.derive(alpha));
            g2.drawImage(CatSprites.note(0xFF3A3346), x + 1, y + 1, 6 * ns, 7 * ns, null); // shadow
            g2.drawImage(note, x, y, 6 * ns, 7 * ns, null);
        }
        g2.setComposite(AlphaComposite.SrcOver);
    }

    /** Drop: stars bursting out from the cat. t = 0..1 */
    private void drawStars(Graphics2D g2, Rectangle cat, double t) {
        int cx = cat.x + catPx / 2, cy = cat.y + 18 * scale;
        int s = Math.max(1, scale - 1);
        double dist = (6 + t * 22) * scale;
        Color[] colors = paintTheme.notes();
        g2.setComposite(AlphaComposite.SrcOver.derive((float) Math.max(0, 1 - t * t)));
        for (int i = 0; i < 10; i++) {
            double a = i * Math.PI * 2 / 10 + t * 0.8;
            int x = cx + (int) (Math.cos(a) * dist) - 5 * s / 2;
            int y = cy + (int) (Math.sin(a) * dist * 0.8) - 5 * s / 2;
            BufferedImage star = CatSprites.star(colors[i % colors.length].getRGB() | 0xFF000000);
            g2.drawImage(star, x, y, 5 * s, 5 * s, null);
        }
        g2.setComposite(AlphaComposite.SrcOver);
    }

    /** Like: hearts rising around the cat. t = 0..1 */
    private void drawHeartBurst(Graphics2D g2, Rectangle cat, double t) {
        BufferedImage heart = CatSprites.heart();
        int s = Math.max(1, scale - 1);
        int[] xs = {-15, -9, 9, 15, 0}; // rise from the sides so they don't cover the face
        g2.setComposite(AlphaComposite.SrcOver.derive((float) Math.max(0, 1 - t * t)));
        for (int i = 0; i < xs.length; i++) {
            double ti = Math.max(0, t - i * 0.06);
            int x = cat.x + catPx / 2 + xs[i] * scale + (int) (Math.sin(ti * 8 + i) * scale) - heart.getWidth() * s / 2;
            int startY = xs[i] == 0 ? 2 : 14 - (i % 2) * 3; // the middle heart starts above the head
            int y = cat.y + startY * scale - (int) (ti * 14 * scale);
            g2.drawImage(heart, x, y, heart.getWidth() * s, heart.getHeight() * s, null);
        }
        g2.setComposite(AlphaComposite.SrcOver);
    }

    private void drawZzz(Graphics2D g2, Rectangle cat, int tick) {
        double cycle = 3000.0 / PARTICLE_STEP_MS; // one Z takes 3 s to rise
        for (int i = 0; i < 3; i++) {
            double t = ((tick / cycle) + i / 3.0) % 1.0;
            int x = cat.x + 15 * scale + (int) (t * 6 * scale) + i * 2;
            int y = cat.y + 13 * scale - (int) (t * 14 * scale);
            int alpha = (int) (255 * Math.sin(Math.PI * t));
            g2.setFont(new Font(Font.MONOSPACED, Font.BOLD, (int) (scale * (3 + t * 2.5))));
            g2.setColor(new Color(0x3A, 0x33, 0x46, alpha));
            g2.drawString("Z", x + 1, y + 1);
            g2.setColor(new Color(0xFF, 0xFF, 0xFF, alpha));
            g2.drawString("Z", x, y);
        }
    }

    private void drawHeart(Graphics2D g2, Rectangle cat, double t) {
        BufferedImage heart = CatSprites.heart();
        int s = Math.max(1, scale - 1);
        int x = cat.x + (catPx - heart.getWidth() * s) / 2;
        int y = cat.y + 3 * scale - (int) (t * 8 * scale);
        g2.setComposite(AlphaComposite.SrcOver.derive((float) Math.max(0, 1 - t * t)));
        g2.drawImage(heart, x, y, heart.getWidth() * s, heart.getHeight() * s, null);
        g2.setComposite(AlphaComposite.SrcOver);
    }

    // ---------------------------------------------------------------- text helpers

    /** Wraps text into at most maxLines lines, cutting the rest with "…". */
    private static List<String> wrap(String text, FontMetrics fm, int maxWidth, int maxLines) {
        List<String> out = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        String[] words = text.split("\\s+");
        for (int i = 0; i < words.length; i++) {
            String candidate = line.isEmpty() ? words[i] : line + " " + words[i];
            if (fm.stringWidth(candidate) <= maxWidth || line.isEmpty()) {
                line = new StringBuilder(candidate);
            } else {
                out.add(line.toString());
                if (out.size() == maxLines - 1) { // last line: everything that is left
                    StringBuilder rest = new StringBuilder(words[i]);
                    for (int j = i + 1; j < words.length; j++) rest.append(' ').append(words[j]);
                    out.add(ellipsize(rest.toString(), fm, maxWidth));
                    return out;
                }
                line = new StringBuilder(words[i]);
            }
        }
        if (!line.isEmpty()) out.add(ellipsize(line.toString(), fm, maxWidth));
        return out;
    }

    private static String ellipsize(String text, FontMetrics fm, int maxWidth) {
        if (fm.stringWidth(text) <= maxWidth) return text;
        String s = text;
        while (!s.isEmpty() && fm.stringWidth(s + "…") > maxWidth) s = s.substring(0, s.length() - 1);
        return s + "…";
    }
}
