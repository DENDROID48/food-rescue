package com.foodrescue.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Changes to one's own account. Changing the email needs the current password, so someone who
 * finds an unlocked phone cannot take the account over.
 */
public record AccountUpdateRequest(
        @NotBlank @Size(max = 120) String fullName,
        @NotBlank @Email @Size(max = 180) String email,
        @NotBlank @Pattern(regexp = AccountUpdateRequest.LANGUAGES, message = "is not a supported language") String language,
        @Size(max = 72) String currentPassword) {

    /** The languages of the interface and emails. */
    public static final String LANGUAGES = "en|ro|es|fr|de|it|pt";
}
