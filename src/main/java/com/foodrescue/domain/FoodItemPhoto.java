package com.foodrescue.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Records that a food item has a photo, and its image type. The picture itself is a file in the
 * uploads folder (see {@link com.foodrescue.storage.FilePhotoStorage}).
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
}
