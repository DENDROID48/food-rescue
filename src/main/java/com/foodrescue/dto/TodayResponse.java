package com.foodrescue.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/**
 * Everything the "Today" dashboard of a store needs in one call.
 *
 * @param date      today in the store's time zone
 * @param offers    offers that run today (never ones that ended before today started)
 * @param templates the store's reusable products, without any quantity
 */
public record TodayResponse(
        LocalDate date,
        String timeZone,
        Instant now,
        Insights insights,
        Reminder reminder,
        List<FoodItemResponse> offers,
        List<TemplateResponse> templates,
        /** Things that need attention now: low stock, leftovers about to go to waste, drafts. */
        List<StoreAlert> alerts,
        /** A Smart Bundle proposal for leftovers that end soon, or null. */
        BundleSuggestion bundleSuggestion) {

    /**
     * Figures computed from the database.
     *
     * @param availableUnits     units customers can reserve now (paid)
     * @param availableFreeUnits Pay-It-Forward units customers can claim now
     * @param reservedUnits      units reserved and waiting to be collected
     * @param collectedUnits     units handed over today (paid and free)
     * @param donatedUnits       free Pay-It-Forward units handed over today
     * @param liveOffers         offers customers can see and reserve now
     */
    public record Insights(long availableUnits, long availableFreeUnits, long reservedUnits,
                           long collectedUnits, long donatedUnits, long liveOffers) {
    }

    /** @param due true when the dashboard should show the reminder now */
    public record Reminder(boolean enabled, LocalTime time, boolean due) {
    }
}
