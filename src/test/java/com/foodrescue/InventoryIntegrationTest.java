package com.foodrescue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.foodrescue.domain.FoodItem;
import com.foodrescue.domain.Store;
import com.foodrescue.repository.FoodItemRepository;
import com.foodrescue.repository.StoreRepository;
import com.foodrescue.service.GeocodingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * The store's daily workflow over HTTP: reusable products, publishing today's offers, drafts,
 * pausing, customer reservations against those offers, daily figures and the reminder.
 *
 * <p>Each test creates its own store in a time zone where it is currently daytime, so pickup
 * windows "later today" exist whenever the tests run.</p>
 */
@SpringBootTest(properties = "foodrescue.geocoding.enabled=false")
@AutoConfigureMockMvc
class InventoryIntegrationTest {

    private static final DateTimeFormatter HHMM = DateTimeFormatter.ofPattern("HH:mm");

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired FoodItemRepository foodItemRepository;
    @Autowired StoreRepository storeRepository;
    @MockitoBean GeocodingService geocodingService;

    String owner;
    long storeId;
    ZoneId zone;
    String windowStart;
    String windowEnd;

    // ---------- helpers ----------

    private String token(String email, String role) throws Exception {
        String body = mvc.perform(post("/api/v1/users").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("fullName", "Test " + role, "email", email, "password", "demo1234", "role", role))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("token").asText();
    }

    private String register(String role) throws Exception {
        return token(role.toLowerCase() + "-" + UUID.randomUUID() + "@test.ro", role);
    }

    private ResultActions call(MockHttpServletRequestBuilder request, String token, Object body) throws Exception {
        request.header("Authorization", "Bearer " + token);
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        return mvc.perform(request);
    }

