package com.foodrescue.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * A customer's personal dashboard: what they rescued and saved, their weekly streak, their
 * achievements and this month's community challenge. Only collected orders count.
 *
 * @param kgRescued          weight of the collected food whose shop stated a weight per unit
 * @param unitsWithoutWeight collected units whose weight is unknown, so they are not in kgRescued
 */
public record ImpactResponse(int rescues, int units, BigDecimal moneySaved, double kgRescued, int unitsWithoutWeight,
                             int shops, Streak streak, List<AchievementView> achievements, Challenge challenge) {

    /** Weeks (Monday to Sunday) in a row with at least one collected order. */
    public record Streak(int currentWeeks, int bestWeeks, boolean rescuedThisWeek) {}

    /** One achievement, with how far the customer is towards it. */
    public record AchievementView(String code, String title, String description, boolean unlocked,
                                  LocalDate unlockedOn, String progressText, int percent) {}

    /** The community's goal for the current month and the customer's share of it. */
    public record Challenge(String title, long targetUnits, long communityUnits, long yourUnits,
                            int percent, LocalDate endsOn, int daysLeft) {}
}
