package com.dancingcat.spotify;

import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Everything about the Spotify connection: login + asking "what's playing?" once per second.
 * Each result is passed to the listener (empty Optional = nothing is playing).
 */
public final class SpotifyService {

    private final SpotifyAuth auth;
    private final SpotifyClient client;
    private final Consumer<Optional<NowPlaying>> listener;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "spotify");
        t.setDaemon(true);
        return t;
    });
    private final AtomicBoolean polling = new AtomicBoolean();
    private final AtomicBoolean loggingIn = new AtomicBoolean();

    private volatile long pausedUntil;
    private volatile Optional<NowPlaying> last = Optional.empty();
    private String lastLabel;
    private Boolean lastPlaying;
    private String lastError;

    public SpotifyService(String clientId, String redirectUri, Consumer<Optional<NowPlaying>> listener) {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        this.auth = new SpotifyAuth(clientId, redirectUri, Path.of("tokens.json"), http);
        this.client = new SpotifyClient(auth, http);
        this.listener = listener;
    }

    public boolean isConnected() {
        return auth.isLoggedIn();
    }

    public SpotifyClient client() {
        return client;
    }

    /** Opens the browser login if needed, then starts polling. Runs in the background. */
    public void connectAsync() {
        Thread.ofVirtual().name("spotify-login").start(() -> {
            if (!auth.isLoggedIn()) {
                if (!loggingIn.compareAndSet(false, true)) return; // a login is already in progress
                try {
                    auth.login();
                } catch (IOException e) {
                    System.err.println("Spotify login failed: " + e.getMessage());
                    return;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                } finally {
                    loggingIn.set(false);
                }
            }
            if (polling.compareAndSet(false, true)) {
                scheduler.scheduleWithFixedDelay(this::poll, 0, 1, TimeUnit.SECONDS);
            }
        });
    }

    // ---- Playback controls (Premium required). All run in order on the same background thread. ----

    public CompletableFuture<Void> previous() { return runAction(client::previous); }

    public CompletableFuture<Void> next() { return runAction(client::next); }

    public CompletableFuture<Void> togglePlay() {
        return runAction(() -> {
            if (last.map(NowPlaying::playing).orElse(false)) client.pause();
            else client.play();
        });
    }

    // ---- Liked Songs

    public CompletableFuture<Boolean> isSaved(String trackId) {
        return call(() -> client.isSaved(trackId));
    }

    public CompletableFuture<Boolean> setSaved(String trackId, boolean saved) {
        return call(() -> {
            if (saved) client.save(trackId);
            else client.unsave(trackId);
            return saved;
        });
    }

    private interface Call<T> {
        T run() throws IOException, InterruptedException;
    }

    /** Runs the call on the Spotify thread and returns the result. */
    private <T> CompletableFuture<T> call(Call<T> call) {
        CompletableFuture<T> result = new CompletableFuture<>();
        scheduler.execute(() -> {
            try {
                result.complete(call.run());
            } catch (Exception e) {
                result.completeExceptionally(e);
            }
        });
        return result;
    }

    private interface Action {
        void run() throws IOException, InterruptedException;
    }

    private CompletableFuture<Void> runAction(Action action) {
        CompletableFuture<Void> result = new CompletableFuture<>();
        scheduler.execute(() -> {
            try {
                action.run();
                result.complete(null);
            } catch (Exception e) {
                result.completeExceptionally(e);
            }
        });
        // give Spotify a moment to update its state, then ask again right away
        scheduler.schedule(this::poll, 400, TimeUnit.MILLISECONDS);
        return result;
    }

    /** Turns errors into a short message that can be shown to the user. */
    public static String friendlyError(Throwable t) {
        Throwable e = t instanceof CompletionException && t.getCause() != null ? t.getCause() : t;
        if (e instanceof SpotifyException se) {
            return switch (se.status()) {
                case 404 -> "No active device – open Spotify first";
                case 403 -> "Spotify didn't allow that";
                case 429 -> "Slow down a bit, try again";
                default -> "Spotify error";
            };
        }
        return "Connection error";
    }

    private void poll() {
        if (System.currentTimeMillis() < pausedUntil) return;
        if (!auth.isLoggedIn()) { // access was revoked
            listener.accept(Optional.empty());
            return;
        }
        try {
            Optional<NowPlaying> now = client.currentlyPlaying();
            logChanges(now);
            last = now;
            lastError = null;
            listener.accept(now);
        } catch (SpotifyException e) {
            if (e.retryAfterSeconds() > 0) pausedUntil = System.currentTimeMillis() + e.retryAfterSeconds() * 1000L;
            logError(e.getMessage());
        } catch (IOException e) {
            logError("Connection error: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) { // keep the scheduler alive
            logError("Beklenmeyen hata: " + e);
        }
    }

    private void logChanges(Optional<NowPlaying> now) {
        String label = now.map(NowPlaying::label).orElse(null);
        Boolean playing = now.map(NowPlaying::playing).orElse(false);
        if (!Objects.equals(label, lastLabel) || !Objects.equals(playing, lastPlaying)) {
            System.out.println(label == null ? "⏹  Nothing playing"
                    : (playing ? "▶  " : "⏸  ") + label);
            lastLabel = label;
            lastPlaying = playing;
        }
    }

    private void logError(String message) {
        if (!message.equals(lastError)) System.err.println(message); // don't print the same error every second
        lastError = message;
    }
}
