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
import java.util.List;

/**
 * A Smart Bundle: leftover units of today's offers turned into surprise bags, launched as a
 * Rescue Drop. The units are taken out of the source offers.
 *
 * @param bags  number of surprise bags the units are shared between
 * @param price price of one bag
 * @param name  optional name; a neutral default is used otherwise
 */
public record BundleDropRequest(
        @NotEmpty @Size(max = 20) List<@Valid @NotNull Component> components,
        @NotNull @Min(1) @Max(100) Integer bags,
        @NotNull @DecimalMin("0.01") @Digits(integer = 8, fraction = 2) BigDecimal price,
        @NotNull @Min(10) @Max(240) Integer durationMinutes,
        @Size(max = 150) String name,
        boolean alertCustomers) {

    public record Component(@NotNull Long offerId, @NotNull @Min(1) @Max(500) Integer units) {
    }
}
