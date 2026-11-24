package com.foodrescue.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serializable;
import java.time.Instant;

@Entity
@Table(name = "favourite_store")
@IdClass(FavouriteStore.Key.class)
@Getter
@Setter
@NoArgsConstructor
public class FavouriteStore {

    @Id
    private Long userId;

    @Id
    private Long storeId;

    @Column(nullable = false)
    private Instant createdAt;

    public FavouriteStore(Long userId, Long storeId, Instant createdAt) {
        this.userId = userId;
        this.storeId = storeId;
        this.createdAt = createdAt;
    }

    public record Key(Long userId, Long storeId) implements Serializable {
        public Key() {
            this(null, null);
        }
    }
}
