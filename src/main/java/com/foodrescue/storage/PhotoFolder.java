package com.foodrescue.storage;

/** The two kinds of photos, each kept in its own sub-folder of the uploads folder. */
public enum PhotoFolder {

    /** Photos of offers (food items), named after the item id. */
    FOOD_ITEMS("food-items"),

    /** Photos of saved products (templates), named after the template id. */
    TEMPLATES("templates");

    private final String folderName;

    PhotoFolder(String folderName) {
        this.folderName = folderName;
    }

    public String folderName() {
        return folderName;
    }
}
