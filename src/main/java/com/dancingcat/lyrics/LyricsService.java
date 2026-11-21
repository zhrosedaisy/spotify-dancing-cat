package com.dancingcat.lyrics;

import com.dancingcat.spotify.NowPlaying;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * Fetches lyrics from LRCLIB (lrclib.net – free, no API key needed).
 * Tries an exact match first (/api/get), then falls back to a search (/api/search).
 * Results are cached in memory so the same song is never requested twice.
 */
public final class LyricsService {

    private static final String BASE = "https://lrclib.net/api";
    private static final String USER_AGENT = "DancingCat/0.1 (desktop pet)";

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private final ObjectMapper json = new ObjectMapper();
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final Map<String, Optional<Lyrics>> cache = new ConcurrentHashMap<>();

    /** Empty Optional = no synced lyrics found. */
    public CompletableFuture<Optional<Lyrics>> fetch(NowPlaying track) {
        Optional<Lyrics> cached = cache.get(track.key());
        if (cached != null) return CompletableFuture.completedFuture(cached);
        return CompletableFuture.supplyAsync(() -> {
            try {
                Optional<Lyrics> result = load(track);
                cache.put(track.key(), result);
                return result;
            } catch (IOException e) {
                throw new CompletionException(e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new CompletionException(e);
            }
        }, executor);
    }

    private Optional<Lyrics> load(NowPlaying track) throws IOException, InterruptedException {
        long durationSec = Math.round(track.durationMs() / 1000.0);

        Map<String, String> exact = new LinkedHashMap<>();
        exact.put("track_name", track.title());
        exact.put("artist_name", track.primaryArtist());
        exact.put("album_name", track.album());
        exact.put("duration", Long.toString(durationSec));
        HttpResponse<String> response = get("/get?" + query(exact));
        if (response.statusCode() == 200) {
            Optional<Lyrics> lyrics = fromJson(json.readTree(response.body()));
            if (lyrics.isPresent()) return lyrics;
        }

        // No exact match -> search and pick the result with synced lyrics and the closest duration
        Map<String, String> search = new LinkedHashMap<>();
        search.put("track_name", track.title());
        search.put("artist_name", track.primaryArtist());
        response = get("/search?" + query(search));
        if (response.statusCode() != 200) return Optional.empty();

        JsonNode best = null;
        double bestDiff = Double.MAX_VALUE;
        for (JsonNode candidate : json.readTree(response.body())) {
            if (!candidate.hasNonNull("syncedLyrics") && !candidate.path("instrumental").asBoolean()) continue;
            double diff = Math.abs(candidate.path("duration").asDouble(0) - durationSec);
            if (diff < bestDiff) {
                best = candidate;
                bestDiff = diff;
            }
        }
        return best != null && bestDiff <= 5 ? fromJson(best) : Optional.empty();
    }

    private static Optional<Lyrics> fromJson(JsonNode node) {
        if (node.path("instrumental").asBoolean(false)) return Optional.of(Lyrics.instrumentalTrack());
        if (!node.hasNonNull("syncedLyrics")) return Optional.empty();
        Lyrics lyrics = Lyrics.parse(node.get("syncedLyrics").asText());
        return lyrics.isEmpty() ? Optional.empty() : Optional.of(lyrics);
    }

    private HttpResponse<String> get(String path) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(BASE + path))
                .header("User-Agent", USER_AGENT)
                .timeout(Duration.ofSeconds(15))
                .GET()
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static String query(Map<String, String> params) {
        return params.entrySet().stream()
                .map(e -> URLEncoder.encode(e.getKey(), UTF_8) + "=" + URLEncoder.encode(e.getValue(), UTF_8))
                .collect(Collectors.joining("&"));
    }
}
