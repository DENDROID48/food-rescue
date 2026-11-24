package com.foodrescue.service;

import com.foodrescue.config.GeocodingProperties;
import com.foodrescue.exception.BusinessRuleException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GeocodingServiceTest {

    @Test
    void constructorWorksWithoutABaseUrlAndAlwaysChecksTheSwitchFirst() {
        GeocodingService off = new GeocodingService(new GeocodingProperties(false, null, "food-rescue-test", ""));

        assertThatThrownBy(() -> off.search("Piata Victoriei"))
                .isInstanceOf(BusinessRuleException.class)
                .satisfies(e -> assertThat(((BusinessRuleException) e).getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
    }
}
