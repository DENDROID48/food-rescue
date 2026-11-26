package com.foodrescue.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * A "forgot password" link sent by email. Only a hash of the token is stored, so someone who
 * reads the database cannot use the links. Each link works once and only for a short time.
 */
@Entity
@Table(name = "password_reset_token")
@Getter
@Setter
@NoArgsConstructor
public class PasswordResetToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false, unique = true, length = 64)
    private String tokenHash;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant expiresAt;

    private Instant usedAt;
}
