package com.foodrescue.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** Expirable and Locatable behave the same for every class that implements them. */
class DomainInterfacesTest {

    private static final Instant DEADLINE = Instant.parse("2026-11-19T18:00:00Z");

    @Test
    void geoPointConstructorKeepsTheCoordinates() {
        GeoPoint p = new GeoPoint(45.7537, 21.2257);
        assertThat(p.getLatitude()).isEqualTo(45.7537);
        assertThat(p.getLongitude()).isEqualTo(21.2257);
    }

    @Test
    void distanceIsTheSameWhicheverLocatableAsks() {
        Store store = new Store();
        store.setLatitude(45.7537);   // Piata Victoriei, Timisoara
        store.setLongitude(21.2257);
        GeoPoint unirii = new GeoPoint(45.7579, 21.2290); // Piata Unirii, about 0.5 km away

        assertThat(unirii.distanceKmTo(store)).isCloseTo(0.53, within(0.05));
        assertThat(store.distanceKmTo(unirii)).isEqualTo(unirii.distanceKmTo(store));
        assertThat(store.distanceKmTo(store)).isZero();
    }

    @Test
    void foodItemExpiresExactlyAtItsExpiryTime() {
        FoodItem item = new FoodItem();
        item.setExpiresAt(DEADLINE);

        assertThat(item.isExpiredAt(DEADLINE.minusSeconds(1))).isFalse();
        assertThat(item.isExpiredAt(DEADLINE)).isTrue();
        assertThat(item.isNotExpired(DEADLINE.minusSeconds(1))).isTrue();
        assertThat(item.state(DEADLINE)).isEqualTo(OfferState.EXPIRED);
    }

    @Test
    void orderExpiresAtItsPickupDeadline() {
        Order order = new Order();
        order.setPickupDeadline(DEADLINE);

        Expirable asExpirable = order; // usable wherever only "can it expire?" matters
        assertThat(asExpirable.expiryMoment()).isEqualTo(DEADLINE);
        assertThat(asExpirable.isExpiredAt(DEADLINE.plusSeconds(60))).isTrue();
        assertThat(asExpirable.isExpiredAt(DEADLINE.minusSeconds(60))).isFalse();
    }
}
