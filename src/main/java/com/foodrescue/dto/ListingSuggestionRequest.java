package com.foodrescue.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * What the image model in the owner's browser recognised in a product photo.
 *
 * @param labels the model's best guesses, most likely first
 * @param name   the product name the owner already typed, if any
 */
public record ListingSuggestionRequest(
        @NotNull @Size(max = 10) List<@Valid Label> labels,
        @Size(max = 150) String name) {

    /** One guess of the model, for example "French loaf" with probability 0.78. */
    public record Label(@NotBlank @Size(max = 200) String className,
                        @DecimalMin("0.0") @DecimalMax("1.0") double probability) {}
}
