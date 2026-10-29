package com.foodrescue.dto;

import jakarta.validation.constraints.NotNull;

/** Admin switch for a store: {@code {"active": false}} suspends it, true reactivates it. */
public record StoreStatusRequest(@NotNull Boolean active) {
}
