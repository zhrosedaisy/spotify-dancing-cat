package com.dancingcat.spotify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;

import java.awt.Desktop;
import java.io.IOException;
import java.io.OutputStream;
import java.net.BindException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * Spotify girişi: Authorization Code + PKCE.
 *
 * 1. Tarayıcıda Spotify giriş sayfası açılır.
 * 2. Kullanıcı onaylayınca Spotify bizi http://127.0.0.1:8888/callback?code=... adresine yollar.
 * 3. Bu adresi dinleyen mini sunucu "code"u yakalar, biz de onu token ile değiştiririz.
 * Token'lar tokens.json'a kaydedilir; bir sonraki açılışta tekrar giriş gerekmez.
 */
public final class SpotifyAuth {

    private static final String AUTHORIZE_URL = "https://accounts.spotify.com/authorize";
    private static final URI TOKEN_URL = URI.create("https://accounts.spotify.com/api/token");
    private static final String SCOPES = "user-read-playback-state user-read-currently-playing "
            + "user-modify-playback-state user-library-read user-library-modify";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final String clientId;
    private final URI redirectUri;
    private final Path tokenFile;
    private final HttpClient http;
    private final ObjectMapper json = new ObjectMapper();
    private Tokens tokens;

    public SpotifyAuth(String clientId, String redirectUri, Path tokenFile, HttpClient http) {
        this.clientId = clientId;
        this.redirectUri = URI.create(redirectUri);
        this.tokenFile = tokenFile;
        this.http = http;
        this.tokens = loadTokens();
    }

    public synchronized boolean isLoggedIn() {
        return tokens != null;
    }

    /** Geçerli bir erişim token'ı döner; süresi dolmak üzereyse önce yeniler. */
    public synchronized String accessToken() throws IOException, InterruptedException {
        if (tokens == null) throw new SpotifyException("Spotify'a giriş yapılmamış");
        if (System.currentTimeMillis() > tokens.expiresAtMillis() - 60_000) refresh();
        return tokens.accessToken();
    }

