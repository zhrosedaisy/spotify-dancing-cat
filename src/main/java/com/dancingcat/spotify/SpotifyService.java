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
 * Spotify bağlantısının tamamı: giriş + saniyede bir "ne çalıyor?" sorgusu.
 * Sonuç her seferinde listener'a iletilir (boş Optional = hiçbir şey çalmıyor).
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

    /** Gerekirse tarayıcıda giriş açar, sonra dinlemeye başlar. Arka planda çalışır. */
    public void connectAsync() {
        Thread.ofVirtual().name("spotify-login").start(() -> {
            if (!auth.isLoggedIn()) {
                if (!loggingIn.compareAndSet(false, true)) return; // zaten giriş bekleniyor
                try {
                    auth.login();
                } catch (IOException e) {
                    System.err.println("Spotify girişi başarısız: " + e.getMessage());
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

    // ---- Kontroller (Premium gerekir). Hepsi aynı arka plan iş parçacığında, sırayla çalışır. ----

    public CompletableFuture<Void> previous() { return runAction(client::previous); }

    public CompletableFuture<Void> next() { return runAction(client::next); }

    public CompletableFuture<Void> togglePlay() {
        return runAction(() -> {
            if (last.map(NowPlaying::playing).orElse(false)) client.pause();
            else client.play();
        });
    }

    // ---- Beğenilen Şarkılar

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

    /** Spotify iş parçacığında çalıştırıp sonucu döner. */
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
        // Spotify'ın durumu güncellemesi için kısa bir süre bekleyip hemen tekrar sor
        scheduler.schedule(this::poll, 400, TimeUnit.MILLISECONDS);
        return result;
    }

    /** Hataları kullanıcıya gösterilebilecek kısa bir mesaja çevirir. */
    public static String friendlyError(Throwable t) {
        Throwable e = t instanceof CompletionException && t.getCause() != null ? t.getCause() : t;
        if (e instanceof SpotifyException se) {
            return switch (se.status()) {
                case 404 -> "Aktif cihaz yok, önce Spotify'ı aç";
                case 403 -> "Spotify izin vermedi";
                case 429 -> "Biraz yavaş, tekrar dene";
                default -> "Spotify hatası";
            };
        }
        return "Bağlantı hatası";
    }

    private void poll() {
        if (System.currentTimeMillis() < pausedUntil) return;
        if (!auth.isLoggedIn()) { // izin geri alınmışsa
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
            logError("Bağlantı hatası: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) { // zamanlayıcı ölmesin
            logError("Beklenmeyen hata: " + e);
        }
    }

    private void logChanges(Optional<NowPlaying> now) {
        String label = now.map(NowPlaying::label).orElse(null);
        Boolean playing = now.map(NowPlaying::playing).orElse(false);
        if (!Objects.equals(label, lastLabel) || !Objects.equals(playing, lastPlaying)) {
            System.out.println(label == null ? "⏹  Hiçbir şey çalmıyor"
                    : (playing ? "▶  " : "⏸  ") + label);
            lastLabel = label;
            lastPlaying = playing;
        }
    }

    private void logError(String message) {
        if (!message.equals(lastError)) System.err.println(message); // aynı hatayı her saniye basma
        lastError = message;
    }
}
