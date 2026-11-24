package com.foodrescue.dto;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/**
 * Suggestions for today's offers, one line per product. Nothing here is published: the owner
 * reviews the numbers in the "Set up today's offers" sheet and confirms them.
 *
 * @param date    today in the store's time zone
 * @param weekday today's weekday, which the history is weighted towards
 */
public record AutopilotPlan(LocalDate date, DayOfWeek weekday, List<Line> lines) {

    /**
     * @param quantity        suggested units for sale, or null when there is no history to go on
     * @param payItForward    free units used last time (0 without history)
     * @param suggestedPrice  a lower price worth trying, or null to keep the usual price; it is
     *                        only used if the owner explicitly applies it
     * @param confidence      NONE, LOW, MEDIUM or HIGH, from the number of past days
     * @param reasons         plain-language explanations shown next to the numbers
     */
    public record Line(Long templateId, Integer quantity, int payItForward, LocalTime pickupStart,
                       LocalTime pickupEnd, BigDecimal suggestedPrice, int daysOfHistory,
                       String confidence, List<String> reasons) {
    }
}
