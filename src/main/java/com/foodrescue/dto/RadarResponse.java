package com.foodrescue.dto;

import java.util.List;
import java.util.Set;

/**
 * Rescue Radar for one customer and place.
 *
 * @param personalised         false when the customer turned recommendations off
 * @param picks                the best matches, best first, each with the reasons it was picked
 * @param favouriteStoreIds    to draw filled hearts on shop names
 * @param favouriteTemplateIds to draw filled hearts on products
 */
public record RadarResponse(boolean personalised, List<Pick> picks, Set<Long> favouriteStoreIds,
                            Set<Long> favouriteTemplateIds) {

    public record Pick(Long itemId, int score, List<String> reasons) {
    }
}
