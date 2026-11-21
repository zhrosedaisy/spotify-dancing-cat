package com.dancingcat;

import com.dancingcat.beat.AudioCapture;
import com.dancingcat.beat.BeatTracker;
import com.dancingcat.beat.FixedTempoBeatSource;
import com.dancingcat.config.AppConfig;
import com.dancingcat.config.AppPaths;
import com.dancingcat.lyrics.LyricsService;
import com.dancingcat.spotify.AlbumArt;
import com.dancingcat.spotify.NowPlaying;
import com.dancingcat.spotify.SpotifyService;
import com.dancingcat.ui.CatPanel;
import com.dancingcat.ui.CatState;
import com.dancingcat.ui.Control;
import com.dancingcat.ui.Costume;
import com.dancingcat.ui.Theme;

import javafx.application.Platform;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.prefs.Preferences;

/**
 * The brain of the app: forwards what Spotify reports to the cat, and what the user does with
 * the cat back to Spotify. Also manages lyrics, likes, costumes and the album-color theme.
 */
public final class CatController {

    private static final String PREF_LYRICS = "lyricsEnabled";
    private static final String PREF_OFFSET = "lyricOffsetMs";
    private static final long DEFAULT_OFFSET_MS = 400;
    private static final String PREF_COSTUME = "costume";

    /**
     * Spotify's "currently playing" endpoint occasionally returns an empty response or
     * is_playing=false for a single poll even though music keeps playing. Reacting to those
     * glitches made the cat (and the lyrics) blink, so we only go to sleep after this many
     * consecutive "not playing" polls (~1 per second)...
     */
    private static final int MISSES_BEFORE_SLEEP = 3;
    /** ...unless the user just pressed play/pause themselves – then react immediately. */
    private static final long USER_ACTION_GRACE_MS = 4000;

    private final CatPanel panel;
    private final BeatTracker beat;
    private final SpotifyService spotify; // null when no Client ID is configured
    private final LyricsService lyrics = new LyricsService();
    private final AlbumArt albumArt = new AlbumArt();
    private final Preferences prefs;

    private volatile NowPlaying current;
    private volatile boolean lyricsEnabled;
    private volatile boolean liked;
    private volatile long userActionUntil;
    // Only accessed from the Spotify polling thread:
    private String lastTrackKey;
    private boolean wasPlaying;
    private int notPlayingStreak;

    /**
     * @param openBrowser opens a URL in the user's browser (used for the Spotify login)
     */
    public CatController(AppConfig config, Preferences prefs, Consumer<String> openBrowser) {
        this.prefs = prefs;
        // Live beat tracking; falls back to the fixed tempo from the config when there is no audio
        this.beat = new BeatTracker(new FixedTempoBeatSource(config.danceBpm()));
        this.panel = new CatPanel(beat, config.catScale());
        this.lyricsEnabled = prefs.getBoolean(PREF_LYRICS, false);
        panel.setLyricsEnabled(lyricsEnabled);
        panel.setLyricOffsetMs(lyricOffsetMs());
        panel.setCostume(costume());
        // When a drop is detected (called from the audio thread), do the big move
        beat.setOnDrop(() -> Platform.runLater(() -> {
            if (panel.state() == CatState.DANCING) panel.triggerDrop();
        }));
        this.spotify = config.hasSpotifyClientId()
                ? new SpotifyService(config.spotifyClientId(), config.spotifyRedirectUri(), openBrowser, this::onPlayback)
                : null;
        if (spotify == null) {
            System.out.println("No Spotify Client ID set (config.properties). The cat runs in test mode.");
        }
    }

    public CatPanel panel() { return panel; }

    public void start() {
        if (spotify != null) spotify.connectAsync(); // opens the browser login the first time
        new AudioCapture(AppPaths.audioTap(), beat, status -> Platform.runLater(() -> {
            switch (status) {
                case LISTENING -> System.out.println("🎧 Listening to Spotify's audio – the cat follows the real beat");
                case PERMISSION_DENIED -> panel.showToast("Audio recording permission needed for live beat");
                case UNAVAILABLE -> { }
            }
        })).start();
    }

    /** Beat info shown in the menu. */
    public String tempoLabel() {
        double bpm = beat.bpm();
        return bpm > 0
                ? String.format("Beat: %.0f BPM · %s", bpm, beat.mood().label())
                : "Beat: fixed tempo";
    }

    // ---------------------------------------------------------------- costumes

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

    // ---------------------------------------------------------------- likes

    /** ❤ button: like if not liked yet, otherwise remove the like. */
    public void toggleLike() {
        setLiked(!liked);
    }

    /** Double-click: only ever likes (so a like can't be removed by accident). */
    public void likeByDoubleClick() {
        if (liked) {
            panel.triggerLike();
            panel.showToast("Already in Liked Songs ♥");
        } else {
            setLiked(true);
        }
    }

    private void setLiked(boolean save) {
        NowPlaying np = current;
        if (spotify == null || np == null || np.trackId() == null) {
            panel.showToast("No song to like");
            return;
        }
        if (save) panel.triggerLike(); // start the animation right away, don't wait for Spotify
        spotify.setSaved(np.trackId(), save).whenComplete((ok, err) -> Platform.runLater(() -> {
            if (err != null) {
                System.err.println("Like failed: " + err.getMessage());
                panel.showToast(SpotifyService.friendlyError(err));
                return;
            }
            liked = save;
            panel.setLiked(save);
            panel.showToast(save ? "♥ Added to Liked Songs" : "Removed from Liked Songs");
        }));
    }

