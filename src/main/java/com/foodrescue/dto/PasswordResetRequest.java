package com.foodrescue.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** "Forgot password": where to send the link. */
public record PasswordResetRequest(@NotBlank @Email @Size(max = 180) String email) {
}
