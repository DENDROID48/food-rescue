package com.foodrescue.domain;

/**
 * Where a consumer stands in the verification for free Pay-It-Forward claims.
 *
 * <pre>
 *   NOT_REQUESTED --(consumer asks)--> PENDING --(admin approves)--> APPROVED
 *                                         |
 *                                         +----(admin rejects)---> REJECTED --(asks again)--> PENDING
 * </pre>
 *
 * Only the outcome is stored, never the reason or any documents: verification happens
 * outside the app (for example through a partner charity or social services office).
 */
public enum EligibilityStatus {
    NOT_REQUESTED,
    PENDING,
    APPROVED,
    REJECTED
}
