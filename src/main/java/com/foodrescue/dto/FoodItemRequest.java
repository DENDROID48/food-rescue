package com.foodrescue.dto;

import com.foodrescue.domain.FoodTag;
import java.util.Set;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import com.foodrescue.domain.OfferKind;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * JSON body a store owner sends to publish or edit surplus food. On edit every field is
 * replaced, and the quantities mean "units still left", not "units originally offered".
 * That the discounted price is really lower than the original price is checked in the service.
 */
public record FoodItemRequest(
        @NotBlank @Size(max = 150) String name,
        @Size(max = 500) String description,
        @NotNull @DecimalMin("0.01") @Digits(integer = 8, fraction = 2) BigDecimal originalPrice,
        @NotNull @DecimalMin("0.00") @Digits(integer = 8, fraction = 2) BigDecimal discountedPrice,
        @NotNull @Min(0) Integer quantityAvailable,
        @NotNull @Min(0) Integer payItForwardQuantity,
        @NotNull @Future Instant expiresAt,
        /** Optional, PRODUCT when missing. */
        OfferKind kind,
        /** Optional start of the pickup window; must be before expiresAt. */
        Instant pickupStartsAt,
        /** Optional food types, as stated by the shop; null keeps the current ones. */
        @Size(max = 6) Set<FoodTag> tags,
        /** Optional approximate weight of one unit, in grams. */
        @Min(1) @Max(50_000) Integer unitWeightGrams) {

    public FoodItemRequest(String name, String description, BigDecimal originalPrice, BigDecimal discountedPrice,
                           Integer quantityAvailable, Integer payItForwardQuantity, Instant expiresAt,
                           OfferKind kind, Instant pickupStartsAt) {
        this(name, description, originalPrice, discountedPrice, quantityAvailable, payItForwardQuantity, expiresAt,
                kind, pickupStartsAt, null, null);
    }

    /** The fields every client sent before kinds and pickup windows existed. */
    public FoodItemRequest(String name, String description, BigDecimal originalPrice, BigDecimal discountedPrice,
                           Integer quantityAvailable, Integer payItForwardQuantity, Instant expiresAt) {
        this(name, description, originalPrice, discountedPrice, quantityAvailable, payItForwardQuantity, expiresAt, null, null, null, null);
    }
}
