package com.foodrescue.domain;

/** What a store owner sees for an offer at a given moment (derived, never stored). */
public enum OfferState {
    DRAFT,
    ACTIVE,
    PAUSED,
    SOLD_OUT,
    EXPIRED
}
