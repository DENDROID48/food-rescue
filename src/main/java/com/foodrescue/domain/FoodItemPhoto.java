package com.foodrescue.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * The photo of one food item, kept in its own table so that searching and listing items never
 * loads image bytes. Storing photos in the database needs no extra file storage to deploy; move
 * them to object storage (S3 or similar) if the database grows too large.
 */
@Entity
@Table(name = "food_item_photo")
@Getter
@Setter
@NoArgsConstructor
public class FoodItemPhoto {

    /** Same value as the id of the food item; an item has at most one photo. */
    @Id
    private Long foodItemId;

    @Column(nullable = false, length = 50)
    private String contentType;

    @Column(nullable = false)
    private byte[] data;
}
