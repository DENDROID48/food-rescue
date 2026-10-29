package com.foodrescue.domain;

/**
 * Life cycle of an {@link Order}.
 *
 * <pre>
 *   RESERVED ---> PICKED_UP      (consumer showed the pickup code at the store)
 *      |
 *      +--------> CANCELLED      (consumer cancelled in time)
 *      |
 *      +--------> EXPIRED        (pickup deadline passed, stock was released)
 * </pre>
 */
public enum OrderStatus {
    RESERVED,
    PICKED_UP,
    CANCELLED,
    EXPIRED
}
