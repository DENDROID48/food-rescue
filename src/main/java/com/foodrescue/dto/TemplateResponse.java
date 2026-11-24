package com.foodrescue.dto;

import com.foodrescue.domain.FoodTag;
import com.foodrescue.domain.OfferKind;
import com.foodrescue.domain.ProductTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalTime;
import java.util.Set;

public record TemplateResponse(
        Long id,
        String name,
        String description,
        OfferKind kind,
        BigDecimal originalPrice,
        BigDecimal discountedPrice,
        int discountPercent,
        LocalTime pickupStart,
        LocalTime pickupEnd,
        String photoUrl,
        Set<FoodTag> tags,
        Integer unitWeightGrams) {

    public static TemplateResponse from(ProductTemplate t) {
        int percent = t.getOriginalPrice().signum() == 0 ? 0 : t.getOriginalPrice().subtract(t.getDiscountedPrice())
                .multiply(BigDecimal.valueOf(100)).divide(t.getOriginalPrice(), 0, RoundingMode.HALF_UP).intValue();
        return new TemplateResponse(t.getId(), t.getName(), t.getDescription(), t.getKind(),
                t.getOriginalPrice(), t.getDiscountedPrice(), percent, t.getPickupStart(), t.getPickupEnd(),
                t.isHasPhoto() ? "/api/v1/template-photos/" + t.getId() + "?v=" + t.getVersion() : null,
                t.getTags(),
                t.getUnitWeightGrams());
    }
}
