package com.foodrescue.dto;

import com.foodrescue.domain.UserRole;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * JSON body for creating an account, for example
 * {@code {"fullName": "Ana Pop", "email": "ana@example.com", "password": "...", "role": "CONSUMER"}}.
 * Only CONSUMER and STORE_OWNER may be chosen; administrators are never self-registered.
 * The 72 character limit is the most BCrypt can use.
 */
public record RegisterUserRequest(
        @NotBlank @Size(max = 120) String fullName,
        @NotBlank @Email @Size(max = 180) String email,
        @NotNull @Size(min = 8, max = 72, message = "must be between 8 and 72 characters") String password,
        @NotNull UserRole role,
        /** Interface and email language; English when missing. */
        @Pattern(regexp = AccountUpdateRequest.LANGUAGES, message = "is not a supported language") String language,
        /** The person agreed to the terms and the privacy policy; required. */
        @AssertTrue(message = "Please accept the terms and the privacy policy") boolean acceptTerms) {
}
