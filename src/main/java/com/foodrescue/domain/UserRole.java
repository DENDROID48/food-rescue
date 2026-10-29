package com.foodrescue.domain;

/**
 * Coarse permission group of a user. Spring Security (not included yet) will map this to
 * authorities such as ROLE_CONSUMER.
 */
public enum UserRole {
    /** Buys discounted food or claims free Pay-It-Forward items. */
    CONSUMER,
    /** Owns one or more stores and publishes surplus food. */
    STORE_OWNER,
    /** Platform operator (verifies beneficiaries, moderates stores). */
    ADMIN
}
