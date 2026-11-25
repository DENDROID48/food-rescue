package com.foodrescue.dto;

import com.foodrescue.domain.FoodTag;

import java.util.List;
import java.util.Set;

/**
 * A suggested name, description and food types for a product, for the owner to check and approve.
 * Nothing is saved: the owner decides what goes into the listing.
 *
 * @param recognised false when the photo showed nothing the app knows as food
 * @param seen       what the model saw, with its confidence, so the owner can judge the guess
 */
public record ListingSuggestion(boolean recognised, String name, String description, Set<FoodTag> tags,
                                List<String> seen) {}
