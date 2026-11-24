package com.foodrescue.dto;

import com.foodrescue.domain.Notification;
import com.foodrescue.domain.OfferState;

import java.time.Instant;
import java.util.List;

/**
 * @param live true while the offer it points to can still be reserved
 */
public record NotificationResponse(Long id, com.foodrescue.domain.NotificationKind kind, String title, String body, Instant createdAt, boolean read,
                                   Long foodItemId, boolean live, String storeName, Double storeLatitude,
                                   Double storeLongitude) {

    public static NotificationResponse from(Notification n, Instant now) {
        var item = n.getFoodItem();
        var store = n.getStore();
        return new NotificationResponse(n.getId(), n.getKind(), n.getTitle(), n.getBody(), n.getCreatedAt(), n.getReadAt() != null,
                item == null ? null : item.getId(),
                item != null && item.state(now) == OfferState.ACTIVE && store != null && store.isActive(),
                store == null ? null : store.getName(),
                store == null ? null : store.getLatitude(),
                store == null ? null : store.getLongitude());
    }

    public record Inbox(long unread, List<NotificationResponse> items) {
    }
}
