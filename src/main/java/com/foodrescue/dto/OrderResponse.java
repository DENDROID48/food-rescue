package com.foodrescue.dto;

import com.foodrescue.domain.Order;
import com.foodrescue.domain.OrderStatus;
import com.foodrescue.domain.OrderType;

import java.math.BigDecimal;
import java.time.Instant;

/** Confirmation returned after a successful reservation or free claim. */
public record OrderResponse(
        Long orderId,
        Long foodItemId,
        String foodItemName,
        String storeName,
        String storeAddress,
        int quantity,
        BigDecimal totalPrice,
        OrderType type,
        OrderStatus status,
        String pickupCode,
        Instant pickupDeadline) {

    /**
     * Must be called while the transaction is still open, because it touches lazily loaded
     * relations (foodItem and store).
     */
    public static OrderResponse from(Order order) {
        return new OrderResponse(
                order.getId(),
                order.getFoodItem().getId(),
                order.getFoodItem().getName(),
                order.getFoodItem().getStore().getName(),
                order.getFoodItem().getStore().getAddress(),
                order.getQuantity(),
                order.getTotalPrice(),
                order.getType(),
                order.getStatus(),
                order.getPickupCode(),
                order.getPickupDeadline());
    }
}
