package com.foodrescue.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

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
        @NotNull @Future Instant expiresAt) {
}
