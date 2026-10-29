package com.foodrescue.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/** JSON body the store sends when a customer collects food, for example {@code {"pickupCode": "048213"}}. */
public record PickupRequest(
        @NotBlank @Pattern(regexp = "\\d{6}", message = "pickupCode must be 6 digits") String pickupCode) {
}
