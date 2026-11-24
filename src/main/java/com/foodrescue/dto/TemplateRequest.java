package com.foodrescue.dto;

import com.foodrescue.domain.FoodTag;
import java.util.Set;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import com.foodrescue.domain.OfferKind;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalTime;

/**
 * Create or replace a reusable product. Pickup times are times of day ("18:00") in the store's
 * time zone. There is deliberately no quantity: quantities are confirmed every day.
 */
public record TemplateRequest(
        @NotBlank @Size(max = 150) String name,
        @Size(max = 500) String description,
        @NotNull OfferKind kind,
        @NotNull @DecimalMin("0.01") @Digits(integer = 8, fraction = 2) BigDecimal originalPrice,
        @NotNull @DecimalMin("0.00") @Digits(integer = 8, fraction = 2) BigDecimal discountedPrice,
        @NotNull LocalTime pickupStart,
        @NotNull LocalTime pickupEnd,
        /** Optional food types, as stated by the shop. */
        @Size(max = 6) Set<FoodTag> tags,
        /** Optional approximate weight of one unit, in grams. */
        @Min(1) @Max(50_000) Integer unitWeightGrams) {

    public TemplateRequest(String name, String description, OfferKind kind, BigDecimal originalPrice,
                           BigDecimal discountedPrice, LocalTime pickupStart, LocalTime pickupEnd) {
        this(name, description, kind, originalPrice, discountedPrice, pickupStart, pickupEnd, null, null);
    }
}
