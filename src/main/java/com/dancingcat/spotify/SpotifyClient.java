package com.dancingcat.spotify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Spotify Web API calls. */
public final class SpotifyClient {

    private static final String API = "https://api.spotify.com/v1";

    private final SpotifyAuth auth;
    private final HttpClient http;
    private final ObjectMapper json = new ObjectMapper();

    public SpotifyClient(SpotifyAuth auth, HttpClient http) {
        this.auth = auth;
        this.http = http;
    }

    /** Empty Optional = nothing is playing on any device. */
    public Optional<NowPlaying> currentlyPlaying() throws IOException, InterruptedException {
        long sentAt = System.currentTimeMillis();
        HttpResponse<String> response = send("GET", "/me/player/currently-playing");
        long receivedAt = System.currentTimeMillis();
        if (response.statusCode() == 204 || response.body().isBlank()) return Optional.empty();

        JsonNode root = json.readTree(response.body());
        boolean playing = root.path("is_playing").asBoolean(false);
        long progress = root.path("progress_ms").asLong(0);
        // Spotify measured the position roughly mid-request; compensate for half the round trip
        long now = (sentAt + receivedAt) / 2;
        JsonNode item = root.path("item");
        if (item.isMissingNode() || item.isNull()) {
            return Optional.of(new NowPlaying(null, "(unknown content)", "", "", "", null, 0, progress, playing, now));
        }
        List<String> artists = new ArrayList<>();
        item.path("artists").forEach(a -> artists.add(a.path("name").asText()));
        return Optional.of(new NowPlaying(
                item.path("id").asText(null),
                item.path("name").asText(""),
                String.join(", ", artists),
                artists.isEmpty() ? "" : artists.get(0),
                item.path("album").path("name").asText(""),
                smallestImage(item.path("album").path("images")),
                item.path("duration_ms").asLong(0),
                progress, playing, now));
    }

    /** The smallest album cover (64 px is enough to pick a color). */
    private static String smallestImage(JsonNode images) {
        String url = null;
        int best = Integer.MAX_VALUE;
        for (JsonNode img : images) {
            int w = img.path("width").asInt(Integer.MAX_VALUE - 1);
            if (w < best && img.hasNonNull("url")) {
                best = w;
                url = img.get("url").asText();
            }
        }
        return url;
    }

    // ---- Library (Liked Songs). Since February 2026 a single endpoint: /me/library?uris=spotify:track:ID

    public boolean isSaved(String trackId) throws IOException, InterruptedException {
        HttpResponse<String> r = send("GET", "/me/library/contains?uris=" + trackUri(trackId));
        JsonNode arr = json.readTree(r.body());
        return arr.isArray() && arr.size() > 0 && arr.get(0).asBoolean(false);
    }

    public void save(String trackId) throws IOException, InterruptedException {
        send("PUT", "/me/library?uris=" + trackUri(trackId));
    }

    public void unsave(String trackId) throws IOException, InterruptedException {
        send("DELETE", "/me/library?uris=" + trackUri(trackId));
    }

    private static String trackUri(String trackId) {
        return java.net.URLEncoder.encode("spotify:track:" + trackId, java.nio.charset.StandardCharsets.UTF_8);
    }

    // Playback controls (Premium required)
    public void next() throws IOException, InterruptedException { send("POST", "/me/player/next"); }

    public void previous() throws IOException, InterruptedException { send("POST", "/me/player/previous"); }

    public void pause() throws IOException, InterruptedException { send("PUT", "/me/player/pause"); }

    public void play() throws IOException, InterruptedException { send("PUT", "/me/player/play"); }

    private HttpResponse<String> send(String method, String path) throws IOException, InterruptedException {
        HttpResponse<String> response = sendOnce(method, path);
        if (response.statusCode() == 401) { // the token may have expired -> refresh and retry once
            auth.refresh();
            response = sendOnce(method, path);
        }
        int status = response.statusCode();
        if (status == 429) {
            int retry = response.headers().firstValue("Retry-After").map(Integer::parseInt).orElse(5);
            throw new SpotifyException("Spotify says too many requests, waiting " + retry + " s", 429, retry);
        }
        if (status >= 400) {
            throw new SpotifyException("Spotify error (" + status + "): " + response.body(), status, 0);
        }
        return response;
    }

    private HttpResponse<String> sendOnce(String method, String path) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(API + path))
                .header("Authorization", "Bearer " + auth.accessToken())
                .method(method, method.equals("GET") || method.equals("DELETE")
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(""))
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
