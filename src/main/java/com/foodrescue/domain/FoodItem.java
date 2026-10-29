package com.foodrescue.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * A batch of surplus food offered by a store.
 *
 * <h3>Two independent stock counters</h3>
 * <ul>
 *   <li>{@code quantityAvailable}: units that can be bought at {@code discountedPrice}.</li>
 *   <li>{@code payItForwardQuantity}: units the store (or donors) set aside to be given away
 *       for free to eligible users.</li>
 * </ul>
 * Keeping them separate means a free claim can never silently consume paid stock, and the
 * other way round.
 *
 * <h3>Concurrency</h3>
 * Many people may try to take the last unit at the same moment. Two defences are used:
 * a pessimistic row lock in the repository (see {@code findByIdForUpdate}) and the
 * {@code @Version} column below, which makes Hibernate reject any stale concurrent update.
 */
@Entity
@Table(name = "food_item") // Indexes are created by the Flyway migrations in db/migration.
@Getter
@Setter
@NoArgsConstructor
public class FoodItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "store_id", nullable = false)
    private Store store;

    @Column(nullable = false, length = 150)
    private String name;

    @Column(length = 500)
    private String description;

    /**
     * Regular shelf price. Money is always {@link BigDecimal}, never double, because binary
     * floating point cannot represent values like 0.10 exactly.
     */
    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal originalPrice;

    /** Price paid by the consumer. Must be lower than {@code originalPrice} (checked in {@link #validatePrices()}). */
    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal discountedPrice;

    /** Units still available for purchase. */
    @Column(nullable = false)
    private int quantityAvailable;

    /** Units still available as free Pay-It-Forward donations. */
    @Column(nullable = false)
    private int payItForwardQuantity;

    /**
     * Moment after which the item must no longer be reserved or collected.
     * Stored as {@link Instant} (UTC) so that time zones never cause bugs on the server.
     */
    @Column(nullable = false)
    private Instant expiresAt;

    /** True when a photo exists in {@link FoodItemPhoto}, so lists can link it without loading it. */
    @Column(nullable = false)
    private boolean hasPhoto;

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    /** Optimistic locking counter, managed automatically by Hibernate. */
    @Version
    private Long version;

    /**
     * Puts units from a released order (cancelled or never collected) back into the counter
     * they came from, so a free unit never turns into a paid one or the other way round.
     */
    public void returnStock(OrderType type, int quantity) {
        if (type == OrderType.PAY_IT_FORWARD) {
            payItForwardQuantity += quantity;
        } else {
            quantityAvailable += quantity;
        }
    }

    /** True while the item has not expired at the given moment. */
    public boolean isNotExpired(Instant now) {
        return expiresAt.isAfter(now);
    }

    /** Percentage saved by the consumer, rounded for display (for example 60 means 60 percent off). */
    public int discountPercent() {
        if (originalPrice.signum() == 0) {
            return 0;
        }
        return originalPrice.subtract(discountedPrice)
                .multiply(BigDecimal.valueOf(100))
                .divide(originalPrice, 0, java.math.RoundingMode.HALF_UP)
                .intValue();
    }

    /**
     * Business rule enforced before every insert and update: prices must be positive and the
     * discount must be a real discount. JPA calls this automatically.
     */
    @PrePersist
    @PreUpdate
    void validatePrices() {
        if (originalPrice == null || discountedPrice == null
                || discountedPrice.signum() < 0
                || discountedPrice.compareTo(originalPrice) >= 0) {
            throw new IllegalStateException("discountedPrice must be lower than originalPrice and not negative");
        }
    }
}
