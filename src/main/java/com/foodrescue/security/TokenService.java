package com.foodrescue.security;

import com.foodrescue.config.SecurityProperties;
import com.foodrescue.domain.User;
import lombok.RequiredArgsConstructor;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Issues the signed login token (a JWT) returned after login or registration. The client sends
 * it back as {@code Authorization: Bearer <token>}; SecurityConfig verifies the signature and
 * expiry on every request, so no session is kept on the server.
 *
 * <p>The token carries the user id (subject) and role. Everything else, such as eligibility
 * for free items, is read fresh from the database, so a change takes effect immediately.</p>
 */
@Service
@RequiredArgsConstructor
public class TokenService {

    static final String ISSUER = "food-rescue";
    static final String ROLE_CLAIM = "role";

    private final JwtEncoder jwtEncoder;
    private final SecurityProperties properties;
    private final Clock clock;

    public IssuedToken issue(User user) {
        Instant now = clock.instant();
        Instant expiresAt = now.plus(Duration.ofHours(properties.tokenValidityHours()));

        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(ISSUER)
                .subject(String.valueOf(user.getId()))
                .claim(ROLE_CLAIM, user.getRole().name())
                .issuedAt(now)
                .expiresAt(expiresAt)
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();

        String token = jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new IssuedToken(token, expiresAt);
    }

    public record IssuedToken(String value, Instant expiresAt) {
    }
}
