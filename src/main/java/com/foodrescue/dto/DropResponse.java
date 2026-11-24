package com.foodrescue.dto;

/** @param notified customers who got an in-app alert about this drop */
public record DropResponse(FoodItemResponse offer, int notified) {
}
