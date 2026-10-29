package com.foodrescue.dto;

import com.foodrescue.domain.FoodItem;

import java.math.BigDecimal;
import java.time.Instant;

/** Store owner view of one of their items, including sold out and expired ones. */
public record FoodItemResponse(
        Long id,
        Long storeId,
        String name,
        String description,
        BigDecimal originalPrice,
        BigDecimal discountedPrice,
        int discountPercent,
        int quantityAvailable,
        int payItForwardQuantity,
        Instant expiresAt,
        String photoUrl) {

    /**
     * Link to the item's photo, or null without one. The version number changes whenever the
     * item changes, so browsers may cache a photo for long and still see a replaced one.
     */
    public static String photoUrl(FoodItem item) {
        return item.isHasPhoto() ? "/api/v1/food-items/" + item.getId() + "/photo?v=" + item.getVersion() : null;
    }

    public static FoodItemResponse from(FoodItem item) {
        return new FoodItemResponse(
                item.getId(),
                item.getStore().getId(),
                item.getName(),
                item.getDescription(),
                item.getOriginalPrice(),
                item.getDiscountedPrice(),
                item.discountPercent(),
                item.getQuantityAvailable(),
                item.getPayItForwardQuantity(),
                item.getExpiresAt(),
                photoUrl(item));
    }
}