    public synchronized void refresh() throws IOException, InterruptedException {
        if (tokens == null) throw new SpotifyException("Spotify'a giriş yapılmamış");
        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "refresh_token");
        form.put("refresh_token", tokens.refreshToken());
        form.put("client_id", clientId);
        try {
            saveTokens(postToken(form), tokens.refreshToken());
        } catch (SpotifyException e) {
            if (e.status() == 400) { // refresh token geçersiz (izin geri alınmış vs.) → tekrar giriş gerekir
                tokens = null;
                Files.deleteIfExists(tokenFile);
            }
            throw e;
        }
    }

    /** Tarayıcıda girişi başlatır ve kullanıcı onaylayana kadar (en fazla 3 dk) bekler. */
    public void login() throws IOException, InterruptedException {
        String verifier = randomString(64);
        String challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(sha256(verifier));
        String state = randomString(16);
        CompletableFuture<String> code = new CompletableFuture<>();

        HttpServer server;
        try {
            server = HttpServer.create(new InetSocketAddress(
                    InetAddress.getByName(redirectUri.getHost()), redirectUri.getPort()), 0);
        } catch (BindException e) {
            throw new SpotifyException(redirectUri.getPort() + " portu kullanımda. Uygulama zaten açık olabilir.");
        }
        server.createContext(redirectUri.getPath(), exchange -> {
            Map<String, String> query = parseQuery(exchange.getRequestURI().getRawQuery());
            String message;
            if (!state.equals(query.get("state"))) {
                message = "Bir şeyler ters gitti (state uyuşmadı). Tekrar dene.";
                code.completeExceptionally(new SpotifyException("Giriş doğrulanamadı (state)"));
            } else if (query.containsKey("error")) {
                message = "Giriş iptal edildi.";
                code.completeExceptionally(new SpotifyException("Giriş reddedildi: " + query.get("error")));
            } else {
                message = "Giriş tamam! Bu sekmeyi kapatabilirsin 🐱";
                code.complete(query.get("code"));
            }
            byte[] html = ("<!doctype html><meta charset='utf-8'><title>Dancing Cat</title>"
                    + "<body style='font-family:-apple-system,sans-serif;text-align:center;padding-top:80px'>"
                    + "<h2>" + message + "</h2></body>").getBytes(UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, html.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(html);
            }
        });
        server.start();

        try {
            Map<String, String> params = new LinkedHashMap<>();
            params.put("client_id", clientId);
            params.put("response_type", "code");
            params.put("redirect_uri", redirectUri.toString());
            params.put("code_challenge_method", "S256");
            params.put("code_challenge", challenge);
            params.put("scope", SCOPES);
            params.put("state", state);
            URI url = URI.create(AUTHORIZE_URL + "?" + form(params));

            System.out.println("Tarayıcıda Spotify girişi açılıyor...");
            Desktop.getDesktop().browse(url);

            String authCode = code.get(3, TimeUnit.MINUTES);
            Map<String, String> form = new LinkedHashMap<>();
            form.put("grant_type", "authorization_code");
            form.put("code", authCode);
            form.put("redirect_uri", redirectUri.toString());
            form.put("client_id", clientId);
            form.put("code_verifier", verifier);
            JsonNode body = postToken(form);
            synchronized (this) {
                saveTokens(body, null);
            }
            System.out.println("Spotify girişi başarılı ✅");
        } catch (ExecutionException e) {
            throw e.getCause() instanceof SpotifyException se ? se : new SpotifyException(e.getCause().toString());
        } catch (TimeoutException e) {
            throw new SpotifyException("Giriş 3 dakika içinde tamamlanmadı");
        } finally {
            server.stop(0);
        }
    }

    private JsonNode postToken(Map<String, String> form) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(TOKEN_URL)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form(form)))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new SpotifyException("Token isteği başarısız (" + response.statusCode() + "): "
                    + response.body(), response.statusCode(), 0);
        }
        return json.readTree(response.body());
    }

    private void saveTokens(JsonNode body, String previousRefreshToken) throws IOException {
        String refresh = body.hasNonNull("refresh_token") ? body.get("refresh_token").asText() : previousRefreshToken;
        String scope = body.hasNonNull("scope") ? body.get("scope").asText()
                : tokens != null ? tokens.scope() : null;
        tokens = new Tokens(
                body.get("access_token").asText(),
                refresh,
                System.currentTimeMillis() + body.path("expires_in").asLong(3600) * 1000,
                scope);
        json.writerWithDefaultPrettyPrinter().writeValue(tokenFile.toFile(), tokens);
        try {
            Files.setPosixFilePermissions(tokenFile, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException ignored) {
            // Windows'ta POSIX izinleri yok
        }
    }

    private Tokens loadTokens() {
        if (!Files.exists(tokenFile)) return null;
        try {
            Tokens t = json.readValue(tokenFile.toFile(), Tokens.class);
            // Uygulama yeni izinler istiyorsa (ör. beğenme) bir kez tekrar giriş gerekir
            if (t.scope() == null || !java.util.Set.of(t.scope().split(" ")).containsAll(java.util.Set.of(SCOPES.split(" ")))) {
                System.out.println("Yeni Spotify izinleri gerekiyor (beğenme) → tarayıcıda bir kez tekrar giriş açılacak.");
                return null;
            }
            return t;
        } catch (IOException e) {
            System.err.println("tokens.json okunamadı, tekrar giriş gerekecek: " + e.getMessage());
            return null;
        }
    }

    private static String form(Map<String, String> params) {
        return params.entrySet().stream()
                .map(e -> URLEncoder.encode(e.getKey(), UTF_8) + "=" + URLEncoder.encode(e.getValue(), UTF_8))
                .collect(Collectors.joining("&"));
    }

    private static Map<String, String> parseQuery(String rawQuery) {
        Map<String, String> map = new HashMap<>();
        if (rawQuery == null) return map;
        for (String pair : rawQuery.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                map.put(URLDecoder.decode(pair.substring(0, eq), UTF_8), URLDecoder.decode(pair.substring(eq + 1), UTF_8));
            }
        }
        return map;
    }

    private static String randomString(int length) {
        String chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) sb.append(chars.charAt(RANDOM.nextInt(chars.length())));
        return sb.toString();
    }

    private static byte[] sha256(String s) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(s.getBytes(UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
