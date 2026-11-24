package com.foodrescue.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * A customer's opt-in for Rescue Drop alerts around a place they chose (home, work). The place is
 * only used to decide whether a drop is close enough; it is never shown to stores.
 */
@Entity
@Table(name = "drop_alert_preference")
@Getter
@Setter
@NoArgsConstructor
public class DropAlertPreference implements Locatable {

    /** Same as the user's id: one preference per user. */
    @Id
    private Long userId;

    @Column(nullable = false)
    private boolean enabled;

    @Column(nullable = false)
    private double latitude;

    @Column(nullable = false)
    private double longitude;

    @Column(nullable = false)
    private double radiusKm;

    /** Upper bound of drop alerts in any 24 hours, chosen by the customer. */
    @Column(nullable = false)
    private int maxPerDay;

    @Column(nullable = false)
    private Instant updatedAt;
}
