package com.foodrescue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.foodrescue.domain.FoodItem;
import com.foodrescue.domain.Order;
import com.foodrescue.domain.OrderStatus;
import com.foodrescue.domain.OrderType;
import com.foodrescue.domain.User;
import com.foodrescue.repository.FoodItemRepository;
import com.foodrescue.repository.OrderRepository;
import com.foodrescue.repository.ProductTemplateRepository;
import com.foodrescue.repository.StoreRepository;
import com.foodrescue.repository.UserRepository;
import com.foodrescue.service.GeocodingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Store Autopilot and Rescue Drops over HTTP: suggestions from history, approved prices, stock
 * alerts, drops, Smart Bundles, drop alerts with their anti-spam limits, and access rules.
 *
 * <p>Every test places its store at its own spot, more than 100 km from the others, so searches
 * and alerts of one test never see another test's stores or customers.</p>
 */
@SpringBootTest(properties = "foodrescue.geocoding.enabled=false")
@AutoConfigureMockMvc
class AutopilotAndDropsIntegrationTest {

    private static final DateTimeFormatter HHMM = DateTimeFormatter.ofPattern("HH:mm");
    private static final AtomicInteger SPOT = new AtomicInteger();

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired FoodItemRepository foodItemRepository;
    @Autowired ProductTemplateRepository templateRepository;
    @Autowired OrderRepository orderRepository;
    @Autowired StoreRepository storeRepository;
    @Autowired UserRepository userRepository;
    @MockitoBean GeocodingService geocodingService;

    String owner;
    long storeId;
    double lat, lng;
    ZoneId zone;
    LocalTime local;

    // ---------- helpers ----------

    private String register(String role) throws Exception {
        String email = role.toLowerCase() + "-" + UUID.randomUUID() + "@test.ro";
        String body = mvc.perform(post("/api/v1/users").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("fullName", "Test " + role, "email", email, "password", "demo1234", "role", role, "acceptTerms", true))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("token").asText();
    }

    private ResultActions call(MockHttpServletRequestBuilder request, String token, Object body) throws Exception {
        if (token != null) request.header("Authorization", "Bearer " + token);
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        return mvc.perform(request);
    }

