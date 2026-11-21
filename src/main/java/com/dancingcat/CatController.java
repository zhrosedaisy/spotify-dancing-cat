package com.dancingcat;

import com.dancingcat.beat.AudioCapture;
import com.dancingcat.beat.BeatTracker;
import com.dancingcat.beat.FixedTempoBeatSource;
import com.dancingcat.config.AppConfig;
import com.dancingcat.lyrics.LyricsService;
import com.dancingcat.spotify.AlbumArt;
import com.dancingcat.spotify.NowPlaying;
import com.dancingcat.spotify.SpotifyService;
import com.dancingcat.ui.CatPanel;
import com.dancingcat.ui.CatState;
import com.dancingcat.ui.Control;
import com.dancingcat.ui.Costume;
import com.dancingcat.ui.Theme;

import javax.swing.SwingUtilities;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.prefs.Preferences;

/**
 * Uygulamanın beyni: Spotify'dan gelen bilgiyi kediye, kediye yapılan tıklamaları Spotify'a iletir.
 * Şarkı sözlerini de buradan yönetiriz.
 */
public final class CatController {

    private static final String PREF_LYRICS = "lyricsEnabled";
    private static final String PREF_OFFSET = "lyricOffsetMs";
    private static final long DEFAULT_OFFSET_MS = 400;
    private static final String PREF_COSTUME = "costume";

    private final CatPanel panel;
    private final BeatTracker beat;
    private final SpotifyService spotify; // Client ID yoksa null
    private final LyricsService lyrics = new LyricsService();
    private final AlbumArt albumArt = new AlbumArt();
    private final Preferences prefs;

    private volatile NowPlaying current;
    private volatile boolean lyricsEnabled;
    private volatile boolean liked;
    private String lastTrackKey; // sadece Spotify iş parçacığından erişilir

    public CatController(AppConfig config, Preferences prefs) {
        this.prefs = prefs;
        // Canlı ritim; ses yoksa config'teki sabit tempoya düşer
        this.beat = new BeatTracker(new FixedTempoBeatSource(config.danceBpm()));
        this.panel = new CatPanel(beat, config.catScale());
        this.lyricsEnabled = prefs.getBoolean(PREF_LYRICS, false);
        panel.setLyricsEnabled(lyricsEnabled);
        panel.setLyricOffsetMs(lyricOffsetMs());
        panel.setCostume(costume());
        // Drop algılanınca (ses iş parçacığından gelir) kediye büyük hareketi yaptır
        beat.setOnDrop(() -> SwingUtilities.invokeLater(() -> {
            if (panel.state() == CatState.DANCING) panel.triggerDrop();
        }));
        this.spotify = config.hasSpotifyClientId()
                ? new SpotifyService(config.spotifyClientId(), config.spotifyRedirectUri(), this::onPlayback)
                : null;
        if (spotify == null) {
            System.out.println("Spotify Client ID girilmemiş (config.properties). Kedi test modunda.");
        }
    }

    public CatPanel panel() { return panel; }

    public void start() {
        if (spotify != null) spotify.connectAsync(); // ilk seferde tarayıcıda giriş açılır
        new AudioCapture(Path.of("build/native/audiotap"), beat, status -> SwingUtilities.invokeLater(() -> {
            switch (status) {
                case LISTENING -> System.out.println("🎧 Spotify sesi dinleniyor, kedi gerçek ritme göre dans edecek");
                case PERMISSION_DENIED -> panel.showToast("Ritim için ses kaydı izni gerekiyor");
                case UNAVAILABLE -> { }
            }
        })).start();
    }

    /** Menüde gösterilecek ritim bilgisi. */
    public String tempoLabel() {
        double bpm = beat.bpm();
        return bpm > 0
                ? String.format("Ritim: %.0f BPM · %s", bpm, beat.mood().label())
                : "Ritim: sabit tempo";
    }

    // ---------------------------------------------------------------- kostüm

    public Costume costume() {
        try {
            return Costume.valueOf(prefs.get(PREF_COSTUME, Costume.NONE.name()));
        } catch (IllegalArgumentException e) {
            return Costume.NONE;
        }
    }

    public void setCostume(Costume costume) {
        prefs.put(PREF_COSTUME, costume.name());
        panel.setCostume(costume);
        panel.react();
    }

    // ---------------------------------------------------------------- beğenme

    /** ❤ butonu: beğenilmemişse beğen, beğenilmişse geri al. */
    public void toggleLike() {
        setLiked(!liked);
    }

    /** Çift tıklama: sadece beğenir (yanlışlıkla beğeni silinmesin). */
    public void likeByDoubleClick() {
        if (liked) {
            panel.triggerLike();
            panel.showToast("Zaten Beğenilen Şarkılar'da \u2665");
        } else {
            setLiked(true);
        }
    }

