package com.foodrescue.domain;

import java.time.Instant;

/**
 * Something that stops being valid at a fixed moment: a {@link FoodItem} past its sell-by
 * time, or an {@link Order} whose pickup deadline has passed.
 */
public interface Expirable {

    /** The moment from which this is no longer valid. */
    Instant expiryMoment();

    /** True when {@code now} is at or after the expiry moment. */
    default boolean isExpiredAt(Instant now) {
        return !expiryMoment().isAfter(now);
    }
}
