package com.foodrescue.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * JSON body for registering or editing a store, for example
 * {@code {"name": "Corner Bakery", "address": "Piata Victoriei 1, Timisoara"}}.
 *
 * <p>Coordinates are optional: without them the server looks the address up on the map. The
 * web page sends them only when the owner moved the pin to correct the position.</p>
 */
public record StoreRequest(
        @NotBlank @Size(max = 150) String name,
        @NotBlank @Size(max = 255) String address,
        @DecimalMin("-90") @DecimalMax("90") Double latitude,
        @DecimalMin("-180") @DecimalMax("180") Double longitude) {
}
