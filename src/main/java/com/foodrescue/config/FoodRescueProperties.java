package com.foodrescue.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Typed business settings, read from the {@code foodrescue} section of application.yml.
 * Changing a rule (for example the daily free claim limit) then needs no code change.
 *
 * @param maxSearchRadiusKm            largest radius a client may request
 * @param reservationWindowMinutes     how long a consumer has to collect a reserved item
 * @param payItForwardDailyLimit       free claims allowed per user per rolling 24 hours
 * @param maxQuantityPerReservation    upper bound on units in one paid reservation
 */
@ConfigurationProperties(prefix = "foodrescue")
public record FoodRescueProperties(
        double maxSearchRadiusKm,
        int reservationWindowMinutes,
        int payItForwardDailyLimit,
        int maxQuantityPerReservation) {
}
