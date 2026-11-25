package com.foodrescue.service;

import com.foodrescue.domain.FoodTag;
import com.foodrescue.dto.ListingSuggestion;
import com.foodrescue.dto.ListingSuggestionRequest;
import com.foodrescue.dto.ListingSuggestionRequest.Label;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** AI-assisted listings: turning image-model labels into an honest suggestion. */
class ListingSuggestionServiceTest {

    private final ListingSuggestionService service = new ListingSuggestionService();

    private ListingSuggestion suggest(String name, Label... labels) {
        return service.suggest(new ListingSuggestionRequest(List.of(labels), name));
    }

    @Test
    void breadPhotoSuggestsBakery() {
        ListingSuggestion s = suggest(null, new Label("French loaf", 0.78), new Label("bagel, beigel", 0.05));

        assertThat(s.recognised()).isTrue();
        assertThat(s.name()).isEqualTo("Bread");
        assertThat(s.tags()).containsExactly(FoodTag.BAKERY);
        assertThat(s.description()).startsWith("Bread left over from today").endsWith(ListingSuggestionService.SAFETY_NOTE);
        assertThat(s.seen()).containsExactly("French loaf (78%)"); // 5% is below the threshold
    }

    @Test
    void theOwnersNameIsKept() {
        ListingSuggestion s = suggest("Sourdough loaf", new Label("French loaf", 0.9));
        assertThat(s.name()).isEqualTo("Sourdough loaf");
        assertThat(s.description()).startsWith("Sourdough loaf left over from today");
    }

    @Test
    void severalFoodsGiveSeveralTypesAndTheMostLikelyName() {
        ListingSuggestion s = suggest(null, new Label("plate", 0.27), new Label("ice cream, icecream", 0.18),
                new Label("chocolate sauce, chocolate syrup", 0.35), new Label("meat loaf, meatloaf", 0.12));

        assertThat(s.name()).isEqualTo("Chocolate dessert");
        assertThat(s.tags()).containsExactlyInAnyOrder(FoodTag.SWEETS, FoodTag.MEAT_FISH, FoodTag.READY_MEALS);
        assertThat(s.seen()).containsExactly("Chocolate sauce (35%)", "Plate (27%)", "Ice cream (18%)", "Meat loaf (12%)");
    }

    @Test
    void somethingThatIsNotFoodGivesNoSuggestion() {
        ListingSuggestion s = suggest("Mystery box", new Label("electric ray, crampfish", 0.6), new Label("plate", 0.3));

        assertThat(s.recognised()).isFalse();
        assertThat(s.name()).isNull();
        assertThat(s.description()).isNull();
        assertThat(s.tags()).isEmpty();
        assertThat(s.seen()).containsExactly("Electric ray (60%)", "Plate (30%)");
    }

    @Test
    void neverClaimsDietaryLabelsOrFoodSafetyFacts() {
        for (String label : ListingSuggestionService.FOODS.keySet()) {
            ListingSuggestion s = suggest(null, new Label(label, 0.9));
            assertThat(s.tags()).as(label).noneMatch(FoodTag::isDietary);
            assertThat(s.description().toLowerCase())
                    .as(label)
                    .doesNotContain("vegan", "vegetarian", "gluten", "free from", "safe", "fresh", "best before", "use by",
                            "contains", "organic");
        }
    }
}
