package com.dancingcat.spotify;

/** tokens.json içinde saklanan erişim bilgileri. */
public record Tokens(String accessToken, String refreshToken, long expiresAtMillis, String scope) {}
