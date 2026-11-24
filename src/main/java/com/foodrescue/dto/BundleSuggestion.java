package com.foodrescue.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * A proposed Smart Bundle made of today's leftovers. Only a proposal: the owner can change every
 * number before launching it.
 *
 * @param bagValue what the contents of one bag would cost at the original prices
 */
public record BundleSuggestion(List<Component> components, int bags, BigDecimal price, BigDecimal bagValue,
                               int durationMinutes) {

    public record Component(Long offerId, String name, int units, int available, BigDecimal originalPrice) {
    }
}
