package com.foodrescue.dto;

import jakarta.validation.constraints.Min;

/**
 * JSON body of the reservation endpoint, for example {@code {"quantity": 2}}.
 * A missing quantity defaults to 1 (see the compact constructor).
 */
public record ReserveRequest(@Min(value = 1, message = "quantity must be at least 1") Integer quantity) {

    public ReserveRequest {
        if (quantity == null) {
            quantity = 1;
        }
    }
}
