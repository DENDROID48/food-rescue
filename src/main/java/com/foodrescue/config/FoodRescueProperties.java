package com.foodrescue.config;

import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Typed business settings, read from the {@code foodrescue} section of application.yml.
 * Changing a rule (for example the daily free claim limit) then needs no code change.
 *
 * @param maxSearchRadiusKm            largest radius a client may request
 * @param reservationWindowMinutes     how long a consumer has to collect a reserved item
 * @param payItForwardDailyLimit       free claims allowed per user per rolling 24 hours
 * @param maxQuantityPerReservation    upper bound on units in one paid reservation
 *
 * <p>{@code @Validated}: a zero or negative value stops the program at startup with a message
 * naming the setting, instead of breaking a rule later.</p>
 */
@ConfigurationProperties(prefix = "foodrescue")
@Validated
public record FoodRescueProperties(
        @Positive double maxSearchRadiusKm,
        @Positive int reservationWindowMinutes,
        @Positive int payItForwardDailyLimit,
        @Positive int maxQuantityPerReservation) {
}
