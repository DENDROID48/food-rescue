package com.foodrescue.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** The current password, asked again before something that cannot be undone (deleting the account). */
public record PasswordCheckRequest(@NotBlank @Size(max = 72) String password) {
}
