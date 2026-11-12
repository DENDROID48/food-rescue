package com.foodrescue.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** JSON body of the login endpoint: {@code {"email": "...", "password": "..."}}. */
public record LoginRequest(
        @NotBlank @Email @Size(max = 180) String email,
        @NotBlank @Size(max = 72) String password) {
}
