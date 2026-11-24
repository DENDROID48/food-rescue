package com.foodrescue.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.List;

/**
 * Today's offers created from templates in one request. Every line repeats the quantities and
 * the pickup window explicitly: nothing is taken over from a previous day.
 *
 * @param publish true to make the offers visible right away, false to save them as drafts
 */
public record PublishOffersRequest(
        @NotEmpty @Size(max = 50) List<@Valid @NotNull Line> offers,
        boolean publish) {

    /**
     * Pickup times are times of day today, in the store's time zone.
     *
     * @param discountedPrice optional price for today only, approved by the owner (for example an
     *                        Autopilot suggestion); null keeps the product's usual price
     */
    public record Line(
            @NotNull Long templateId,
            @NotNull @Min(0) @Max(10_000) Integer quantityAvailable,
            @NotNull @Min(0) @Max(10_000) Integer payItForwardQuantity,
            @NotNull LocalTime pickupStart,
            @NotNull LocalTime pickupEnd,
            @DecimalMin("0.01") @Digits(integer = 8, fraction = 2) BigDecimal discountedPrice) {

        public Line(Long templateId, Integer quantityAvailable, Integer payItForwardQuantity,
                    LocalTime pickupStart, LocalTime pickupEnd) {
            this(templateId, quantityAvailable, payItForwardQuantity, pickupStart, pickupEnd, null);
        }
    }
}
