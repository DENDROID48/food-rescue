package com.foodrescue.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** The token from the emailed link and the new password. */
public record PasswordResetConfirmRequest(
        @NotBlank @Size(max = 100) String token,
        @NotNull @Size(min = 8, max = 72, message = "must be between 8 and 72 characters") String newPassword) {
}
