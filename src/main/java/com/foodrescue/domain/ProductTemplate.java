package com.foodrescue.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalTime;
import java.util.EnumSet;
import java.util.Set;

/**
 * A product a store sells again and again ("Sourdough loaf", "Bakery surprise bag"). It holds
 * everything that stays the same from day to day. Each day the store turns templates into
 * offers ({@link FoodItem}) by confirming that day's quantities: a template never carries a
 * quantity, so yesterday's stock can never be published again by accident.
 *
 * <p>Templates are archived instead of deleted, because past offers keep pointing to them.</p>
 */
@Entity
@Table(name = "product_template")
@Getter
@Setter
@NoArgsConstructor
public class ProductTemplate {

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

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OfferKind kind = OfferKind.PRODUCT;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal originalPrice;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal discountedPrice;

    /** Usual start of the pickup window, as a time of day in the store's time zone. */
    @Column(nullable = false)
    private LocalTime pickupStart;

    /** Usual end of the pickup window, later the same day. */
    @Column(nullable = false)
    private LocalTime pickupEnd;

    @Column(nullable = false)
    private boolean hasPhoto;

    /** Approximate weight of one unit in grams; optional, used for "kg of food rescued". */
    private Integer unitWeightGrams;

    /** Food types chosen by the shop, for example BAKERY and VEGETARIAN. */
    @Convert(converter = FoodTagsConverter.class)
    @Column(nullable = false, length = 200)
    private Set<FoodTag> tags = EnumSet.noneOf(FoodTag.class);

    @Column(nullable = false)
    private boolean archived;

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    /** Optimistic locking; also part of the photo link so browsers fetch a replaced photo. */
    @Version
    private Long version;
}
