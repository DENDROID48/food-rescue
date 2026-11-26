package com.foodrescue.security;

import com.foodrescue.domain.User;
import com.foodrescue.repository.UserRepository;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

/**
 * Refuses login tokens of deleted accounts, and tokens issued before the account's
 * {@code tokensValidAfter} (set after a password change, a password reset or "sign out
 * everywhere"). Without this, a stolen token would keep working until it expired.
 */
public class AccountTokenValidator implements OAuth2TokenValidator<Jwt> {

    private static final OAuth2Error SIGNED_OUT = new OAuth2Error("invalid_token", "This login has ended", null);

    private final UserRepository userRepository;

    public AccountTokenValidator(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt jwt) {
        Optional<User> user;
        try {
            user = userRepository.findById(Long.valueOf(jwt.getSubject()));
        } catch (NumberFormatException e) {
            return OAuth2TokenValidatorResult.failure(SIGNED_OUT);
        }
        if (user.isEmpty() || user.get().getDeletedAt() != null || user.get().getBlockedAt() != null) {
            return OAuth2TokenValidatorResult.failure(SIGNED_OUT);
        }
        Instant validAfter = user.get().getTokensValidAfter();
        Object ms = jwt.getClaims().get(TokenService.ISSUED_MS_CLAIM);
        Instant issuedAt = ms instanceof Number n ? Instant.ofEpochMilli(n.longValue()) : jwt.getIssuedAt();
        if (validAfter != null && (issuedAt == null || issuedAt.isBefore(validAfter.truncatedTo(ChronoUnit.MILLIS)))) {
            return OAuth2TokenValidatorResult.failure(SIGNED_OUT);
        }
        return OAuth2TokenValidatorResult.success();
    }
}
