package com.foodrescue.service;

import com.foodrescue.domain.FoodItem;
import com.foodrescue.domain.ProductTemplate;
import com.foodrescue.dto.BundleDropRequest;
import com.foodrescue.dto.StoreInsights;
import com.foodrescue.service.AnalyticsService.OfferRow;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Smart Analytics arithmetic: totals, product flags, timing, forecast, recommendations, CSV. */
class AnalyticsServiceTest {

    private static final ZoneId ZONE = ZoneId.of("Europe/Bucharest");
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 17);

    /** An offer that ended at 20:00 local time {@code daysAgo} days ago. */
    private static OfferRow row(long id, Long template, int daysAgo, Long published, long left, long sold, long donated,
                                long noShows, Integer grams) {
        Instant end = TODAY.minusDays(daysAgo).atTime(20, 0).atZone(ZONE).toInstant();
        return new OfferRow(id, 1, template, "Bread", end.minus(Duration.ofHours(10)), end, end.minus(Duration.ofHours(2)),
                false, grams, new BigDecimal("6.00"), left, published, sold, donated, noShows, 0,
                new BigDecimal(sold * 6), new BigDecimal(sold * 8), sold > 0 ? end.minus(Duration.ofMinutes(90)) : null);
    }

    @Test
    void totalsUsePublishedQuantitiesAndRebuildThemForOlderOffers() {
        List<OfferRow> rows = List.of(
                row(1, 7L, 1, 10L, 2, 6, 1, 1, 800),     // published 10
                row(2, 7L, 2, null, 3, 4, 0, 1, null));  // rebuilt: 3 + 4 + 0 + 1 = 8

        StoreInsights.Totals t = AnalyticsService.totals(rows);

        assertThat(t.offered()).isEqualTo(18);
        assertThat(t.collected()).isEqualTo(11);
        assertThat(t.leftover()).isEqualTo(5);
        assertThat(t.sellThroughPercent()).isEqualTo(61);   // 11 / 18
        assertThat(t.noShowPercent()).isEqualTo(15);        // 2 / (2 + 11)
        assertThat(t.kgRescued()).isEqualTo(5.6);           // 7 collected × 0.8 kg
        assertThat(t.weightCoverage()).isEqualTo(64);       // 7 of 11 collected units weighed
        assertThat(t.takings()).isEqualByComparingTo("60.00");
    }

    @Test
    void noDataMeansNoPercentagesRatherThanZero() {
        StoreInsights.Totals t = AnalyticsService.totals(List.of());
        assertThat(t.sellThroughPercent()).isNull();
        assertThat(t.noShowPercent()).isNull();
        assertThat(t.kgRescued()).isNull();
    }

    @Test
    void dailyFiguresFallOnTheStoresLocalDay() {
        List<StoreInsights.Day> days = AnalyticsService.daily(List.of(row(1, 7L, 1, 5L, 1, 4, 0, 0, null)),
                TODAY.minusDays(2), TODAY, ZONE);
        assertThat(days).extracting(StoreInsights.Day::offered).containsExactly(0L, 5L, 0L);
    }

    @Test
    void productsAreFlaggedForSellingOutOrBeingLeftOver() {
        List<OfferRow> rows = new ArrayList<>();
        for (int d = 1; d <= 3; d++) rows.add(row(d, 7L, d, 6L, 0, 6, 0, 0, null));          // always sold out
        for (int d = 1; d <= 3; d++) rows.add(row(10 + d, 8L, d, 10L, 6, 4, 0, 0, null));    // 40% sold

        List<StoreInsights.Product> products = AnalyticsService.products(rows, ZONE);

        assertThat(products).filteredOn(p -> p.templateId() == 7L).first()
                .satisfies(p -> { assertThat(p.flag()).isEqualTo("Sells out"); assertThat(p.soldOutDays()).isEqualTo(3); });
        assertThat(products).filteredOn(p -> p.templateId() == 8L).first()
                .satisfies(p -> { assertThat(p.flag()).isEqualTo("Often left over"); assertThat(p.sellThroughPercent()).isEqualTo(40); });
    }

    @Test
    void pickupTimeIsPlacedInAQuarterOfTheWindow() {
        Instant start = Instant.parse("2026-11-17T16:00:00Z"), end = start.plus(Duration.ofHours(2));
        assertThat(AnalyticsService.quarter(start.minusSeconds(60), start, end)).isZero();      // early: first quarter
        assertThat(AnalyticsService.quarter(start.plus(Duration.ofMinutes(45)), start, end)).isEqualTo(1);
        assertThat(AnalyticsService.quarter(end.minusSeconds(1), start, end)).isEqualTo(3);
        assertThat(AnalyticsService.quarter(end.plusSeconds(60), start, end)).isEqualTo(3);
    }

    // ---------- forecast ----------

    private static AutopilotService.Day day(LocalDate date, int demand) {
        return new AutopilotService.Day(date, demand, 0, 1, 0, LocalTime.of(18, 0), LocalTime.of(20, 0), BigDecimal.ONE);
    }

    @Test
    void theForecastLeansOnTheSameWeekdayButIsPulledTowardsTheAverage() {
        // Tuesdays sell 10, other days 4.
        List<AutopilotService.Day> history = new ArrayList<>();
        for (int i = 28; i >= 1; i--) {
            LocalDate d = TODAY.minusDays(i);
            history.add(day(d, d.getDayOfWeek() == java.time.DayOfWeek.TUESDAY ? 10 : 4));
        }
        double tuesday = AnalyticsService.estimate(history, java.time.DayOfWeek.TUESDAY)[0];
        double monday = AnalyticsService.estimate(history, java.time.DayOfWeek.MONDAY)[0];

        assertThat(tuesday).isBetween(7.0, 10.0);
        assertThat(monday).isBetween(4.0, 5.5);

        StoreInsights.ForecastLine line = AnalyticsService.forecastLine(7L, "Bread", history, TODAY);
        assertThat(line.days()).hasSize(7);
        assertThat(line.days().get(0).date()).isEqualTo(TODAY.plusDays(1));
        StoreInsights.ForecastDay nextTuesday = line.days().get(6);
        assertThat(nextTuesday.date().getDayOfWeek()).isEqualTo(java.time.DayOfWeek.TUESDAY);
        assertThat(nextTuesday.low()).isLessThanOrEqualTo(nextTuesday.expected());
        assertThat(nextTuesday.high()).isGreaterThanOrEqualTo(nextTuesday.expected());
        assertThat(line.backtestDays()).isEqualTo(14);
    }

    @Test
    void steadyDemandIsForecastAccurately() {
        List<AutopilotService.Day> history = new ArrayList<>();
        for (int i = 20; i >= 1; i--) history.add(day(TODAY.minusDays(i), 5));

        StoreInsights.ForecastLine line = AnalyticsService.forecastLine(7L, "Bread", history, TODAY);

        assertThat(line.days()).allSatisfy(d -> assertThat(d.expected()).isEqualTo(5));
        assertThat(line.backtestHits()).isEqualTo(line.backtestDays());
    }

    @Test
    void withTooLittleHistoryThereIsNoForecast() {
        StoreInsights.ForecastLine line = AnalyticsService.forecastLine(7L, "Bread",
                List.of(day(TODAY.minusDays(2), 3), day(TODAY.minusDays(1), 4)), TODAY);
        assertThat(line.days()).isEmpty();
        assertThat(line.historyDays()).isEqualTo(2);
    }

    // ---------- recommendations ----------

    private static ProductTemplate template(long id, String name, Integer grams) {
        ProductTemplate t = new ProductTemplate();
        t.setId(id);
        t.setName(name);
        t.setUnitWeightGrams(grams);
        return t;
    }

    @Test
    void recommendationsExplainThemselves() {
        List<OfferRow> rows = new ArrayList<>();
        for (int d = 1; d <= 4; d++) rows.add(row(d, 7L, d, 6L, 0, 6, 0, 0, 800));
        for (int d = 1; d <= 4; d++) rows.add(row(10 + d, 8L, d, 10L, 7, 2, 0, 1, null));
        List<StoreInsights.Product> products = AnalyticsService.products(rows, ZONE);
        StoreInsights.Totals totals = AnalyticsService.totals(rows);
        StoreInsights.Timing timing = new StoreInsights.Timing(new int[24], new int[]{1, 1, 2, 16}, 20);
        List<StoreInsights.Day> daily = AnalyticsService.daily(rows, TODAY.minusDays(13), TODAY, ZONE);

        List<StoreInsights.Recommendation> recs = AnalyticsService.recommend(rows, products, timing, totals, daily, ZONE,
                List.of(template(7, "Bread", 800), template(8, "Cake", null)));

        assertThat(recs).extracting(StoreInsights.Recommendation::type)
                .contains("SELLS_OUT", "LEFT_OVER", "LATE_PICKUPS", "ADD_WEIGHTS", "QUIET_DAYS");
        assertThat(recs).filteredOn(r -> r.type().equals("SELLS_OUT")).first().satisfies(r -> {
            assertThat(r.title()).isEqualTo("Offer 1 more Bread");
            assertThat(r.evidence()).isEqualTo("Sold out on 4 of 4 days, usually 90 minutes before pickup ended.");
            assertThat(r.action()).isEqualTo("SETUP");
        });
        assertThat(recs).filteredOn(r -> r.type().equals("LEFT_OVER")).first()
                .satisfies(r -> assertThat(r.evidence()).isEqualTo("80% went unsold (28 of 40 units)."));
        assertThat(recs).filteredOn(r -> r.type().equals("QUIET_DAYS")).first()
                .satisfies(r -> assertThat(r.title()).isEqualTo("No offers on 9 of the last 13 days"));
    }

    @Test
    void aGoodWeekGetsNoNaggingRecommendations() {
        List<OfferRow> rows = new ArrayList<>();
        for (int d = 0; d <= 13; d++) rows.add(row(d, 7L, d, 6L, 1, 5, 0, 0, 500));
        List<StoreInsights.Recommendation> recs = AnalyticsService.recommend(rows, AnalyticsService.products(rows, ZONE),
                new StoreInsights.Timing(new int[24], new int[]{5, 5, 5, 5}, 20), AnalyticsService.totals(rows),
                AnalyticsService.daily(rows, TODAY.minusDays(13), TODAY, ZONE), ZONE, List.of(template(7, "Bread", 500)));
        assertThat(recs).isEmpty();
    }

    // ---------- small rules ----------

    @Test
    void csvCellsAreQuotedAndCannotRunAsFormulas() {
        assertThat(AnalyticsService.csv("Bread, \"rye\"")).isEqualTo("\"Bread, \"\"rye\"\"\"");
        assertThat(AnalyticsService.csv("=HYPERLINK(\"x\")")).isEqualTo("\"'=HYPERLINK(\"\"x\"\")\"");
        assertThat(AnalyticsService.csv("-1")).startsWith("\"'-");
    }

    @Test
    void smallAudiencesAreHidden() {
        assertThat(AnalyticsService.atLeast(4)).isNull();
        assertThat(AnalyticsService.atLeast(5)).isEqualTo(5);
    }

    @Test
    void correctingTheShelfMovesThePublishedTotal() {
        FoodItem item = new FoodItem();
        item.setQuantityAvailable(3);      // 5 published, 2 reserved
        item.setPayItForwardQuantity(1);
        item.setQuantityPublished(5);
        item.setFreePublished(1);

        item.setShelf(4, 0);               // found one more; the free one was given away

        assertThat(item.getQuantityPublished()).isEqualTo(6);
        assertThat(item.getFreePublished()).isZero();
        assertThat(item.getQuantityAvailable()).isEqualTo(4);
    }

    @Test
    void aBagHasAWeightOnlyWhenEveryPartHasOne() {
        FoodItem bread = new FoodItem();
        bread.setUnitWeightGrams(800);
        FoodItem cake = new FoodItem();
        cake.setUnitWeightGrams(300);
        List<BundleDropRequest.Component> parts = List.of(new BundleDropRequest.Component(1L, 2), new BundleDropRequest.Component(2L, 3));

        assertThat(RescueDropService.bagWeight(List.of(bread, cake), parts, 2)).isEqualTo(1250); // (1600 + 900) / 2
        cake.setUnitWeightGrams(null);
        assertThat(RescueDropService.bagWeight(List.of(bread, cake), parts, 2)).isNull();
    }
}
