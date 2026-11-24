package com.foodrescue.domain;

/**
 * What the store decided about an offer. "Sold out" and "expired" are not stored here: they
 * follow from the stock counters and the clock, see {@link FoodItem#state(java.time.Instant)}.
 */
public enum OfferStatus {
    /** Prepared but not visible to customers yet. */
    DRAFT,
    /** Visible to customers and can be reserved. */
    ACTIVE,
    /** Temporarily hidden by the store; existing reservations can still be collected. */
    PAUSED
}
