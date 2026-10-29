package com.foodrescue.dto;

import jakarta.validation.constraints.NotBlank;

/** JSON body of the login endpoint: {@code {"email": "...", "password": "..."}}. */
public record LoginRequest(@NotBlank String email, @NotBlank String password) {
}
