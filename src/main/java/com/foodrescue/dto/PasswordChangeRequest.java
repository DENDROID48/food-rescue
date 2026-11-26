package com.foodrescue.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record PasswordChangeRequest(
        @NotBlank @Size(max = 72) String currentPassword,
        @NotNull @Size(min = 8, max = 72, message = "must be between 8 and 72 characters") String newPassword) {
}
