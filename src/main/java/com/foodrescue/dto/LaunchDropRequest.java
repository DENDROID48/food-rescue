package com.foodrescue.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * Launch a Rescue Drop of a saved product, starting now.
 *
 * @param discountedPrice optional drop price; null keeps the product's usual price
 * @param alertCustomers  alert opted-in customers nearby
 */
public record LaunchDropRequest(
        @NotNull Long templateId,
        @NotNull @Min(0) @Max(500) Integer quantityAvailable,
        @Min(0) @Max(500) Integer payItForwardQuantity,
        @DecimalMin("0.01") @Digits(integer = 8, fraction = 2) BigDecimal discountedPrice,
        @NotNull @Min(10) @Max(240) Integer durationMinutes,
        boolean alertCustomers) {
}
