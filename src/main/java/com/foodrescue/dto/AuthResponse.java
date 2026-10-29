package com.foodrescue.dto;

import java.time.Instant;

/**
 * Returned after login and registration. The client keeps {@code token} and sends it on every
 * later request as {@code Authorization: Bearer <token>}.
 */
public record AuthResponse(String token, Instant expiresAt, UserResponse user) {
}