    private void setLiked(boolean save) {
        NowPlaying np = current;
        if (spotify == null || np == null || np.trackId() == null) {
            panel.showToast("Beğenilecek şarkı yok");
            return;
        }
        if (save) panel.triggerLike(); // animasyon hemen başlasın, cevap beklemesin
        spotify.setSaved(np.trackId(), save).whenComplete((ok, err) -> SwingUtilities.invokeLater(() -> {
            if (err != null) {
                System.err.println("Beğenme başarısız: " + err.getMessage());
                panel.showToast(SpotifyService.friendlyError(err));
                return;
            }
            liked = save;
            panel.setLiked(save);
            panel.showToast(save ? "\u2665 Beğenilen Şarkılar'a eklendi" : "Beğenilerden çıkarıldı");
        }));
    }

    public boolean hasSpotify() { return spotify != null; }

    public boolean isSpotifyConnected() { return spotify != null && spotify.isConnected(); }

    public void connectSpotify() { if (spotify != null) spotify.connectAsync(); }

    public boolean lyricsEnabled() { return lyricsEnabled; }

    public void setLyricsEnabled(boolean enabled) {
        lyricsEnabled = enabled;
        prefs.putBoolean(PREF_LYRICS, enabled);
        panel.setLyricsEnabled(enabled);
        NowPlaying np = current;
        if (enabled && np != null) loadLyrics(np);
        panel.showToast(enabled ? "Sözler açık" : "Sözler kapalı");
    }

    /** Sözlerin ne kadar erken gösterileceği (ms). Geç kalıyorsa artır. */
    public long lyricOffsetMs() {
        return prefs.getLong(PREF_OFFSET, DEFAULT_OFFSET_MS);
    }

    /** Söz zamanlamasını delta ms kaydırır (pozitif = daha erken). */
    public void nudgeLyricOffset(long delta) {
        long offset = Math.max(-2000, Math.min(3000, lyricOffsetMs() + delta));
        prefs.putLong(PREF_OFFSET, offset);
        panel.setLyricOffsetMs(offset);
        panel.showToast(String.format("Söz zamanlaması: %+.1f sn", offset / 1000.0));
    }

    /** Kedinin üstündeki bir butona basıldı. */
    public void onControl(Control control) {
        if (control == Control.LYRICS) {
            setLyricsEnabled(!lyricsEnabled);
            return;
        }
        if (control == Control.LIKE) {
            toggleLike();
            return;
        }
        if (spotify == null) return;
        CompletableFuture<Void> result = switch (control) {
            case PREVIOUS -> spotify.previous();
            case NEXT -> spotify.next();
            case PLAY_PAUSE -> spotify.togglePlay();
            case LYRICS, LIKE -> CompletableFuture.completedFuture(null);
        };
        result.exceptionally(err -> {
            System.err.println("Komut başarısız: " + err.getMessage());
            SwingUtilities.invokeLater(() -> panel.showToast(SpotifyService.friendlyError(err)));
            return null;
        });
    }

    /** Spotify iş parçacığından saniyede bir çağrılır. */
    private void onPlayback(Optional<NowPlaying> now) {
        NowPlaying np = now.orElse(null);
        current = np;
        boolean playing = np != null && np.playing();
        String key = np == null ? null : np.key();
        boolean changed = key != null && !key.equals(lastTrackKey);
        lastTrackKey = key;

        if (changed && lyricsEnabled) loadLyrics(np);
        if (changed) onTrackChanged(np);
        SwingUtilities.invokeLater(() -> {
            panel.setMood(beat.mood());
            panel.setNowPlaying(np);
            panel.setState(playing ? CatState.DANCING : CatState.SLEEPING);
            if (changed && playing) panel.showToast("♪ " + np.label());
        });
    }

    /** Yeni şarkı: beğenilmiş mi diye sor, albüm kapağından renk temasını çıkar. */
    private void onTrackChanged(NowPlaying track) {
        liked = false;
        SwingUtilities.invokeLater(() -> panel.setLiked(false));
        if (track.trackId() != null) {
            spotify.isSaved(track.trackId()).whenComplete((saved, err) -> {
                if (err != null) {
                    System.err.println("Beğeni durumu okunamadı: " + err.getMessage());
                    return;
                }
                if (isCurrent(track)) {
                    liked = saved;
                    SwingUtilities.invokeLater(() -> panel.setLiked(saved));
                }
            });
        }
        albumArt.dominantColor(track.albumImageUrl()).thenAccept(color -> {
            if (isCurrent(track)) {
                Theme theme = color.map(Theme::fromColor).orElse(Theme.DEFAULT);
                SwingUtilities.invokeLater(() -> panel.setTheme(theme));
            }
        });
    }

    private boolean isCurrent(NowPlaying track) {
        NowPlaying np = current;
        return np != null && np.key().equals(track.key());
    }

    private void loadLyrics(NowPlaying track) {
        SwingUtilities.invokeLater(() -> panel.setLyrics(null));
        lyrics.fetch(track).whenComplete((result, err) -> SwingUtilities.invokeLater(() -> {
            NowPlaying np = current;
            if (np == null || !np.key().equals(track.key())) return; // bu arada şarkı değişti
            if (err != null) {
                System.err.println("Sözler yüklenemedi: " + err.getMessage());
                if (lyricsEnabled) panel.showToast("Sözler yüklenemedi");
                return;
            }
            panel.setLyrics(result.orElse(null));
            if (result.isEmpty() && lyricsEnabled) panel.showToast("Bu şarkının senkron sözü yok");
        }));
    }
}
