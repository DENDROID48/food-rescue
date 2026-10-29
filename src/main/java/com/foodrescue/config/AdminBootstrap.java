package com.foodrescue.config;

import com.foodrescue.domain.User;
import com.foodrescue.domain.UserRole;
import com.foodrescue.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * Creates the first administrator of a new installation. Administrators cannot register
 * themselves, so without this a fresh production database would have none.
 *
 * <p>Set {@code FOODRESCUE_ADMIN_EMAIL} and {@code FOODRESCUE_ADMIN_PASSWORD} for the first
 * start. If an account with that email already exists nothing happens, so the variables can
 * stay set; removing them after the first start is still good practice.</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AdminBootstrap implements CommandLineRunner {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Value("${FOODRESCUE_ADMIN_EMAIL:}")
    private String email;

    @Value("${FOODRESCUE_ADMIN_PASSWORD:}")
    private String password;

    @Override
    public void run(String... args) {
        if (email.isBlank() || password.isBlank()) {
            return;
        }
        String normalized = email.trim().toLowerCase(Locale.ROOT);
        if (userRepository.existsByEmail(normalized)) {
            return;
        }
        if (password.length() < 12) {
            throw new IllegalStateException("FOODRESCUE_ADMIN_PASSWORD must be at least 12 characters");
        }

        User admin = new User();
        admin.setFullName("Administrator");
        admin.setEmail(normalized);
        admin.setPasswordHash(passwordEncoder.encode(password));
        admin.setRole(UserRole.ADMIN);
        userRepository.save(admin);
        log.info("Created administrator account {}", normalized);
    }
}
