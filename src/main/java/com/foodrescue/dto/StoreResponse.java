package com.foodrescue.dto;

import com.foodrescue.domain.Store;

/** Public view of a store. */
public record StoreResponse(
        Long id,
        String name,
        String address,
        double latitude,
        double longitude,
        boolean active,
        String timeZone) {

    public static StoreResponse from(Store store) {
        return new StoreResponse(
                store.getId(),
                store.getName(),
                store.getAddress(),
                store.getLatitude(),
                store.getLongitude(),
                store.isActive(),
                store.getTimeZone());
    }
}
