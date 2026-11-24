package com.foodrescue.service;

import com.foodrescue.domain.FoodItem;
import com.foodrescue.domain.OfferState;
import com.foodrescue.domain.OfferStatus;
import com.foodrescue.exception.BusinessRuleException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Pickup windows are converted in the store's time zone, and offer states follow clock and stock. */
class DailyOfferServiceTest {

    private static final ZoneId BUCHAREST = ZoneId.of("Europe/Bucharest");
    private static final Instant EARLY = Instant.parse("2026-07-15T05:00:00Z");

    @Test
    void pickupTimesAreReadInTheStoresTimeZone() {
        var summer = DailyOfferService.window(LocalDate.of(2026, 7, 15), LocalTime.of(18, 0), LocalTime.of(20, 0), BUCHAREST, EARLY, "x");
        assertThat(summer.start()).isEqualTo(Instant.parse("2026-07-15T15:00:00Z")); // UTC+3 in summer
        assertThat(summer.end()).isEqualTo(Instant.parse("2026-07-15T17:00:00Z"));

        var winter = DailyOfferService.window(LocalDate.of(2026, 1, 15), LocalTime.of(18, 0), LocalTime.of(20, 0), BUCHAREST,
                Instant.parse("2026-01-15T05:00:00Z"), "x");
        assertThat(winter.start()).isEqualTo(Instant.parse("2026-01-15T16:00:00Z")); // UTC+2 in winter

        var newYork = DailyOfferService.window(LocalDate.of(2026, 7, 15), LocalTime.of(18, 0), LocalTime.of(20, 0),
                ZoneId.of("America/New_York"), EARLY, "x");
        assertThat(newYork.end()).isEqualTo(Instant.parse("2026-07-16T00:00:00Z")); // ends after midnight UTC
    }

    @Test
    void aTimeSkippedByTheClockChangeMovesForward() {
        // On 29 March 2026 clocks in Romania jump from 03:00 to 04:00, so 03:30 does not exist.
        var w = DailyOfferService.window(LocalDate.of(2026, 3, 29), LocalTime.of(3, 30), LocalTime.of(5, 0), BUCHAREST,
                Instant.parse("2026-03-29T00:00:00Z"), "x");
        assertThat(w.start()).isEqualTo(Instant.parse("2026-03-29T01:30:00Z")); // 04:30 local, UTC+3
    }

    @Test
    void windowMustEndAfterItStartsAndNotBeOverAlready() {
        assertThatThrownBy(() -> DailyOfferService.window(LocalDate.of(2026, 7, 15), LocalTime.of(20, 0), LocalTime.of(18, 0), BUCHAREST, EARLY, "x"))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("must end after it starts");
        Instant evening = Instant.parse("2026-07-15T18:00:00Z"); // 21:00 in Bucharest
        assertThatThrownBy(() -> DailyOfferService.window(LocalDate.of(2026, 7, 15), LocalTime.of(18, 0), LocalTime.of(20, 0), BUCHAREST, evening, "x"))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("already ended");
    }

    @Test
    void stateFollowsExpiryThenStatusThenStock() {
        FoodItem item = new FoodItem();
        item.setOriginalPrice(new BigDecimal("10.00"));
        item.setDiscountedPrice(new BigDecimal("4.00"));
        item.setExpiresAt(EARLY.plusSeconds(3600));
        item.setQuantityAvailable(2);

        assertThat(item.state(EARLY)).isEqualTo(OfferState.ACTIVE);
        item.setStatus(OfferStatus.PAUSED);
        assertThat(item.state(EARLY)).isEqualTo(OfferState.PAUSED);
        item.setStatus(OfferStatus.DRAFT);
        assertThat(item.state(EARLY)).isEqualTo(OfferState.DRAFT);
        item.setStatus(OfferStatus.ACTIVE);
        item.setQuantityAvailable(0);
        assertThat(item.state(EARLY)).isEqualTo(OfferState.SOLD_OUT);
        item.setPayItForwardQuantity(1);
        assertThat(item.state(EARLY)).isEqualTo(OfferState.ACTIVE); // free units still available
        item.setStatus(OfferStatus.DRAFT);
        assertThat(item.state(EARLY.plusSeconds(7200))).isEqualTo(OfferState.EXPIRED); // expiry wins
    }
}
