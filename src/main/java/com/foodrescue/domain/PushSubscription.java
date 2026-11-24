package com.foodrescue.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * One browser that agreed to receive push messages for a user. The endpoint is a URL of the
 * browser maker's push service; p256dh and auth are the browser's keys used to encrypt messages,
 * so the push service itself cannot read them.
 */
@Entity
@Table(name = "push_subscription")
@Getter
@Setter
@NoArgsConstructor
public class PushSubscription {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false, unique = true, length = 1000)
    private String endpoint;

    @Column(nullable = false, length = 200)
    private String p256dh;

    @Column(nullable = false, length = 100)
    private String auth;

    @Column(nullable = false)
    private Instant createdAt;

    private Instant lastSuccessAt;

    @Column(nullable = false)
    private int failures;
}
