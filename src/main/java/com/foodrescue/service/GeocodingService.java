package com.foodrescue.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.foodrescue.config.GeocodingProperties;
import com.foodrescue.dto.PlaceResponse;
import com.foodrescue.exception.BusinessRuleException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Turns addresses into coordinates and back, so nobody ever has to type a latitude or longitude.
 *
 * <p>Uses a Nominatim service (OpenStreetMap's geocoder). Calls go through the server rather
 * than the browser so that the usage policy can be respected in one place: results are cached
 * and upstream calls are spaced at least one second apart.</p>
 */
@Service
@Slf4j
public class GeocodingService {

    private static final int MAX_RESULTS = 5;
    private static final int CACHE_SIZE = 1000;
    private static final long MIN_MILLIS_BETWEEN_CALLS = 1000;

    private final GeocodingProperties properties;
    private final RestClient restClient;

    /** Least recently used cache of answers, keyed by the request. */
    private final Map<String, List<PlaceResponse>> cache = Collections.synchronizedMap(
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, List<PlaceResponse>> eldest) {
                    return size() > CACHE_SIZE;
                }
            });

    private final Object throttleLock = new Object();
    private long lastCallMillis;

    public GeocodingService(GeocodingProperties properties) {
        this.properties = properties;

        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(5));
        requestFactory.setReadTimeout(Duration.ofSeconds(8));
        this.restClient = RestClient.builder()
                .baseUrl(properties.baseUrl() == null ? "" : properties.baseUrl())
                .defaultHeader(HttpHeaders.USER_AGENT, properties.userAgent())
                .defaultHeader(HttpHeaders.ACCEPT_LANGUAGE, "ro,en")
                .requestFactory(requestFactory)
                .build();
    }

    /** Places matching a free text query such as "Piata Victoriei, Timisoara", best match first. */
    public List<PlaceResponse> search(String query) {
        String trimmed = query.trim();
        String key = "search:" + trimmed.toLowerCase(Locale.ROOT);
        return cached(key, () -> {
            NominatimPlace[] found = restClient.get()
                    .uri(uri -> {
                        uri.path("/search")
                                .queryParam("q", trimmed)
                                .queryParam("format", "jsonv2")
                                .queryParam("limit", MAX_RESULTS);
                        if (properties.countryCodes() != null && !properties.countryCodes().isBlank()) {
                            uri.queryParam("countrycodes", properties.countryCodes());
                        }
                        return uri.build();
                    })
                    .retrieve()
                    .body(NominatimPlace[].class);
            return found == null ? List.of() : Arrays.stream(found).map(NominatimPlace::toPlace).toList();
        });
    }

    /** The address nearest to a point, used to show "near Strada X" after "Use my location". */
    public Optional<PlaceResponse> reverse(double latitude, double longitude) {
        // Rounding to about 10 m lets nearby requests share one cache entry.
        String key = String.format(Locale.ROOT, "reverse:%.4f,%.4f", latitude, longitude);
        List<PlaceResponse> result = cached(key, () -> {
            NominatimPlace found = restClient.get()
                    .uri(uri -> uri.path("/reverse")
                            .queryParam("lat", latitude)
                            .queryParam("lon", longitude)
                            .queryParam("format", "jsonv2")
                            .queryParam("zoom", 17)
                            .build())
                    .retrieve()
                    .body(NominatimPlace.class);
            return found == null || found.displayName() == null ? List.of() : List.of(found.toPlace());
        });
        return result.stream().findFirst();
    }

    /**
     * Coordinates of a street address, for registering a store.
     *
     * @throws BusinessRuleException 400 when the address cannot be found
     */
    public PlaceResponse locateAddress(String address) {
        return search(address).stream().findFirst()
                .orElseThrow(() -> new BusinessRuleException(HttpStatus.BAD_REQUEST,
                        "We could not find this address on the map. Check the spelling or add the city."));
    }

    private List<PlaceResponse> cached(String key, UpstreamCall call) {
        if (!properties.enabled()) {
            throw unavailable();
        }
        List<PlaceResponse> hit = cache.get(key);
        if (hit != null) {
            return hit;
        }
        try {
            waitForTurn();
            List<PlaceResponse> result = call.run();
            cache.put(key, result);
            return result;
        } catch (RestClientException ex) {
            log.warn("Address lookup failed: {}", ex.getMessage());
            throw unavailable();
        }
    }

    /** Spaces upstream calls at least one second apart, as the Nominatim usage policy asks. */
    private void waitForTurn() {
        synchronized (throttleLock) {
            long wait = lastCallMillis + MIN_MILLIS_BETWEEN_CALLS - System.currentTimeMillis();
            if (wait > 0) {
                try {
                    Thread.sleep(wait);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw unavailable();
                }
            }
            lastCallMillis = System.currentTimeMillis();
        }
    }

    private static BusinessRuleException unavailable() {
        return new BusinessRuleException(HttpStatus.SERVICE_UNAVAILABLE, "Address lookup is not available right now");
    }

    @FunctionalInterface
    private interface UpstreamCall {
        List<PlaceResponse> run();
    }

    /** The fields of a Nominatim answer that are used; coordinates arrive as strings. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record NominatimPlace(@JsonProperty("display_name") String displayName, String lat, String lon) {
        PlaceResponse toPlace() {
            return new PlaceResponse(displayName, Double.parseDouble(lat), Double.parseDouble(lon));
        }
    }
}