    private JsonNode read(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString());
    }

    /** A zone where the local time is between 06:00 and 17:00 right now. Offsets span 26 hours, so one always exists. */
    private static ZoneId daytimeZone() {
        return Stream.of("Europe/Bucharest", "America/New_York", "America/Los_Angeles", "Pacific/Honolulu",
                        "Asia/Tokyo", "Pacific/Auckland", "Pacific/Kiritimati", "Asia/Kolkata", "Pacific/Pago_Pago")
                .map(ZoneId::of)
                .filter(z -> { int h = ZonedDateTime.now(z).getHour(); return h >= 6 && h < 17; })
                .findFirst().orElseThrow();
    }

    private Map<String, Object> template(String name, String kind) {
        return Map.of("name", name, "description", "Today's best", "kind", kind,
                "originalPrice", 20, "discountedPrice", 8, "pickupStart", "06:00", "pickupEnd", "23:00");
    }

    private long createTemplate(String name, String kind) throws Exception {
        return read(call(post("/api/v1/stores/" + storeId + "/templates"), owner, template(name, kind))
                .andExpect(status().isCreated())).get("id").asLong();
    }

    private Map<String, Object> line(long templateId, int forSale, int free) {
        return Map.of("templateId", templateId, "quantityAvailable", forSale, "payItForwardQuantity", free,
                "pickupStart", windowStart, "pickupEnd", windowEnd);
    }

    private JsonNode publish(boolean publish, Object... lines) throws Exception {
        return read(call(post("/api/v1/stores/" + storeId + "/offers"), owner,
                Map.of("offers", List.of(lines), "publish", publish)).andExpect(status().isCreated()));
    }

    private JsonNode today() throws Exception {
        return read(call(get("/api/v1/stores/" + storeId + "/today"), owner, null).andExpect(status().isOk()));
    }

    private boolean visibleInSearch(long itemId) throws Exception {
        JsonNode items = json.readTree(mvc.perform(get("/api/v1/food-items/nearby?lat=10.0&lng=10.0&radiusKm=2"))
                .andReturn().getResponse().getContentAsString());
        for (JsonNode i : items) if (i.get("id").asLong() == itemId) return true;
        return false;
    }

    @BeforeEach
    void createStore() throws Exception {
        zone = daytimeZone();
        LocalTime local = ZonedDateTime.now(zone).toLocalTime();
        windowStart = local.minusHours(1).format(HHMM);
        windowEnd = local.plusHours(3).format(HHMM);
        owner = register("STORE_OWNER");
        // Every test gets its own spot far from the demo stores, so searches only see its offers.
        double lat = 10.0, lng = 10.0;
        storeId = read(call(post("/api/v1/stores"), owner, Map.of("name", "Daily Shop", "address", "Strada 1",
                "latitude", lat, "longitude", lng, "timeZone", zone.getId())).andExpect(status().isCreated())).get("id").asLong();
        // Older tests' stores at the same spot are suspended, so they do not show up in searches.
        for (Store s : storeRepository.findAll()) {
            if (s.getId() != storeId && s.getLatitude() == lat) { s.setActive(false); storeRepository.save(s); }
        }
    }

    // ---------- tests ----------

    @Test
    void publishTodaysOffersFromTemplatesInOneRequest() throws Exception {
        long bread = createTemplate("Sourdough loaf", "PRODUCT");
        long bag = createTemplate("Surprise bag", "SURPRISE_BAG");

        JsonNode created = publish(true, line(bread, 5, 1), line(bag, 3, 0));
        assertThat(created).hasSize(2);
        JsonNode offer = created.get(0);
        assertThat(offer.get("state").asText()).isEqualTo("ACTIVE");
        assertThat(offer.get("templateId").asLong()).isEqualTo(bread);
        assertThat(offer.get("quantityAvailable").asInt()).isEqualTo(5);
        // 23:00 template default was overridden by today's explicit window, read in the store's zone.
        Instant expectedEnd = ZonedDateTime.of(LocalDate.now(zone), LocalTime.parse(windowEnd), zone).toInstant();
        assertThat(Instant.parse(offer.get("expiresAt").asText())).isEqualTo(expectedEnd);
        assertThat(created.get(1).get("kind").asText()).isEqualTo("SURPRISE_BAG");

        assertThat(visibleInSearch(offer.get("id").asLong())).isTrue();
        JsonNode t = today();
        assertThat(t.get("timeZone").asText()).isEqualTo(zone.getId());
        assertThat(t.get("date").asText()).isEqualTo(LocalDate.now(zone).toString());
        assertThat(t.get("offers")).hasSize(2);
        assertThat(t.get("insights").get("availableUnits").asLong()).isEqualTo(8);
        assertThat(t.get("insights").get("availableFreeUnits").asLong()).isEqualTo(1);
        assertThat(t.get("insights").get("liveOffers").asLong()).isEqualTo(2);
        // Templates carry no quantity at all: nothing to pre-fill from a previous day.
        assertThat(t.get("templates").get(0).has("quantityAvailable")).isFalse();
    }

    @Test
    void sameProductCannotBePublishedTwiceForTheSameDayAndBatchesAreAllOrNothing() throws Exception {
        long bread = createTemplate("Bread", "PRODUCT");
        long cake = createTemplate("Cake", "PRODUCT");
        publish(true, line(bread, 2, 0));

        call(post("/api/v1/stores/" + storeId + "/offers"), owner, Map.of("offers", List.of(line(cake, 2, 0), line(bread, 4, 0)), "publish", true))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("“Bread” is already on offer today. Change that offer instead."));
        // The cake line was rolled back together with the failing bread line.
        assertThat(today().get("offers")).hasSize(1);
    }

    @Test
    void invalidLinesAreRejectedWithClearMessages() throws Exception {
        long bread = createTemplate("Bread", "PRODUCT");
        String url = "/api/v1/stores/" + storeId + "/offers";
        call(post(url), owner, Map.of("offers", List.of(line(bread, 0, 0)), "publish", true))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.detail").value("Enter today's quantity for “Bread”"));
        call(post(url), owner, Map.of("offers", List.of(Map.of("templateId", bread, "quantityAvailable", 1, "payItForwardQuantity", 0,
                        "pickupStart", windowEnd, "pickupEnd", windowStart)), "publish", true))
                .andExpect(status().isBadRequest());
        call(post(url), owner, Map.of("offers", List.of(line(bread, 1, 0), line(bread, 1, 0)), "publish", true))
                .andExpect(status().isBadRequest());
        call(post(url), owner, Map.of("offers", List.of(line(bread, -1, 0)), "publish", true))
                .andExpect(status().isBadRequest());
        call(post("/api/v1/stores/" + storeId + "/templates"), owner,
                Map.of("name", "X", "kind", "PRODUCT", "originalPrice", 5, "discountedPrice", 6, "pickupStart", "18:00", "pickupEnd", "20:00"))
                .andExpect(status().isBadRequest());
        assertThat(today().get("offers")).isEmpty();
    }

    @Test
    void draftsAndPausedOffersAreHiddenAndCannotBeReserved() throws Exception {
        long bread = createTemplate("Bread", "PRODUCT");
        long id = publish(false, line(bread, 3, 0)).get(0).get("id").asLong();
        String customer = register("CONSUMER");

        assertThat(today().get("offers").get(0).get("state").asText()).isEqualTo("DRAFT");
        assertThat(visibleInSearch(id)).isFalse();
        call(post("/api/v1/food-items/" + id + "/reservations"), customer, Map.of("quantity", 1)).andExpect(status().isConflict());

        call(put("/api/v1/stores/" + storeId + "/offers/" + id + "/status"), owner, Map.of("status", "ACTIVE"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("ACTIVE"));
        assertThat(visibleInSearch(id)).isTrue();
        call(post("/api/v1/food-items/" + id + "/reservations"), customer, Map.of("quantity", 1)).andExpect(status().isCreated());

        call(put("/api/v1/stores/" + storeId + "/offers/" + id + "/status"), owner, Map.of("status", "PAUSED"))
                .andExpect(jsonPath("$.state").value("PAUSED"));
        assertThat(visibleInSearch(id)).isFalse();
        call(post("/api/v1/food-items/" + id + "/reservations"), customer, Map.of("quantity", 1)).andExpect(status().isConflict());
        // The reservation made before pausing still counts and can still be collected.
        assertThat(today().get("insights").get("reservedUnits").asLong()).isEqualTo(1);
        call(put("/api/v1/stores/" + storeId + "/offers/" + id + "/status"), owner, Map.of("status", "DRAFT"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void dailyFiguresComeFromReservationsCancellationsAndPickups() throws Exception {
        long bread = createTemplate("Bread", "PRODUCT");
        long id = publish(true, line(bread, 5, 2)).get(0).get("id").asLong();
        String customer = register("CONSUMER");

        JsonNode first = read(call(post("/api/v1/food-items/" + id + "/reservations"), customer, Map.of("quantity", 2)).andExpect(status().isCreated()));
        // The pickup window decides how long the reservation is held.
        assertThat(first.get("pickupOpensAt").isNull()).isFalse();
        JsonNode second = read(call(post("/api/v1/food-items/" + id + "/reservations"), customer, Map.of("quantity", 1)).andExpect(status().isCreated()));
        JsonNode insights = today().get("insights");
        assertThat(insights.get("availableUnits").asLong()).isEqualTo(2);
        assertThat(insights.get("reservedUnits").asLong()).isEqualTo(3);

        call(post("/api/v1/orders/" + second.get("orderId").asLong() + "/cancel"), customer, null).andExpect(status().isOk());
        call(post("/api/v1/stores/" + storeId + "/pickups"), owner, Map.of("pickupCode", first.get("pickupCode").asText())).andExpect(status().isOk());

        insights = today().get("insights");
        assertThat(insights.get("availableUnits").asLong()).isEqualTo(3); // cancelled unit came back
        assertThat(insights.get("reservedUnits").asLong()).isZero();
        assertThat(insights.get("collectedUnits").asLong()).isEqualTo(2);
        assertThat(insights.get("donatedUnits").asLong()).isZero();
    }

    @Test
    void quickEditChangesQuantitiesAndWindowButNotEndedOffers() throws Exception {
        long bread = createTemplate("Bread", "PRODUCT");
        long id = publish(true, line(bread, 5, 0)).get(0).get("id").asLong();
        String url = "/api/v1/stores/" + storeId + "/offers/" + id;
        call(patch(url), owner, Map.of("quantityAvailable", 0, "payItForwardQuantity", 0, "pickupStart", windowStart, "pickupEnd", windowEnd))
                .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("SOLD_OUT"));
        assertThat(visibleInSearch(id)).isFalse();

        FoodItem item = foodItemRepository.findById(id).orElseThrow();
        item.setExpiresAt(Instant.now().minusSeconds(60));
        foodItemRepository.save(item);
        call(patch(url), owner, Map.of("quantityAvailable", 3, "payItForwardQuantity", 0, "pickupStart", windowStart, "pickupEnd", windowEnd))
                .andExpect(status().isConflict());
    }

    @Test
    void yesterdaysOffersNeverShowUpAsTodaysAndCanBePublishedAgainExplicitly() throws Exception {
        long bread = createTemplate("Bread", "PRODUCT");
        long id = publish(true, line(bread, 4, 0)).get(0).get("id").asLong();
        // Move the offer back to yesterday.
        FoodItem item = foodItemRepository.findById(id).orElseThrow();
        Instant yesterday = LocalDate.now(zone).minusDays(1).atTime(18, 0).atZone(zone).toInstant();
        item.setExpiresAt(yesterday);
        item.setCreatedAt(yesterday.minus(Duration.ofHours(4)));
        foodItemRepository.save(item);

        JsonNode t = today();
        assertThat(t.get("offers")).isEmpty();
        assertThat(t.get("insights").get("availableUnits").asLong()).isZero();
        assertThat(visibleInSearch(id)).isFalse();
        // Today needs a new, explicit publish; it is not blocked by yesterday's offer.
        assertThat(publish(true, line(bread, 2, 0)).get(0).get("quantityAvailable").asInt()).isEqualTo(2);
    }

    @Test
    void reminderIsDueOnlyUntilSomethingIsPublishedOrItIsDismissed() throws Exception {
        String url = "/api/v1/stores/" + storeId + "/reminder";
        call(put(url), owner, Map.of("enabled", true)).andExpect(status().isBadRequest());
        call(put(url), owner, Map.of("enabled", true, "time", "00:00")).andExpect(status().isOk()).andExpect(jsonPath("$.due").value(true));
        assertThat(today().get("reminder").get("due").asBoolean()).isTrue();

        call(post(url + "/dismiss"), owner, null).andExpect(status().isNoContent());
        assertThat(today().get("reminder").get("due").asBoolean()).isFalse();

        Store store = storeRepository.findById(storeId).orElseThrow();
        store.setReminderDismissedOn(null);
        storeRepository.save(store);
        assertThat(today().get("reminder").get("due").asBoolean()).isTrue();
        publish(false, line(createTemplate("Bread", "PRODUCT"), 1, 0));
        assertThat(today().get("reminder").get("due").asBoolean()).isFalse();

        call(put(url), owner, Map.of("enabled", false)).andExpect(jsonPath("$.enabled").value(false));
    }

    @Test
    void templatePhotoIsCopiedToTheOfferAndOtherStoresCannotTouchTemplates() throws Exception {
        long bread = createTemplate("Bread", "PRODUCT");
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0};
        mvc.perform(multipart("/api/v1/stores/" + storeId + "/templates/" + bread + "/photo")
                        .file(new MockMultipartFile("file", "p.png", "image/png", png)).header("Authorization", "Bearer " + owner))
                .andExpect(status().isNoContent());
        String photoUrl = today().get("templates").get(0).get("photoUrl").asText();
        mvc.perform(get(photoUrl)).andExpect(status().isOk()).andExpect(content().contentType("image/png"));

        JsonNode offer = publish(true, line(bread, 1, 0)).get(0);
        mvc.perform(get(offer.get("photoUrl").asText())).andExpect(status().isOk()).andExpect(content().bytes(png));

        String other = register("STORE_OWNER");
        long otherStore = read(call(post("/api/v1/stores"), other, Map.of("name", "Other", "address", "Strada 2",
                "latitude", 11.0, "longitude", 11.0)).andExpect(status().isCreated())).get("id").asLong();
        call(get("/api/v1/stores/" + storeId + "/templates"), other, null).andExpect(status().isForbidden());
        call(put("/api/v1/stores/" + otherStore + "/templates/" + bread), other, template("Stolen", "PRODUCT")).andExpect(status().isNotFound());
        call(post("/api/v1/stores/" + otherStore + "/offers"), other, Map.of("offers", List.of(line(bread, 1, 0)), "publish", true))
                .andExpect(status().isNotFound());
        call(get("/api/v1/stores/" + storeId + "/today"), register("CONSUMER"), null).andExpect(status().isForbidden());
    }

    @Test
    void archivedTemplatesDisappearButTheirOffersRemain() throws Exception {
        long bread = createTemplate("Bread", "PRODUCT");
        publish(true, line(bread, 1, 0));
        call(delete("/api/v1/stores/" + storeId + "/templates/" + bread), owner, null).andExpect(status().isNoContent());
        JsonNode t = today();
        assertThat(t.get("templates")).isEmpty();
        assertThat(t.get("offers")).hasSize(1);
        assertThat(new BigDecimal(t.get("offers").get(0).get("discountedPrice").asText())).isEqualByComparingTo("8");
    }

    @Test
    void unknownTimeZonesAreRefused() throws Exception {
        call(post("/api/v1/stores"), owner, Map.of("name", "X", "address", "Y", "latitude", 1.0, "longitude", 1.0, "timeZone", "Mars/Olympus"))
                .andExpect(status().isBadRequest());
        call(post("/api/v1/stores"), owner, Map.of("name", "X", "address", "Y", "latitude", 1.0, "longitude", 1.0, "timeZone", "+02:00"))
                .andExpect(status().isBadRequest());
    }
}
