package com.foodrescue.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The no-show rule, read from {@code foodrescue.no-shows} in application.yml. A no-show is a
 * reservation whose pickup time ended without the food being collected (cancelling in time never
 * counts). They are counted over a rolling window, so limits lift by themselves.
 *
 * @param windowDays how far back no-shows count
 * @param limitAt    from this many no-shows, only one open reservation at a time
 * @param pauseAt    from this many, no new reservations or free claims until the count drops
 */
@ConfigurationProperties(prefix = "foodrescue.no-shows")
public record NoShowProperties(int windowDays, int limitAt, int pauseAt) {

    public NoShowProperties {
        if (windowDays <= 0) windowDays = 30;
        if (limitAt <= 0) limitAt = 3;
        if (pauseAt <= limitAt) pauseAt = Math.max(limitAt + 1, 5);
    }
}
