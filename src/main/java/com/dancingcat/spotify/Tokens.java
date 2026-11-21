package com.dancingcat.spotify;

/** Credentials stored in tokens.json. */
public record Tokens(String accessToken, String refreshToken, long expiresAtMillis, String scope) {}
