package com.foodrescue.service;

import com.foodrescue.domain.FoodItem;
import com.foodrescue.domain.FoodTag;
import com.foodrescue.domain.RadarProfile;
import com.foodrescue.dto.NearbyFoodItemResponse;
import com.foodrescue.dto.RadarResponse;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/** Rescue Radar scoring, quiet hours and bundle food types, without a database. */
class RadarServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-17T15:00:00Z");

    private static NearbyFoodItemResponse item(long id, long storeId, Long templateId, double km, Set<FoodTag> tags) {
        return new NearbyFoodItemResponse(id, "Item " + id, null, new BigDecimal("20.00"), new BigDecimal("8.00"), 60,
                3, 0, NOW.plus(Duration.ofHours(3)), storeId, "Shop " + storeId, "Street", 45.75, 21.22, km, null, null,
                null, false, templateId, tags, null);
    }

    private static RadarService.Signals signals(Set<Long> favStores, Set<Long> favProducts, Map<Long, Integer> storeVisits,
                                                Map<Long, Integer> productVisits, Set<FoodTag> interests) {
        return new RadarService.Signals(favStores, favProducts, storeVisits, productVisits, interests, false);
    }

    private static final RadarService.Signals NOTHING = signals(Set.of(), Set.of(), Map.of(), Map.of(), Set.of());

    @Test
    void nothingPersonalMeansNoPick() {
        assertThat(RadarService.score(item(1, 1, 10L, 0.5, Set.of(FoodTag.BAKERY)), NOTHING, NOW)).isNull();
    }

    @Test
    void favouritesComeFirstAndSayWhy() {
        RadarService.Signals s = signals(Set.of(2L), Set.of(10L), Map.of(), Map.of(), Set.of());
        List<RadarResponse.Pick> picks = RadarService.pick(List.of(
                item(1, 1, 10L, 1.0, Set.of()),    // favourite product
                item(2, 2, 20L, 0.3, Set.of()),    // favourite shop
                item(3, 3, 30L, 0.1, Set.of())),   // nothing personal
                s, NOW);

        assertThat(picks).extracting(RadarResponse.Pick::itemId).containsExactly(1L, 2L);
        assertThat(picks.get(0).reasons()).first().isEqualTo("One of your favourites");
        assertThat(picks.get(1).reasons()).first().isEqualTo("From a shop you follow");
    }

    @Test
    void historyAndInterestsAreExplained() {
        RadarService.Signals s = signals(Set.of(), Set.of(), Map.of(1L, 3), Map.of(), Set.of(FoodTag.FRUIT_VEG));

        RadarResponse.Pick shopRegular = RadarService.score(item(1, 1, null, 0.5, Set.of()), s, NOW);
        RadarResponse.Pick veg = RadarService.score(item(2, 2, null, 0.5, Set.of(FoodTag.FRUIT_VEG)), s, NOW);

        assertThat(shopRegular.reasons()).containsExactly("You've collected from here 3 times");
        assertThat(veg.reasons()).containsExactly("Matches your interest in fruit & veg");

        RadarService.Signals had = signals(Set.of(), Set.of(), Map.of(1L, 1), Map.of(10L, 1), Set.of());
        assertThat(RadarService.score(item(3, 1, 10L, 0.5, Set.of()), had, NOW).reasons()).containsExactly("You've had this once before");
    }

    @Test
    void nearerAndCheaperRanksHigherAmongEquals() {
        RadarService.Signals s = signals(Set.of(1L, 2L), Set.of(), Map.of(), Map.of(), Set.of());
        List<RadarResponse.Pick> picks = RadarService.pick(List.of(item(1, 1, null, 4.0, Set.of()), item(2, 2, null, 0.2, Set.of())), s, NOW);
        assertThat(picks).extracting(RadarResponse.Pick::itemId).containsExactly(2L, 1L);
    }

    @Test
    void atMostSixPicks() {
        RadarService.Signals s = signals(Set.of(1L), Set.of(), Map.of(), Map.of(), Set.of());
        List<NearbyFoodItemResponse> many = IntStream.range(0, 10).mapToObj(i -> item(i, 1, null, 0.5, Set.of())).toList();
        assertThat(RadarService.pick(many, s, NOW)).hasSize(RadarService.MAX_PICKS);
    }

    @Test
    void quietHoursCanSpanMidnight() {
        RadarProfile p = new RadarProfile();
        p.setTimeZone("UTC");
        p.setQuietStart(LocalTime.of(22, 0));
        p.setQuietEnd(LocalTime.of(7, 0));

        assertThat(p.isQuietAt(Instant.parse("2026-11-17T23:30:00Z"))).isTrue();
        assertThat(p.isQuietAt(Instant.parse("2026-11-17T06:59:00Z"))).isTrue();
        assertThat(p.isQuietAt(Instant.parse("2026-11-17T07:00:00Z"))).isFalse();
        assertThat(p.isQuietAt(Instant.parse("2026-11-17T15:00:00Z"))).isFalse();

        p.setQuietStart(LocalTime.of(13, 0));
        p.setQuietEnd(LocalTime.of(14, 0));
        assertThat(p.isQuietAt(Instant.parse("2026-11-17T13:30:00Z"))).isTrue();
        assertThat(p.isQuietAt(Instant.parse("2026-11-17T14:30:00Z"))).isFalse();
    }

    @Test
    void aBagIsVegetarianOnlyWhenEverythingInItIs() {
        FoodItem bread = new FoodItem();
        bread.setTags(EnumSet.of(FoodTag.BAKERY, FoodTag.VEGETARIAN, FoodTag.VEGAN));
        FoodItem cheese = new FoodItem();
        cheese.setTags(EnumSet.of(FoodTag.DAIRY, FoodTag.VEGETARIAN));
        FoodItem ham = new FoodItem();
        ham.setTags(EnumSet.of(FoodTag.MEAT_FISH));

        assertThat(RescueDropService.bundleTags(List.of(bread, cheese)))
                .containsExactlyInAnyOrder(FoodTag.BAKERY, FoodTag.DAIRY, FoodTag.VEGETARIAN);
        assertThat(RescueDropService.bundleTags(List.of(bread, cheese, ham)))
                .containsExactlyInAnyOrder(FoodTag.BAKERY, FoodTag.DAIRY, FoodTag.MEAT_FISH);
    }
}
