package com.foodrescue.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class GeoUtilsTest {

    @Test
    void oneDegreeOfLongitudeAtTheEquatorIsAbout111Km() {
        double km = GeoUtils.haversineKm(0, 0, 0, 1);
        assertThat(km).isCloseTo(111.19, within(0.1));
    }

    @Test
    void distanceToSamePointIsZero() {
        assertThat(GeoUtils.haversineKm(45.75, 21.23, 45.75, 21.23)).isZero();
    }

    @Test
    void boundingBoxContainsPointsInsideTheRadius() {
        GeoUtils.BoundingBox box = GeoUtils.boundingBox(45.75, 21.23, 5);
        // A point roughly 4 km north of the centre must be inside the box.
        double northLat = 45.75 + 4 / 111.32;
        assertThat(northLat).isBetween(box.minLat(), box.maxLat());
        assertThat(21.23).isBetween(box.minLng(), box.maxLng());
    }
}
