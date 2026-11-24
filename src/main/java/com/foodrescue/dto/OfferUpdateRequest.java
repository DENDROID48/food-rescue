package com.foodrescue.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.time.LocalTime;

/**
 * Quick change of a today's offer: the units still on the shelf and the pickup window (times of
 * day today, in the store's zone). Units already reserved are not part of these numbers.
 */
public record OfferUpdateRequest(
        @NotNull @Min(0) @Max(10_000) Integer quantityAvailable,
        @NotNull @Min(0) @Max(10_000) Integer payItForwardQuantity,
        @NotNull LocalTime pickupStart,
        @NotNull LocalTime pickupEnd) {
}
