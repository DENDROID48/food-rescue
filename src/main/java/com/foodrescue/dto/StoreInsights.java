package com.foodrescue.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * A store's results over a period, from offers that ended in it. Percentages are null when there
 * is nothing to divide by; audience numbers are null when below 5, so no individual customer can
 * be singled out.
 */
public record StoreInsights(
        LocalDate from,
        LocalDate to,
        String timeZone,
        Totals totals,
        List<Day> daily,
        List<Product> products,
        Timing timing,
        Drops drops,
        Audience audience,
        List<Recommendation> recommendations,
        List<ForecastLine> forecast) {

    /**
     * @param offered        units put on offer (for sale and free)
     * @param collected      units handed over (sold and donated)
     * @param leftover       units still on the shelf when their offer ended
     * @param noShows        units reserved but never collected
     * @param takings        what customers paid for collected units (paid at the shop)
     * @param customerSavings what collected units would have cost at full price, minus what was paid
     * @param kgRescued      weight of collected units that have a weight; null when none has
     * @param weightCoverage share of collected units that have a weight, in percent
     */
    public record Totals(long offered, long collected, long donated, long leftover, long noShows,
                         Integer sellThroughPercent, Integer noShowPercent, BigDecimal takings,
                         BigDecimal customerSavings, Double kgRescued, int weightCoverage, int offers) {
    }

    public record Day(LocalDate date, long offered, long collected, long leftover) {
    }

    /** @param flag a short warning ("Often left over", "Sells out", "Many no-shows") or null */
    public record Product(Long templateId, String name, int days, long offered, long collected, long leftover,
                          int soldOutDays, Integer sellThroughPercent, Integer noShowPercent, String flag) {
    }

    /**
     * @param reservationsByHour reservations per local hour of the day (24 entries)
     * @param pickupsByQuarter   pickups per quarter of the pickup window (4 entries: first … last)
     */
    public record Timing(int[] reservationsByHour, int[] pickupsByQuarter, int pickupsMeasured) {
    }

    /**
     * @param minutesToSellOut median time from launch to the last unit, over drops that sold out
     * @param alerted          customers alerted about these drops
     * @param alertedWhoReserved how many of them then reserved from that drop
     */
    public record Drops(int launched, long offered, long collected, Integer sellThroughPercent,
                        Integer minutesToSellOut, int alerted, int alertedWhoReserved) {
    }

    /** Counts only; null means "fewer than 5". */
    public record Audience(Integer followers, Integer favouriteFans, Integer dropAlertReach) {
    }

    /**
     * @param action what the app can open for it: SETUP, EDIT_PRODUCT, REMINDER, or null
     */
    public record Recommendation(String type, String title, String evidence, String advice, String action,
                                 Long templateId) {
    }

    /**
     * @param days          the next 7 days; empty when there is not enough history
     * @param backtestHits  past days the method would have predicted within ±1 unit (or 20%)
     * @param backtestDays  past days it was checked on
     * @param oftenSoldOut  demand may be higher than shown, because it often sold out
     */
    public record ForecastLine(Long templateId, String name, int historyDays, List<ForecastDay> days,
                               int backtestHits, int backtestDays, boolean oftenSoldOut) {
    }

    public record ForecastDay(LocalDate date, int low, int expected, int high) {
    }
}
