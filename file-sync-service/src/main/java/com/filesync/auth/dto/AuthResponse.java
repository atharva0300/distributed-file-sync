package com.filesync.auth.dto;

public record AuthResponse(
        String token,
        String tokenType,
        Long userId,
        String email
) {
    public AuthResponse(String token, Long userId, String email) {
        this(token, "Bearer", userId, email);
    }
}
