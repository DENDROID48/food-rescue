package com.foodrescue.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * A reservation of food by a consumer, either paid (discounted) or free (Pay-It-Forward).
 *
 * <p>The table is named {@code orders} because {@code order} is an SQL keyword.</p>
 *
 * <p>The price is copied into {@code totalPrice} at reservation time. Later edits to the
 * food item never change what a consumer already agreed to pay.</p>
 */
@Entity
@Table(name = "orders") // Indexes are created by the Flyway migrations in db/migration.
@Getter
@Setter
@NoArgsConstructor
public class Order implements Expirable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "food_item_id", nullable = false)
    private FoodItem foodItem;

    @Column(nullable = false)
    private int quantity;

    /** Zero for Pay-It-Forward orders. */
    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal totalPrice;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OrderType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OrderStatus status = OrderStatus.RESERVED;

    /** Short code the consumer shows at the store to collect the food. */
    @Column(nullable = false, length = 10)
    private String pickupCode;

    /** After this moment an uncollected reservation is released back into stock. */
    @Column(nullable = false)
    private Instant pickupDeadline;

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    /**
     * Optimistic locking counter. A consumer cancelling, a store confirming the pickup and the
     * expiry job may touch the same order at the same moment; only the first change wins and
     * the others fail instead of, for example, returning the stock twice.
     */
    @Version
    private Long version;

    /** A reservation expires at its pickup deadline. */
    @Override
    public Instant expiryMoment() {
        return pickupDeadline;
    }
}
