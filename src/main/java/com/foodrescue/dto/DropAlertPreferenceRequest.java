package com.foodrescue.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * @param radiusKm  how far from the chosen place a drop may be
 * @param maxPerDay at most this many drop alerts in any 24 hours
 */
public record DropAlertPreferenceRequest(
        boolean enabled,
        @NotNull @DecimalMin("-90") @DecimalMax("90") Double latitude,
        @NotNull @DecimalMin("-180") @DecimalMax("180") Double longitude,
        @NotNull @DecimalMin("0.5") @DecimalMax("10") Double radiusKm,
        @NotNull @Min(1) @Max(10) Integer maxPerDay) {
}
