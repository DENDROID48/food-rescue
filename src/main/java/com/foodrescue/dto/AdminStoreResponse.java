package com.foodrescue.dto;

import com.foodrescue.domain.Store;

import java.time.Instant;

/** Admin view of a store, including who runs it. */
public record AdminStoreResponse(
        Long id,
        String name,
        String address,
        boolean active,
        Long ownerId,
        String ownerName,
        String ownerEmail,
        Instant createdAt) {

    /** The owner must be loaded (see StoreRepository.findAllByOrderByNameAsc). */
    public static AdminStoreResponse from(Store store) {
        return new AdminStoreResponse(
                store.getId(),
                store.getName(),
                store.getAddress(),
                store.isActive(),
                store.getOwner().getId(),
                store.getOwner().getFullName(),
                store.getOwner().getEmail(),
                store.getCreatedAt());
    }
}
