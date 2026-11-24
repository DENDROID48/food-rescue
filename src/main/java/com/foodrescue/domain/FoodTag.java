package com.foodrescue.domain;

/**
 * Food types a shop can give a product. They are the shop's own statement, shown as such; the
 * app never adds or guesses allergen or food-safety information.
 */
public enum FoodTag {
    BAKERY, FRUIT_VEG, READY_MEALS, DAIRY, MEAT_FISH, SWEETS, GROCERIES, VEGETARIAN, VEGAN;

    /** Says what the food is, not what it is free of. */
    public boolean isDietary() {
        return this == VEGETARIAN || this == VEGAN;
    }
}
