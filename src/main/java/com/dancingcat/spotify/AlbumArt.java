package com.dancingcat.spotify;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/** Downloads the album cover and finds its dominant (most vivid and most common) color. */
public final class AlbumArt {

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final Map<String, Optional<Color>> cache = new ConcurrentHashMap<>();

    public CompletableFuture<Optional<Color>> dominantColor(String imageUrl) {
        if (imageUrl == null) return CompletableFuture.completedFuture(Optional.empty());
        Optional<Color> cached = cache.get(imageUrl);
        if (cached != null) return CompletableFuture.completedFuture(cached);
        HttpRequest request = HttpRequest.newBuilder(URI.create(imageUrl)).timeout(Duration.ofSeconds(10)).build();
        return http.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray()).thenApply(response -> {
            try {
                BufferedImage img = ImageIO.read(new ByteArrayInputStream(response.body()));
                Optional<Color> color = img == null ? Optional.empty() : Optional.ofNullable(dominant(img));
                cache.put(imageUrl, color);
                return color;
            } catch (IOException e) {
                return Optional.empty();
            }
        });
    }

    /**
     * Sorts colors into 16-level buckets; each pixel votes, and more saturated pixels vote more.
     * Very dark / very white pixels are ignored. Returns the average color of the winning bucket.
     */
    static Color dominant(BufferedImage img) {
        Map<Integer, double[]> buckets = new HashMap<>(); // key -> {votes, r sum, g sum, b sum, count}
        int step = Math.max(1, Math.min(img.getWidth(), img.getHeight()) / 64);
        float[] hsb = new float[3];
        for (int y = 0; y < img.getHeight(); y += step) {
            for (int x = 0; x < img.getWidth(); x += step) {
                int rgb = img.getRGB(x, y);
                int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
                Color.RGBtoHSB(r, g, b, hsb);
                if (hsb[2] < 0.15f || (hsb[2] > 0.95f && hsb[1] < 0.1f)) continue;
                int key = (r >> 4) << 8 | (g >> 4) << 4 | (b >> 4);
                double[] acc = buckets.computeIfAbsent(key, k -> new double[5]);
                acc[0] += 1 + 3 * hsb[1];
                acc[1] += r;
                acc[2] += g;
                acc[3] += b;
                acc[4]++;
            }
        }
        double[] best = null;
        for (double[] acc : buckets.values()) if (best == null || acc[0] > best[0]) best = acc;
        if (best == null) return null;
        return new Color((int) (best[1] / best[4]), (int) (best[2] / best[4]), (int) (best[3] / best[4]));
    }
}
