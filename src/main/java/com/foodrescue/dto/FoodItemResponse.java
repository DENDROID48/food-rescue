package com.foodrescue.dto;

import com.foodrescue.domain.FoodItem;
import com.foodrescue.domain.FoodTag;
import com.foodrescue.domain.OfferKind;
import com.foodrescue.domain.OfferState;
import com.foodrescue.domain.OfferStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Set;

/**
 * An offer as the store owner sees it. {@code status} is what the store decided (draft, active,
 * paused); {@code state} adds what follows from the clock and the stock (sold out, expired).
 */
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
        String photoUrl,
        OfferKind kind,
        OfferStatus status,
        OfferState state,
        Instant pickupStartsAt,
        Long templateId,
        /** True for a Rescue Drop. */
        boolean rescueDrop,
        Set<FoodTag> tags,
        Integer unitWeightGrams) {

    public static String photoUrl(FoodItem item) {
        return item.isHasPhoto() ? "/api/v1/food-items/" + item.getId() + "/photo?v=" + item.getVersion() : null;
    }

    public static FoodItemResponse from(FoodItem item) {
        return from(item, Instant.now());
    }

    public static FoodItemResponse from(FoodItem item, Instant now) {
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
                photoUrl(item),
                item.getKind(),
                item.getStatus(),
                item.state(now),
                item.getPickupStartsAt(),
                // Reading the id of a lazy association does not load the template.
                item.getTemplate() == null ? null : item.getTemplate().getId(),
                item.isRescueDrop(),
                item.getTags(),
                item.getUnitWeightGrams());
    }
}
