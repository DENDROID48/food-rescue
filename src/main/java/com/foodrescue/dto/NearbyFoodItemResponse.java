package com.foodrescue.dto;

import com.foodrescue.domain.FoodItem;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * What the mobile app receives for each search result. A DTO (data transfer object) is used
 * instead of returning the entity directly so that internal fields (version, owner, lazy
 * relations) never leak into the public API and the API can evolve independently of the
 * database schema.
 */
public record NearbyFoodItemResponse(
        Long id,
        String name,
        String description,
        BigDecimal originalPrice,
        BigDecimal discountedPrice,
        int discountPercent,
        int quantityAvailable,
        int payItForwardQuantity,
        Instant expiresAt,
        Long storeId,
        String storeName,
        String storeAddress,
        double storeLatitude,
        double storeLongitude,
        double distanceKm,
        String photoUrl) {

    public static NearbyFoodItemResponse from(FoodItem item, double distanceKm) {
        return new NearbyFoodItemResponse(
                item.getId(),
                item.getName(),
                item.getDescription(),
                item.getOriginalPrice(),
                item.getDiscountedPrice(),
                item.discountPercent(),
                item.getQuantityAvailable(),
                item.getPayItForwardQuantity(),
                item.getExpiresAt(),
                item.getStore().getId(),
                item.getStore().getName(),
                item.getStore().getAddress(),
                item.getStore().getLatitude(),
                item.getStore().getLongitude(),
                // One decimal is plenty for a distance shown on a phone screen.
                Math.round(distanceKm * 10.0) / 10.0,
                FoodItemResponse.photoUrl(item));
    }
}
