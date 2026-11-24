package com.foodrescue.service;

import com.foodrescue.domain.FoodItem;
import com.foodrescue.domain.OfferStatus;
import com.foodrescue.domain.ProductTemplate;
import com.foodrescue.domain.Store;
import com.foodrescue.dto.AutopilotPlan;
import com.foodrescue.dto.BundleSuggestion;
import com.foodrescue.service.AutopilotService.Day;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The Autopilot's arithmetic, without a database. */
class AutopilotServiceTest {

    private static final LocalTime SIX = LocalTime.of(18, 0), EIGHT = LocalTime.of(20, 0);
    // 2026-11-17 is a Tuesday.
    private static final LocalDate TUESDAY = LocalDate.of(2026, 11, 17);

    private static ProductTemplate template() {
        ProductTemplate t = new ProductTemplate();
        t.setId(7L);
        t.setName("Sourdough loaf");
        t.setOriginalPrice(new BigDecimal("14.00"));
        t.setDiscountedPrice(new BigDecimal("6.00"));
        t.setPickupStart(SIX);
        t.setPickupEnd(EIGHT);
        return t;
    }

    private static Day day(int daysAgo, int sold, int noShow, int left) {
        return new Day(TUESDAY.minusDays(daysAgo), sold, noShow, left, 1, SIX, EIGHT, new BigDecimal("6.00"));
    }

    @Test
    void withoutHistoryNothingIsGuessed() {
        AutopilotPlan.Line line = AutopilotService.suggest(template(), List.of(), DayOfWeek.TUESDAY);

        assertThat(line.quantity()).isNull();
        assertThat(line.confidence()).isEqualTo("NONE");
        assertThat(line.pickupStart()).isEqualTo(SIX);
        assertThat(line.suggestedPrice()).isNull();
    }

    @Test
    void afterOneDayTheSameQuantityIsProposed() {
        AutopilotPlan.Line line = AutopilotService.suggest(template(), List.of(day(1, 4, 1, 3)), DayOfWeek.TUESDAY);

        assertThat(line.quantity()).isEqualTo(8); // 4 collected + 1 no-show + 3 left
        assertThat(line.payItForward()).isEqualTo(1);
        assertThat(line.confidence()).isEqualTo("LOW");
    }

    @Test
    void sellingOutRaisesTheQuantity() {
        List<Day> history = List.of(day(1, 6, 0, 0), day(2, 6, 0, 0), day(3, 5, 0, 1), day(4, 6, 0, 0));

        AutopilotPlan.Line line = AutopilotService.suggest(template(), history, DayOfWeek.TUESDAY);

        assertThat(line.quantity()).isGreaterThan(6);
        assertThat(line.reasons()).anyMatch(r -> r.startsWith("Sold out on 3 of the last 4 days"));
        assertThat(line.confidence()).isEqualTo("MEDIUM");
        assertThat(line.suggestedPrice()).isNull();
    }

    @Test
    void largeLeftoversLowerTheQuantityAndSuggestAPrice() {
        List<Day> history = List.of(day(1, 3, 0, 7), day(2, 4, 0, 6), day(3, 3, 0, 7), day(4, 2, 0, 8), day(5, 3, 0, 7));

        AutopilotPlan.Line line = AutopilotService.suggest(template(), history, DayOfWeek.TUESDAY);

        assertThat(line.quantity()).isBetween(3, 4);
        assertThat(line.confidence()).isEqualTo("HIGH");
        // 15% below 6.00 is 5.10; only used if the owner applies it.
        assertThat(line.suggestedPrice()).isEqualByComparingTo("5.10");
        assertThat(line.reasons()).anyMatch(r -> r.contains("Applied only if you choose it"));
    }

