package com.foodrescue.domain;

/** Why a notification was sent. DROP and FAVOURITE count towards a customer's daily limit. */
public enum NotificationKind {
    DROP, FAVOURITE, DIGEST, TEST, REMINDER;

    public boolean countsTowardsLimit() {
        return this == DROP || this == FAVOURITE;
    }
}
