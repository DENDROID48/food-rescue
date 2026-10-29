package com.foodrescue.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Address lookup settings, read from {@code foodrescue.geocoding} in application.yml.
 *
 * @param enabled       false turns lookups off (tests, or no internet access)
 * @param baseUrl       Nominatim compatible service
 * @param userAgent     identifies this application to the service, as its usage policy requires
 * @param countryCodes  ISO country codes results are limited to, comma separated; empty for worldwide
 */
@ConfigurationProperties(prefix = "foodrescue.geocoding")
public record GeocodingProperties(
        boolean enabled,
        String baseUrl,
        String userAgent,
        String countryCodes) {
}
