package com.foodrescue.dto;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Request records with constructors of their own. */
class RequestConstructorsTest {

    @Test
    void storeRequestWithoutTimeZoneLeavesItToTheServer() {
        StoreRequest r = new StoreRequest("Corner Bakery", "Piata Victoriei 1", 45.75, 21.22);
        assertThat(r.name()).isEqualTo("Corner Bakery");
        assertThat(r.latitude()).isEqualTo(45.75);
        assertThat(r.timeZone()).isNull();
    }

    @Test
    void reserveRequestDefaultsToOneUnit() {
        assertThat(new ReserveRequest(null).quantity()).isEqualTo(1);
        assertThat(new ReserveRequest(3).quantity()).isEqualTo(3);
    }
}
