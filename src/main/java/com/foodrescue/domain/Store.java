package com.foodrescue.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * A local shop (bakery, supermarket, restaurant) that publishes surplus food.
 *
 * <p>The location is stored as plain latitude and longitude in degrees (WGS84, the same
 * system GPS and Google Maps use). A composite index on both columns lets the database
 * narrow down candidates quickly before the exact distance is calculated.</p>
 */
@Entity
@Table(name = "store") // Indexes are created by the Flyway migrations in db/migration.
@Getter
@Setter
@NoArgsConstructor
public class Store {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 150)
    private String name;

    @Column(nullable = false)
    private String address;

    /** Degrees north (+) or south (-) of the equator, range -90 to 90. */
    @Column(nullable = false)
    private double latitude;

    /** Degrees east (+) or west (-) of the prime meridian, range -180 to 180. */
    @Column(nullable = false)
    private double longitude;

    /**
     * Inactive stores (suspended, closed, not yet approved) never show up in search results.
     */
    @Column(nullable = false)
    private boolean active = true;

    /**
     * The account that manages this store. LAZY means the owner row is only loaded from the
     * database when someone actually calls {@code getOwner()}.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "owner_id", nullable = false)
    private User owner;

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();
}
