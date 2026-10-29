package com.foodrescue.service;

import com.foodrescue.domain.User;
import com.foodrescue.dto.AuthResponse;
import com.foodrescue.dto.LoginRequest;
import com.foodrescue.dto.UserResponse;
import com.foodrescue.exception.BusinessRuleException;
import com.foodrescue.repository.UserRepository;
import com.foodrescue.security.TokenService;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/** Checks email and password and hands out a login token. */
@Service
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final TokenService tokenService;

    /**
     * Hash of a random value, checked against when the email is unknown. An unknown email then
     * takes as long to reject as a wrong password, so which emails exist cannot be found by timing.
     */
    private final String dummyHash;

    public AuthService(UserRepository userRepository, PasswordEncoder passwordEncoder, TokenService tokenService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.tokenService = tokenService;
        this.dummyHash = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    @Transactional(readOnly = true)
    public AuthResponse login(LoginRequest request) {
        Optional<User> user = userRepository.findByEmail(normalizeEmail(request.email()));
        String hash = user.map(User::getPasswordHash).orElse(dummyHash);
        boolean passwordMatches = passwordEncoder.matches(request.password(), hash);

        if (user.isEmpty() || !passwordMatches) {
            // One message for both cases, so the response does not reveal which emails exist.
            throw new BusinessRuleException(HttpStatus.UNAUTHORIZED, "Wrong email or password");
        }
        return issueFor(user.get());
    }

    /** Builds the login response for a user, used after login and right after registration. */
    public AuthResponse issueFor(User user) {
        TokenService.IssuedToken token = tokenService.issue(user);
        return new AuthResponse(token.value(), token.expiresAt(), UserResponse.from(user));
    }

    /** Emails are compared case insensitively, so "Ana@x.com" and "ana@x.com" are one account. */
    static String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
