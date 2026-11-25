package com.foodrescue.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings for rescue streaks, achievements and the community challenge, read from the
 * {@code foodrescue.gamification} section of application.yml.
 *
 * @param monthlyChallengeUnits how many items the whole community tries to rescue each month
 */
@ConfigurationProperties(prefix = "foodrescue.gamification")
public record GamificationProperties(int monthlyChallengeUnits) {

    public GamificationProperties {
        if (monthlyChallengeUnits <= 0) {
            monthlyChallengeUnits = 400;
        }
    }
}