    private JsonNode read(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString());
    }

    private static ZoneId daytimeZone() {
        return Stream.of("Europe/Bucharest", "America/New_York", "America/Los_Angeles", "Pacific/Honolulu",
                        "Asia/Tokyo", "Pacific/Auckland", "Pacific/Kiritimati", "Asia/Kolkata", "Pacific/Pago_Pago")
                .map(ZoneId::of)
                .filter(z -> { int h = ZonedDateTime.now(z).getHour(); return h >= 6 && h < 17; })
                .findFirst().orElseThrow();
    }

    private long store(String token, double latitude, double longitude) throws Exception {
        return read(call(post("/api/v1/stores"), token, Map.of("name", "Drop Shop", "address", "Strada 2",
                "latitude", latitude, "longitude", longitude, "timeZone", zone.getId()))
                .andExpect(status().isCreated())).get("id").asLong();
    }

    private long template(String name) throws Exception {
        return read(call(post("/api/v1/stores/" + storeId + "/templates"), owner, Map.of("name", name, "description", "Fresh today",
                "kind", "PRODUCT", "originalPrice", 20, "discountedPrice", 8, "pickupStart", "06:00", "pickupEnd", "23:00"))
                .andExpect(status().isCreated())).get("id").asLong();
    }

    private Map<String, Object> line(long templateId, int forSale, int endsInHours) {
        return new HashMap<>(Map.of("templateId", templateId, "quantityAvailable", forSale, "payItForwardQuantity", 0,
                "pickupStart", local.minusHours(1).format(HHMM), "pickupEnd", local.plusHours(endsInHours).format(HHMM)));
    }

    private JsonNode publish(boolean live, Object... lines) throws Exception {
        return read(call(post("/api/v1/stores/" + storeId + "/offers"), owner, Map.of("offers", List.of(lines), "publish", live))
                .andExpect(status().isCreated()));
    }

    private Map<String, Object> drop(long templateId, int quantity) {
        return new HashMap<>(Map.of("templateId", templateId, "quantityAvailable", quantity, "durationMinutes", 30,
                "alertCustomers", true));
    }

    private JsonNode launch(Map<String, Object> drop) throws Exception {
        return read(call(post("/api/v1/stores/" + storeId + "/drops"), owner, drop).andExpect(status().isCreated()));
    }

    private JsonNode today() throws Exception {
        return read(call(get("/api/v1/stores/" + storeId + "/today"), owner, null).andExpect(status().isOk()));
    }

    private JsonNode offer(long id) throws Exception {
        for (JsonNode o : today().get("offers")) if (o.get("id").asLong() == id) return o;
        throw new AssertionError("offer " + id + " not on today's dashboard");
    }

    /** A customer who turned drop alerts on around a place. */
    private String subscriber(double latitude, double longitude, double radiusKm, int maxPerDay, boolean enabled) throws Exception {
        String token = register("CONSUMER");
        call(put("/api/v1/me/drop-alerts"), token, Map.of("enabled", enabled, "latitude", latitude, "longitude", longitude,
                "radiusKm", radiusKm, "maxPerDay", maxPerDay)).andExpect(status().isOk());
        return token;
    }

    private JsonNode inbox(String token) throws Exception {
        return read(call(get("/api/v1/me/notifications"), token, null).andExpect(status().isOk()));
    }

    private JsonNode nearby() throws Exception {
        return json.readTree(mvc.perform(get("/api/v1/food-items/nearby?lat=" + lat + "&lng=" + lng + "&radiusKm=2"))
                .andReturn().getResponse().getContentAsString());
    }

    @BeforeEach
    void createStore() throws Exception {
        zone = daytimeZone();
        local = ZonedDateTime.now(zone).toLocalTime();
        int spot = SPOT.incrementAndGet();
        lat = -40.0 + spot * 1.5;
        lng = -60.0;
        owner = register("STORE_OWNER");
        storeId = store(owner, lat, lng);
    }

    // ---------- Rescue Drops ----------

    @Test
    void aDropGoesLiveAtOnceAndAlertsOnlyOptedInCustomersInRange() throws Exception {
        long bread = template("Sourdough loaf");
        String near = subscriber(lat + 0.005, lng, 2, 3, true);        // ~0.6 km away, 2 km radius
        String outOfRange = subscriber(lat + 0.045, lng, 1, 3, true);  // ~5 km away, 1 km radius
        String optedOut = subscriber(lat, lng, 5, 3, false);
        String neverAsked = register("CONSUMER");

        call(get("/api/v1/stores/" + storeId + "/drops/audience"), owner, null)
                .andExpect(status().isOk()).andExpect(jsonPath("$.customers").value(1));

        Instant before = Instant.now();
        JsonNode result = launch(drop(bread, 3));
        JsonNode offer = result.get("offer");
        assertThat(result.get("notified").asInt()).isEqualTo(1);
        assertThat(offer.get("rescueDrop").asBoolean()).isTrue();
        assertThat(offer.get("state").asText()).isEqualTo("ACTIVE");
        assertThat(Instant.parse(offer.get("pickupStartsAt").asText())).isBetween(before.minusSeconds(1), Instant.now());
        assertThat(Duration.between(Instant.parse(offer.get("pickupStartsAt").asText()), Instant.parse(offer.get("expiresAt").asText())))
                .isEqualTo(Duration.ofMinutes(30));

        JsonNode found = nearby();
        assertThat(found).hasSize(1);
        assertThat(found.get(0).get("rescueDrop").asBoolean()).isTrue();

        JsonNode box = inbox(near);
        assertThat(box.get("unread").asLong()).isEqualTo(1);
        JsonNode alert = box.get("items").get(0);
        assertThat(alert.get("title").asText()).isEqualTo("Rescue Drop at Drop Shop");
        assertThat(alert.get("body").asText()).startsWith("3 × Sourdough loaf for 8.00 lei · until ");
        assertThat(alert.get("foodItemId").asLong()).isEqualTo(offer.get("id").asLong());
        assertThat(alert.get("live").asBoolean()).isTrue();
        assertThat(alert.get("storeLatitude").asDouble()).isCloseTo(lat, within(1e-9));

        assertThat(inbox(outOfRange).get("items")).isEmpty();
        assertThat(inbox(optedOut).get("items")).isEmpty();
        assertThat(inbox(neverAsked).get("items")).isEmpty();

        call(post("/api/v1/me/notifications/read"), near, null).andExpect(status().isNoContent());
        assertThat(inbox(near).get("unread").asLong()).isZero();
    }

    @Test
    void alertsAreLimitedPerStoreAndPerDay() throws Exception {
        long bread = template("Bread");
        long cake = template("Cake");
        String oncePerDay = subscriber(lat, lng, 3, 1, true);
        String threePerDay = subscriber(lat, lng, 3, 3, true);

        assertThat(launch(drop(bread, 2)).get("notified").asInt()).isEqualTo(2);
        // A second drop from the same store within two hours alerts nobody again.
        assertThat(launch(drop(cake, 2)).get("notified").asInt()).isZero();

        // Another store nearby: only the customer whose daily limit is not reached yet is alerted.
        String otherOwner = register("STORE_OWNER");
        long otherStore = store(otherOwner, lat + 0.003, lng);
        long otherTemplate = read(call(post("/api/v1/stores/" + otherStore + "/templates"), otherOwner, Map.of("name", "Soup",
                "kind", "PRODUCT", "originalPrice", 20, "discountedPrice", 8, "pickupStart", "06:00", "pickupEnd", "23:00"))).get("id").asLong();
        JsonNode other = read(call(post("/api/v1/stores/" + otherStore + "/drops"), otherOwner, drop(otherTemplate, 1))
                .andExpect(status().isCreated()));
        assertThat(other.get("notified").asInt()).isEqualTo(1);

        assertThat(inbox(oncePerDay).get("items")).hasSize(1);
        assertThat(inbox(threePerDay).get("items")).hasSize(2);
    }

    @Test
    void aDropEndsByItselfWhenSoldOutAndCannotRunTwice() throws Exception {
        long bread = template("Bread");
        long id = launch(drop(bread, 1)).get("offer").get("id").asLong();
        String customer = register("CONSUMER");

        call(post("/api/v1/stores/" + storeId + "/drops"), owner, drop(bread, 4))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("“Bread” already has a Rescue Drop running. Change that drop instead."));
        // A drop does not block the regular daily offer of the same product.
        publish(true, line(bread, 2, 3));

        call(post("/api/v1/food-items/" + id + "/reservations"), customer, Map.of("quantity", 1)).andExpect(status().isCreated());
        assertThat(offer(id).get("state").asText()).isEqualTo("SOLD_OUT");
        assertThat(nearby()).noneMatch(i -> i.get("id").asLong() == id);
        call(post("/api/v1/food-items/" + id + "/reservations"), register("CONSUMER"), Map.of("quantity", 1))
                .andExpect(status().isConflict());
    }

    @Test
    void dropRequestsAreValidated() throws Exception {
        long bread = template("Bread");
        String url = "/api/v1/stores/" + storeId + "/drops";
        Map<String, Object> tooShort = drop(bread, 2);
        tooShort.put("durationMinutes", 5);
        call(post(url), owner, tooShort).andExpect(status().isBadRequest());
        call(post(url), owner, drop(bread, 0)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Enter how many units are on the shelf now"));
        Map<String, Object> tooExpensive = drop(bread, 2);
        tooExpensive.put("discountedPrice", 20);
        call(post(url), owner, tooExpensive).andExpect(status().isBadRequest());

        Map<String, Object> cheaper = drop(bread, 2);
        cheaper.put("discountedPrice", 5.5);
        assertThat(launch(cheaper).get("offer").get("discountedPrice").decimalValue()).isEqualByComparingTo("5.50");
    }

    // ---------- Smart Bundles ----------

    @Test
    void leftoversEndingSoonAreProposedAndBundledIntoBags() throws Exception {
        long bread = template("Sourdough loaf");
        long croissant = template("Croissants");
        JsonNode created = publish(true, line(bread, 4, 1), line(croissant, 3, 1));
        long breadOffer = created.get(0).get("id").asLong();
        long croissantOffer = created.get(1).get("id").asLong();

        JsonNode t = today();
        assertThat(t.get("alerts")).anyMatch(a -> a.get("type").asText().equals("LEFTOVERS_ENDING"));
        JsonNode proposal = t.get("bundleSuggestion");
        assertThat(proposal.get("components")).hasSize(2);
        assertThat(proposal.get("bags").asInt()).isEqualTo(2); // 7 units, 3 per bag

        Map<String, Object> bundle = Map.of("components", List.of(Map.of("offerId", breadOffer, "units", 3),
                        Map.of("offerId", croissantOffer, "units", 2)),
                "bags", 2, "price", 9, "durationMinutes", 45, "alertCustomers", false);
        JsonNode result = read(call(post("/api/v1/stores/" + storeId + "/drops/bundle"), owner, bundle)
                .andExpect(status().isCreated()));
        JsonNode bag = result.get("offer");
        assertThat(bag.get("kind").asText()).isEqualTo("SURPRISE_BAG");
        assertThat(bag.get("rescueDrop").asBoolean()).isTrue();
        assertThat(bag.get("quantityAvailable").asInt()).isEqualTo(2);
        assertThat(bag.get("originalPrice").decimalValue()).isEqualByComparingTo("50.00"); // (3 + 2) × 20 / 2
        assertThat(bag.get("description").asText()).isEqualTo("A mix of today's Sourdough loaf, Croissants. Contents vary.");

        // The units moved out of the source offers.
        assertThat(offer(breadOffer).get("quantityAvailable").asInt()).isEqualTo(1);
        assertThat(offer(croissantOffer).get("quantityAvailable").asInt()).isEqualTo(1);
    }

    @Test
    void aBundleCannotTakeMoreThanIsLeftAndFailsAsAWhole() throws Exception {
        long bread = template("Bread");
        long cake = template("Cake");
        JsonNode created = publish(true, line(bread, 2, 1), line(cake, 2, 1));
        long a = created.get(0).get("id").asLong(), b = created.get(1).get("id").asLong();
        String url = "/api/v1/stores/" + storeId + "/drops/bundle";

        call(post(url), owner, Map.of("components", List.of(Map.of("offerId", a, "units", 2), Map.of("offerId", b, "units", 3)),
                "bags", 1, "price", 5, "durationMinutes", 30, "alertCustomers", false))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Only 2 unit(s) of “Cake” are left"));
        // Bread was reduced first, then rolled back with the rest.
        assertThat(offer(a).get("quantityAvailable").asInt()).isEqualTo(2);

        call(post(url), owner, Map.of("components", List.of(Map.of("offerId", a, "units", 1)),
                "bags", 2, "price", 5, "durationMinutes", 30, "alertCustomers", false)).andExpect(status().isBadRequest());
        call(post(url), owner, Map.of("components", List.of(Map.of("offerId", a, "units", 1)),
                "bags", 1, "price", 20, "durationMinutes", 30, "alertCustomers", false))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("The bag price must be below the value of its contents (20.00 lei)"));

        long draft = publish(false, line(template("Pie"), 3, 1)).get(0).get("id").asLong();
        call(post(url), owner, Map.of("components", List.of(Map.of("offerId", draft, "units", 1)),
                "bags", 1, "price", 5, "durationMinutes", 30, "alertCustomers", false)).andExpect(status().isConflict());

        // Offers of another store are reported as missing.
        String otherOwner = register("STORE_OWNER");
        long otherStore = store(otherOwner, lat, lng + 0.01);
        call(post("/api/v1/stores/" + otherStore + "/drops/bundle"), otherOwner, Map.of("components", List.of(Map.of("offerId", a, "units", 1)),
                "bags", 1, "price", 5, "durationMinutes", 30, "alertCustomers", false)).andExpect(status().isNotFound());
        assertThat(offer(a).get("quantityAvailable").asInt()).isEqualTo(2);
    }

    // ---------- Store Autopilot ----------

    @Test
    void autopilotSuggestsFromTheStoresOwnHistory() throws Exception {
        long bread = template("Bread");
        long fresh = template("New cake");
        // Three past days on which 5 loaves were offered and all 5 collected.
        User customer = userRepository.findByEmail("student@example.com").orElseThrow();
        for (int daysAgo = 1; daysAgo <= 3; daysAgo++) {
            FoodItem past = new FoodItem();
            past.setStore(storeRepository.findById(storeId).orElseThrow());
            past.setTemplate(templateRepository.findById(bread).orElseThrow());
            past.setName("Bread");
            past.setOriginalPrice(new BigDecimal("20.00"));
            past.setDiscountedPrice(new BigDecimal("8.00"));
            past.setQuantityAvailable(0);
            past.setPickupStartsAt(Instant.now().minus(Duration.ofDays(daysAgo)).minus(Duration.ofHours(2)));
            past.setExpiresAt(Instant.now().minus(Duration.ofDays(daysAgo)));
            past.setCreatedAt(Instant.now().minus(Duration.ofDays(daysAgo)).minus(Duration.ofHours(5)));
            past = foodItemRepository.save(past);
            Order order = new Order();
            order.setUser(customer);
            order.setFoodItem(past);
            order.setQuantity(5);
            order.setTotalPrice(new BigDecimal("40.00"));
            order.setType(OrderType.PURCHASE);
            order.setStatus(OrderStatus.PICKED_UP);
            order.setPickupCode("1" + daysAgo + "0000");
            order.setPickupDeadline(past.getExpiresAt());
            order.setPickedUpAt(past.getExpiresAt().minusSeconds(600));
            orderRepository.save(order);
        }

        JsonNode plan = read(call(get("/api/v1/stores/" + storeId + "/autopilot"), owner, null).andExpect(status().isOk()));
        Map<Long, JsonNode> lines = new HashMap<>();
        plan.get("lines").forEach(l -> lines.put(l.get("templateId").asLong(), l));

        JsonNode breadLine = lines.get(bread);
        assertThat(breadLine.get("quantity").asInt()).isEqualTo(6);
        assertThat(breadLine.get("daysOfHistory").asInt()).isEqualTo(3);
        assertThat(breadLine.get("confidence").asText()).isEqualTo("MEDIUM");
        List<String> reasons = new ArrayList<>();
        breadLine.get("reasons").forEach(r -> reasons.add(r.asText()));
        assertThat(reasons).contains("Sold out on 3 of the last 3 days, so 6 may meet demand.");

        assertThat(lines.get(fresh).get("quantity").isNull()).isTrue();
        assertThat(lines.get(fresh).get("confidence").asText()).isEqualTo("NONE");

        // Suggestions publish nothing by themselves.
        assertThat(today().get("offers")).isEmpty();
    }

    @Test
    void anApprovedPriceIsUsedForTodayOnly() throws Exception {
        long bread = template("Bread");
        Map<String, Object> cheaper = line(bread, 3, 3);
        cheaper.put("discountedPrice", 6.5);
        assertThat(publish(true, cheaper).get(0).get("discountedPrice").decimalValue()).isEqualByComparingTo("6.50");
        // The product keeps its usual price.
        assertThat(templateRepository.findById(bread).orElseThrow().getDiscountedPrice()).isEqualByComparingTo("8.00");

        Map<String, Object> notADiscount = line(template("Cake"), 3, 3);
        notADiscount.put("discountedPrice", 25);
        call(post("/api/v1/stores/" + storeId + "/offers"), owner, Map.of("offers", List.of(notADiscount), "publish", true))
                .andExpect(status().isBadRequest());
    }

    @Test
    void sellingFastIsFlagged() throws Exception {
        long id = publish(true, line(template("Bread"), 5, 3)).get(0).get("id").asLong();
        call(post("/api/v1/food-items/" + id + "/reservations"), register("CONSUMER"), Map.of("quantity", 4))
                .andExpect(status().isCreated());

        JsonNode alerts = today().get("alerts");
        assertThat(alerts).anyMatch(a -> a.get("type").asText().equals("LOW_STOCK") && a.get("itemId").asLong() == id
                && a.get("message").asText().equals("“Bread” is selling fast: 1 of 5 left. Add more if you have them."));
    }

    // ---------- access ----------

    @Test
    void onlyTheStoreOwnerCanLaunchDropsAndOnlyCustomersHaveAnInbox() throws Exception {
        long bread = template("Bread");
        String customer = register("CONSUMER");
        String otherOwner = register("STORE_OWNER");

        call(post("/api/v1/stores/" + storeId + "/drops"), customer, drop(bread, 1)).andExpect(status().isForbidden());
        call(post("/api/v1/stores/" + storeId + "/drops"), otherOwner, drop(bread, 1)).andExpect(status().isForbidden());
        call(get("/api/v1/stores/" + storeId + "/autopilot"), otherOwner, null).andExpect(status().isForbidden());
        call(post("/api/v1/stores/" + storeId + "/drops"), null, drop(bread, 1)).andExpect(status().isUnauthorized());
        call(get("/api/v1/me/notifications"), owner, null).andExpect(status().isForbidden());
        call(put("/api/v1/me/drop-alerts"), customer, Map.of("enabled", true, "latitude", lat, "longitude", lng,
                "radiusKm", 50, "maxPerDay", 3)).andExpect(status().isBadRequest());
    }

    @Test
    void aSuspendedStoreCannotLaunchDrops() throws Exception {
        long bread = template("Bread");
        var store = storeRepository.findById(storeId).orElseThrow();
        store.setActive(false);
        storeRepository.save(store);

        call(post("/api/v1/stores/" + storeId + "/drops"), owner, drop(bread, 1)).andExpect(status().isConflict());
    }
}
