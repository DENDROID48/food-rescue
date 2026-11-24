package com.foodrescue.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Records that a product template has a photo, and its image type; the picture is a file like {@link FoodItemPhoto}. */
@Entity
@Table(name = "product_template_photo")
@Getter
@Setter
@NoArgsConstructor
public class ProductTemplatePhoto {

    /** Same value as the id of the template. */
    @Id
    private Long templateId;

    @Column(nullable = false, length = 50)
    private String contentType;
}
