package com.foodrescue.domain;

/**
 * Distinguishes a normal discounted purchase from a free donated item.
 */
public enum OrderType {
    /** Consumer pays the discounted price. */
    PURCHASE,
    /** Consumer receives the item for free from the Pay-It-Forward pool. */
    PAY_IT_FORWARD
}
