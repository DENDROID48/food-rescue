package com.foodrescue.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;

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
public class FoodItem implements Expirable {

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

    /** Approximate weight of one unit in grams, as the shop estimates it; null when unknown. */
    private Integer unitWeightGrams;

    /**
     * Units for sale and free units this offer was published with, adjusted when the owner
     * changes the shelf count. Used for sell-through figures; null on offers older than this field.
     */
    private Integer quantityPublished;
    private Integer freePublished;

    /**
     * Moment after which the item must no longer be reserved or collected.
     * Stored as {@link Instant} (UTC) so that time zones never cause bugs on the server.
     */
    @Column(nullable = false)
    private Instant expiresAt;

    /** The reusable product this offer was created from, or null for a one-off offer. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "template_id")
    private ProductTemplate template;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OfferKind kind = OfferKind.PRODUCT;

    /** Only ACTIVE offers are shown to customers and can be reserved. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OfferStatus status = OfferStatus.ACTIVE;

    /**
     * Start of the pickup window, or null when the food can be collected straight away. The end
     * of the window is {@link #expiresAt}.
     */
    private Instant pickupStartsAt;

    /**
     * True for a Rescue Drop: a short offer that starts the moment the store launches it and
     * that opted-in customers nearby are alerted about.
     */
    @Column(nullable = false)
    private boolean rescueDrop;

    /** Food types chosen by the shop, for example BAKERY and VEGETARIAN. */
    @Convert(converter = FoodTagsConverter.class)
    @Column(nullable = false, length = 200)
    private Set<FoodTag> tags = EnumSet.noneOf(FoodTag.class);

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

    /** An offer can no longer be reserved from its expiry time on. */
    @Override
    public Instant expiryMoment() {
        return expiresAt;
    }

    /** True while the item has not expired at the given moment. */
    public boolean isNotExpired(Instant now) {
        return !isExpiredAt(now);
    }

    /** What the store owner sees: expiry wins over everything, then the store's own decision, then stock. */
    public OfferState state(Instant now) {
        if (isExpiredAt(now)) {
            return OfferState.EXPIRED;
        }
        if (status == OfferStatus.DRAFT) {
            return OfferState.DRAFT;
        }
        if (status == OfferStatus.PAUSED) {
            return OfferState.PAUSED;
        }
        if (quantityAvailable == 0 && payItForwardQuantity == 0) {
            return OfferState.SOLD_OUT;
        }
        return OfferState.ACTIVE;
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
    /**
     * The owner corrects the units still on the shelf. The published totals move by the same
     * amount, so "offered" stays equal to "reserved so far + still on the shelf".
     */
    public void setShelf(int forSale, int free) {
        if (quantityPublished != null) quantityPublished = Math.max(0, quantityPublished + forSale - quantityAvailable);
        if (freePublished != null) freePublished = Math.max(0, freePublished + free - payItForwardQuantity);
        quantityAvailable = forSale;
        payItForwardQuantity = free;
    }

    /** Before the first save: check the prices and remember what the offer was published with. */
    @PrePersist
    void beforeInsert() {
        validatePrices();
        if (quantityPublished == null) quantityPublished = quantityAvailable;
        if (freePublished == null) freePublished = payItForwardQuantity;
    }

    @PreUpdate
    void validatePrices() {
        if (originalPrice == null || discountedPrice == null
                || discountedPrice.signum() < 0
                || discountedPrice.compareTo(originalPrice) >= 0) {
            throw new IllegalStateException("discountedPrice must be lower than originalPrice and not negative");
        }
    }
}
