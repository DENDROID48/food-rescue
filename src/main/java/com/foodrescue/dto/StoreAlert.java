package com.foodrescue.dto;

/**
 * Something on today's dashboard that needs the owner's attention.
 *
 * @param type   LOW_STOCK, SOLD_OUT_EARLY, LEFTOVERS_ENDING or DRAFTS_WAITING
 * @param level  "warning" or "info"
 * @param itemId the offer it is about, if any
 */
public record StoreAlert(String type, String level, String message, Long itemId) {
}
