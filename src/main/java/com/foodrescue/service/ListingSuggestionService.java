package com.foodrescue.service;

import com.foodrescue.domain.FoodTag;
import com.foodrescue.dto.ListingSuggestion;
import com.foodrescue.dto.ListingSuggestionRequest;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static java.util.Map.entry;

/**
 * Turns what the free image model in the owner's browser (MobileNet, trained on ImageNet) saw in
 * a product photo into a suggested name, description and food types.
 *
 * <p>Rules that keep the suggestion honest:</p>
 * <ul>
 *   <li>It is only a suggestion: nothing is saved, and the owner fills in the form only when they choose to.</li>
 *   <li>Only food categories are suggested. Dietary labels (vegetarian, vegan) say what food is free of,
 *       which a photo cannot show, so the owner must choose them.</li>
 *   <li>The description never mentions allergens, ingredients, freshness or how long the food keeps. It
 *       tells customers to ask the shop instead.</li>
 *   <li>Guesses below {@link #MIN_PROBABILITY} are ignored.</li>
 * </ul>
 */
@Service
public class ListingSuggestionService {

    static final double MIN_PROBABILITY = 0.10;
    static final String SAFETY_NOTE = "Ask us about ingredients and allergens.";

    /** What an ImageNet label means for a listing. */
    record Food(Set<FoodTag> tags, String name) {
        Food(FoodTag tag, String name) { this(EnumSet.of(tag), name); }
    }

    /** ImageNet labels (their first name, lower case) that show food, and what they mean here. */
    static final Map<String, Food> FOODS = Map.ofEntries(
            entry("french loaf", new Food(FoodTag.BAKERY, "Bread")),
            entry("bagel", new Food(FoodTag.BAKERY, "Bagels")),
            entry("pretzel", new Food(FoodTag.BAKERY, "Pretzels")),
            entry("bakery", new Food(FoodTag.BAKERY, "Baked goods")),
            entry("dough", new Food(FoodTag.BAKERY, "Dough")),
            entry("chocolate sauce", new Food(FoodTag.SWEETS, "Chocolate dessert")),
            entry("ice cream", new Food(FoodTag.SWEETS, "Ice cream")),
            entry("ice lolly", new Food(FoodTag.SWEETS, "Ice lollies")),
            entry("trifle", new Food(FoodTag.SWEETS, "Dessert")),
            entry("confectionery", new Food(FoodTag.SWEETS, "Sweets")),
            entry("pizza", new Food(FoodTag.READY_MEALS, "Pizza")),
            entry("burrito", new Food(FoodTag.READY_MEALS, "Burritos")),
            entry("hotdog", new Food(FoodTag.READY_MEALS, "Hot dogs")),
            entry("cheeseburger", new Food(FoodTag.READY_MEALS, "Burgers")),
            entry("potpie", new Food(FoodTag.READY_MEALS, "Pot pie")),
            entry("carbonara", new Food(FoodTag.READY_MEALS, "Pasta")),
            entry("consomme", new Food(FoodTag.READY_MEALS, "Soup")),
            entry("soup bowl", new Food(FoodTag.READY_MEALS, "Soup")),
            entry("hot pot", new Food(FoodTag.READY_MEALS, "Hot pot")),
            entry("mashed potato", new Food(FoodTag.READY_MEALS, "Mashed potatoes")),
            entry("guacamole", new Food(FoodTag.READY_MEALS, "Guacamole")),
            entry("wok", new Food(FoodTag.READY_MEALS, "Cooked dish")),
            entry("dutch oven", new Food(FoodTag.READY_MEALS, "Cooked dish")),
            entry("frying pan", new Food(FoodTag.READY_MEALS, "Cooked dish")),
            entry("crock pot", new Food(FoodTag.READY_MEALS, "Cooked dish")),
            entry("meat loaf", new Food(EnumSet.of(FoodTag.MEAT_FISH, FoodTag.READY_MEALS), "Meatloaf")),
            entry("butcher shop", new Food(FoodTag.MEAT_FISH, "Meat")),
            entry("granny smith", new Food(FoodTag.FRUIT_VEG, "Apples")),
            entry("strawberry", new Food(FoodTag.FRUIT_VEG, "Strawberries")),
            entry("orange", new Food(FoodTag.FRUIT_VEG, "Oranges")),
            entry("lemon", new Food(FoodTag.FRUIT_VEG, "Lemons")),
            entry("fig", new Food(FoodTag.FRUIT_VEG, "Figs")),
            entry("pineapple", new Food(FoodTag.FRUIT_VEG, "Pineapples")),
            entry("banana", new Food(FoodTag.FRUIT_VEG, "Bananas")),
            entry("jackfruit", new Food(FoodTag.FRUIT_VEG, "Jackfruit")),
            entry("custard apple", new Food(FoodTag.FRUIT_VEG, "Custard apples")),
            entry("pomegranate", new Food(FoodTag.FRUIT_VEG, "Pomegranates")),
            entry("head cabbage", new Food(FoodTag.FRUIT_VEG, "Cabbage")),
            entry("broccoli", new Food(FoodTag.FRUIT_VEG, "Broccoli")),
            entry("cauliflower", new Food(FoodTag.FRUIT_VEG, "Cauliflower")),
            entry("zucchini", new Food(FoodTag.FRUIT_VEG, "Courgettes")),
            entry("spaghetti squash", new Food(FoodTag.FRUIT_VEG, "Squash")),
            entry("acorn squash", new Food(FoodTag.FRUIT_VEG, "Squash")),
            entry("butternut squash", new Food(FoodTag.FRUIT_VEG, "Squash")),
            entry("cucumber", new Food(FoodTag.FRUIT_VEG, "Cucumbers")),
            entry("artichoke", new Food(FoodTag.FRUIT_VEG, "Artichokes")),
            entry("bell pepper", new Food(FoodTag.FRUIT_VEG, "Peppers")),
            entry("cardoon", new Food(FoodTag.FRUIT_VEG, "Cardoons")),
            entry("mushroom", new Food(FoodTag.FRUIT_VEG, "Mushrooms")),
            entry("corn", new Food(FoodTag.FRUIT_VEG, "Sweetcorn")),
            entry("ear", new Food(FoodTag.FRUIT_VEG, "Sweetcorn")),
            entry("grocery store", new Food(FoodTag.GROCERIES, "Groceries")));

