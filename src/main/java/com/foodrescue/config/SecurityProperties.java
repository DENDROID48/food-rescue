package com.foodrescue.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Login token settings, read from {@code foodrescue.security} in application.yml.
 *
 * @param jwtSecret           key that signs tokens (HMAC-SHA256); at least 32 characters
 * @param tokenValidityHours  how long a login lasts before the user must sign in again
 */
@ConfigurationProperties(prefix = "foodrescue.security")
@Validated // The application refuses to start with a missing or too short secret.
public record SecurityProperties(
        @NotBlank @Size(min = 32, message = "must be at least 32 characters") String jwtSecret,
        @Min(1) int tokenValidityHours) {
}