    @Test
    void sameWeekdayCountsDoubleAndProvidesThePickupTimes() {
        // Last Tuesday (7 days ago) was busy and had a later window; other days were quiet.
        Day lastTuesday = new Day(TUESDAY.minusDays(7), 10, 0, 2, 0, LocalTime.of(19, 0), LocalTime.of(21, 0), BigDecimal.ONE);
        List<Day> history = List.of(day(1, 2, 0, 2), day(2, 2, 0, 2), lastTuesday);

        AutopilotPlan.Line line = AutopilotService.suggest(template(), history, DayOfWeek.TUESDAY);

        // Weighted average demand (2 + 2 + 2×10) / 4 = 6, plus a 10% buffer.
        assertThat(line.quantity()).isEqualTo(7);
        assertThat(line.pickupStart()).isEqualTo(LocalTime.of(19, 0));
        assertThat(line.pickupEnd()).isEqualTo(LocalTime.of(21, 0));
        assertThat(line.reasons()).anyMatch(r -> r.startsWith("Pickup times as on Tuesday 10 Nov"));
    }

    @Test
    void manyNoShowsAreReported() {
        List<Day> history = List.of(day(1, 2, 2, 1), day(2, 2, 2, 1));

        AutopilotPlan.Line line = AutopilotService.suggest(template(), history, DayOfWeek.TUESDAY);

        assertThat(line.reasons()).anyMatch(r -> r.equals("50% of reservations were not collected."));
    }

    @Test
    void lowerPriceNeverGoesBelowAFifthOfTheOriginal() {
        assertThat(AutopilotService.lowerPrice(new BigDecimal("6.00"), new BigDecimal("14.00"))).isEqualByComparingTo("5.10");
        assertThat(AutopilotService.lowerPrice(new BigDecimal("3.00"), new BigDecimal("14.00"))).isEqualByComparingTo("2.80");
        assertThat(AutopilotService.lowerPrice(new BigDecimal("2.80"), new BigDecimal("14.00"))).isNull();
    }

    // ---------- Smart Bundle proposal ----------

    private static FoodItem offer(long id, int left, String original, String discounted, Duration endsIn, Instant now) {
        FoodItem f = new FoodItem();
        f.setId(id);
        f.setStore(new Store());
        f.setName("Item " + id);
        f.setOriginalPrice(new BigDecimal(original));
        f.setDiscountedPrice(new BigDecimal(discounted));
        f.setQuantityAvailable(left);
        f.setStatus(OfferStatus.ACTIVE);
        f.setExpiresAt(now.plus(endsIn));
        return f;
    }

    @Test
    void leftoversEndingSoonAreProposedAsBags() {
        Instant now = Instant.parse("2026-11-17T16:00:00Z");
        List<FoodItem> offers = List.of(
                offer(1, 4, "14.00", "6.00", Duration.ofMinutes(60), now),
                offer(2, 2, "16.00", "6.50", Duration.ofMinutes(90), now),
                offer(3, 5, "10.00", "4.00", Duration.ofHours(5), now));   // ends later: not proposed

        BundleSuggestion s = AutopilotService.bundleSuggestion(offers, now);

        assertThat(s.components()).extracting(BundleSuggestion.Component::offerId).containsExactly(1L, 2L);
        assertThat(s.bags()).isEqualTo(2); // 6 units, 3 per bag
        // Contents worth (4×14 + 2×16) / 2 = 44.00 per bag; 30% is 13.20, but at most 80% of the
        // discounted value (4×6 + 2×6.5) / 2 = 18.50 → 14.80; rounded down to 0.50: 13.00.
        assertThat(s.bagValue()).isEqualByComparingTo("44.00");
        assertThat(s.price()).isEqualByComparingTo("13.00");
    }

    @Test
    void noBundleForASingleLeftoverOrPausedOffers() {
        Instant now = Instant.parse("2026-11-17T16:00:00Z");
        FoodItem paused = offer(2, 5, "10.00", "4.00", Duration.ofMinutes(30), now);
        paused.setStatus(OfferStatus.PAUSED);

        assertThat(AutopilotService.bundleSuggestion(List.of(offer(1, 1, "10.00", "4.00", Duration.ofMinutes(30), now), paused), now))
                .isNull();
    }
}