    public boolean hasSpotify() { return spotify != null; }

    public boolean isSpotifyConnected() { return spotify != null && spotify.isConnected(); }

    public void connectSpotify() { if (spotify != null) spotify.connectAsync(); }

    // ---------------------------------------------------------------- lyrics

    public boolean lyricsEnabled() { return lyricsEnabled; }

    public void setLyricsEnabled(boolean enabled) {
        lyricsEnabled = enabled;
        prefs.putBoolean(PREF_LYRICS, enabled);
        panel.setLyricsEnabled(enabled);
        NowPlaying np = current;
        if (enabled && np != null) loadLyrics(np);
        panel.showToast(enabled ? "Lyrics on" : "Lyrics off");
    }

    /** How much earlier lyrics are shown (ms). Increase it if lyrics lag behind. */
    public long lyricOffsetMs() {
        return prefs.getLong(PREF_OFFSET, DEFAULT_OFFSET_MS);
    }

    /** Shifts the lyric timing by delta ms (positive = earlier). */
    public void nudgeLyricOffset(long delta) {
        long offset = Math.max(-2000, Math.min(3000, lyricOffsetMs() + delta));
        prefs.putLong(PREF_OFFSET, offset);
        panel.setLyricOffsetMs(offset);
        panel.showToast(String.format(java.util.Locale.ROOT, "Lyric timing: %+.1f s", offset / 1000.0));
    }

    // ---------------------------------------------------------------- controls

    /** A button above the cat was pressed. */
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
        if (control == Control.PLAY_PAUSE) userActionUntil = System.currentTimeMillis() + USER_ACTION_GRACE_MS;
        CompletableFuture<Void> result = switch (control) {
            case PREVIOUS -> spotify.previous();
            case NEXT -> spotify.next();
            case PLAY_PAUSE -> spotify.togglePlay();
            case LYRICS, LIKE -> CompletableFuture.completedFuture(null);
        };
        result.exceptionally(err -> {
            System.err.println("Command failed: " + err.getMessage());
            Platform.runLater(() -> panel.showToast(SpotifyService.friendlyError(err)));
            return null;
        });
    }

    // ---------------------------------------------------------------- playback updates

    /** Called about once per second from the Spotify polling thread. */
    private void onPlayback(Optional<NowPlaying> now) {
        NowPlaying np = now.orElse(null);
        boolean playing = np != null && np.playing();

        // Ignore one-off "nothing playing" glitches while music is playing (see MISSES_BEFORE_SLEEP)
        if (!playing && wasPlaying && System.currentTimeMillis() > userActionUntil
                && ++notPlayingStreak < MISSES_BEFORE_SLEEP) {
            return;
        }
        notPlayingStreak = 0;
        wasPlaying = playing;
        if (np != null) current = np;

        // An empty response doesn't mean the song changed: keep the last key so the same song
        // isn't treated as "new" again (which used to reload lyrics, theme and the like state).
        String key = np == null ? lastTrackKey : np.key();
        boolean changed = key != null && !key.equals(lastTrackKey);
        lastTrackKey = key;

        if (changed && lyricsEnabled) loadLyrics(np);
        if (changed) onTrackChanged(np);
        Platform.runLater(() -> {
            panel.setMood(beat.mood());
            if (np != null) panel.setNowPlaying(np);
            panel.setState(playing ? CatState.DANCING : CatState.SLEEPING);
            if (changed && playing) panel.showToast("♪ " + np.label());
        });
    }

    /** New song: ask Spotify whether it's liked and derive the color theme from the album cover. */
    private void onTrackChanged(NowPlaying track) {
        liked = false;
        Platform.runLater(() -> panel.setLiked(false));
        if (track.trackId() != null) {
            spotify.isSaved(track.trackId()).whenComplete((saved, err) -> {
                if (err != null) {
                    System.err.println("Couldn't read like state: " + err.getMessage());
                    return;
                }
                if (isCurrent(track)) {
                    liked = saved;
                    Platform.runLater(() -> panel.setLiked(saved));
                }
            });
        }
        albumArt.dominantColor(track.albumImageUrl()).thenAccept(color -> {
            if (isCurrent(track)) {
                Theme theme = color.map(Theme::fromColor).orElse(Theme.DEFAULT);
                Platform.runLater(() -> panel.setTheme(theme));
            }
        });
    }

    private boolean isCurrent(NowPlaying track) {
        NowPlaying np = current;
        return np != null && np.key().equals(track.key());
    }

    private void loadLyrics(NowPlaying track) {
        Platform.runLater(() -> panel.setLyrics(null));
        lyrics.fetch(track).whenComplete((result, err) -> Platform.runLater(() -> {
            if (!isCurrent(track)) return; // the song changed in the meantime
            if (err != null) {
                System.err.println("Couldn't load lyrics: " + err.getMessage());
                if (lyricsEnabled) panel.showToast("Couldn't load lyrics");
                return;
            }
            panel.setLyrics(result.orElse(null));
            if (result.isEmpty() && lyricsEnabled) panel.showToast("No synced lyrics for this song");
        }));
    }
}
