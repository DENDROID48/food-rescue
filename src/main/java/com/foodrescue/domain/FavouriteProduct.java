package com.foodrescue.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serializable;
import java.time.Instant;

/** A customer's favourite saved product of a shop; it carries over to that product's offers every day. */
@Entity
@Table(name = "favourite_product")
@IdClass(FavouriteProduct.Key.class)
@Getter
@Setter
@NoArgsConstructor
public class FavouriteProduct {

    @Id
    private Long userId;

    @Id
    private Long templateId;

    @Column(nullable = false)
    private Instant createdAt;

    public FavouriteProduct(Long userId, Long templateId, Instant createdAt) {
        this.userId = userId;
        this.templateId = templateId;
        this.createdAt = createdAt;
    }

    public record Key(Long userId, Long templateId) implements Serializable {
        public Key() {
            this(null, null);
        }
    }
}