    public ListingSuggestion suggest(ListingSuggestionRequest request) {
        List<ListingSuggestionRequest.Label> likely = request.labels().stream()
                .filter(l -> l.probability() >= MIN_PROBABILITY)
                .sorted(Comparator.comparingDouble(ListingSuggestionRequest.Label::probability).reversed())
                .toList();

        List<String> seen = new ArrayList<>();
        Set<FoodTag> tags = EnumSet.noneOf(FoodTag.class);
        Food best = null;
        for (ListingSuggestionRequest.Label label : likely) {
            String key = firstName(label.className());
            seen.add(capitalise(key) + " (" + Math.round(label.probability() * 100) + "%)");
            Food food = FOODS.get(key);
            if (food == null) continue;
            if (best == null) best = food;
            tags.addAll(food.tags());
        }
        tags.removeIf(FoodTag::isDietary); // never claimed from a photo (the table has none, this keeps it so)

        if (best == null) return new ListingSuggestion(false, null, null, Set.of(), seen);
        String typed = request.name() == null ? "" : request.name().strip();
        String name = typed.isEmpty() ? best.name() : typed;
        return new ListingSuggestion(true, name, description(name), tags, seen);
    }

    /** A plain description that states nothing a photo cannot show. */
    static String description(String name) {
        return name + " left over from today, at a lower price so it doesn't go to waste. " + SAFETY_NOTE;
    }

    /** "bagel, beigel" becomes "bagel": ImageNet lists synonyms after the first name. */
    static String firstName(String className) {
        return className.split(",")[0].strip().toLowerCase(Locale.ROOT);
    }

    private static String capitalise(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
