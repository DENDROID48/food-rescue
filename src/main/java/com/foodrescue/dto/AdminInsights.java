package com.foodrescue.dto;

import java.util.List;

/**
 * The platform over the last 28 days, for administrators.
 *
 * @param customers customers who collected at least once
 * @param stores    stores that handed over food at least once
 * @param quiet     active stores that have not published for 7 days
 * @param attention stores with much unsold food or many no-shows
 */
public record AdminInsights(StoreInsights.Totals totals, int customers, int stores,
                            List<StoreLine> quiet, List<StoreLine> attention) {

    public record StoreLine(Long storeId, String name, String reason) {
    }
}
