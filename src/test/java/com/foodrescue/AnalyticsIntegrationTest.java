package com.foodrescue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.foodrescue.domain.FoodItem;
import com.foodrescue.domain.Order;
import com.foodrescue.domain.OrderStatus;
import com.foodrescue.domain.OrderType;
import com.foodrescue.domain.ProductTemplate;
import com.foodrescue.domain.User;
import com.foodrescue.repository.FoodItemRepository;
import com.foodrescue.repository.OrderRepository;
import com.foodrescue.repository.ProductTemplateRepository;
import com.foodrescue.repository.StoreRepository;
import com.foodrescue.repository.UserRepository;
import com.foodrescue.service.AlertService;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Smart Analytics over HTTP (figures read with JDBC), the CSV export, the admin overview, weights,
 * published quantities and pickup reminders.
 */
@SpringBootTest(properties = "foodrescue.geocoding.enabled=false")
@AutoConfigureMockMvc
class AnalyticsIntegrationTest {

    private static final DateTimeFormatter HHMM = DateTimeFormatter.ofPattern("HH:mm");
    private static final AtomicInteger SPOT = new AtomicInteger();

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired FoodItemRepository foodItemRepository;
    @Autowired OrderRepository orderRepository;
    @Autowired ProductTemplateRepository templateRepository;
    @Autowired StoreRepository storeRepository;
    @Autowired UserRepository userRepository;
    @Autowired AlertService alertService;
    @MockitoBean GeocodingService geocodingService;

    String owner;
    long storeId;
    ZoneId zone;

    private String[] register(String role) throws Exception {
        String email = role.toLowerCase() + "-" + UUID.randomUUID() + "@test.ro";
        String body = mvc.perform(post("/api/v1/users").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("fullName", "Test " + role, "email", email, "password", "demo1234", "role", role, "acceptTerms", true))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return new String[]{json.readTree(body).get("token").asText(), email};
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

    private long template(String name, Integer grams) throws Exception {
        Map<String, Object> body = new HashMap<>(Map.of("name", name, "kind", "PRODUCT", "originalPrice", 20,
                "discountedPrice", 8, "pickupStart", "06:00", "pickupEnd", "23:00"));
        if (grams != null) body.put("unitWeightGrams", grams);
        return read(call(post("/api/v1/stores/" + storeId + "/templates"), owner, body).andExpect(status().isCreated())).get("id").asLong();
    }

    /** A finished offer {@code daysAgo} days ago: published, some collected, some not. */
    private FoodItem pastOffer(long templateId, int daysAgo, int published, int collected, int noShows, User customer) {
        ProductTemplate t = templateRepository.findById(templateId).orElseThrow();
        Instant end = Instant.now().minus(Duration.ofDays(daysAgo)).minus(Duration.ofMinutes(5));
        FoodItem f = new FoodItem();
        f.setStore(storeRepository.findById(storeId).orElseThrow());
        f.setTemplate(t);
        f.setName(t.getName());
        f.setOriginalPrice(new BigDecimal("20.00"));
        f.setDiscountedPrice(new BigDecimal("8.00"));
        f.setUnitWeightGrams(t.getUnitWeightGrams());
        f.setQuantityAvailable(published - collected - noShows);
        f.setQuantityPublished(published);
        f.setFreePublished(0);
        f.setPickupStartsAt(end.minus(Duration.ofHours(2)));
        f.setExpiresAt(end);
        f.setCreatedAt(end.minus(Duration.ofHours(6)));
        f = foodItemRepository.save(f);
        if (collected > 0) order(f, customer, collected, OrderStatus.PICKED_UP, end.minus(Duration.ofHours(3)), end.minus(Duration.ofMinutes(10)));
        if (noShows > 0) order(f, customer, noShows, OrderStatus.EXPIRED, end.minus(Duration.ofHours(3)), null);
        return f;
    }

    private Order order(FoodItem f, User customer, int qty, OrderStatus status, Instant created, Instant pickedUp) {
        Order o = new Order();
        o.setUser(customer);
        o.setFoodItem(f);
        o.setQuantity(qty);
        o.setTotalPrice(f.getDiscountedPrice().multiply(BigDecimal.valueOf(qty)));
        o.setType(OrderType.PURCHASE);
        o.setStatus(status);
        o.setPickupCode(String.valueOf(100000 + (int) (Math.random() * 899999)));
        o.setPickupDeadline(f.getExpiresAt());
        o.setCreatedAt(created);
        o.setPickedUpAt(pickedUp);
        return orderRepository.save(o);
    }

    private User user(String email) {
        return userRepository.findByEmail(email).orElseThrow();
    }

    @BeforeEach
    void createStore() throws Exception {
        zone = daytimeZone();
        owner = register("STORE_OWNER")[0];
        storeId = read(call(post("/api/v1/stores"), owner, Map.of("name", "Insight Bakery", "address", "Strada 4",
                "latitude", 50.0 + SPOT.incrementAndGet(), "longitude", -100.0, "timeZone", zone.getId()))
                .andExpect(status().isCreated())).get("id").asLong();
    }

    @Test
    void insightsSumUpTheStoresHistory() throws Exception {
        long bread = template("Sourdough", 800);
        long cake = template("Cake", null);
        User customer = user("student@example.com");
        for (int d = 1; d <= 3; d++) pastOffer(bread, d, 6, 6, 0, customer);        // sells out
        for (int d = 1; d <= 3; d++) pastOffer(cake, d, 10, 3, 1, customer);        // mostly left over

        JsonNode r = read(call(get("/api/v1/stores/" + storeId + "/insights?days=7"), owner, null).andExpect(status().isOk()));

        JsonNode t = r.get("totals");
        assertThat(t.get("offered").asLong()).isEqualTo(48);
        assertThat(t.get("collected").asLong()).isEqualTo(27);
        assertThat(t.get("leftover").asLong()).isEqualTo(18);
        assertThat(t.get("noShows").asLong()).isEqualTo(3);
        assertThat(t.get("sellThroughPercent").asInt()).isEqualTo(56);
        assertThat(t.get("kgRescued").asDouble()).isEqualTo(14.4);   // 18 loaves × 0.8 kg
        assertThat(t.get("takings").decimalValue()).isEqualByComparingTo("216.00");
        assertThat(r.get("daily")).hasSize(7);

        JsonNode products = r.get("products");
        assertThat(products).anyMatch(p -> p.get("name").asText().equals("Sourdough") && p.get("flag").asText().equals("Sells out"));
        assertThat(products).anyMatch(p -> p.get("name").asText().equals("Cake") && p.get("flag").asText().startsWith("Often left over"));
        assertThat(r.get("timing").get("pickupsMeasured").asInt()).isEqualTo(6);
        assertThat(r.get("timing").get("pickupsByQuarter").get(3).asInt()).isEqualTo(6); // 10 minutes before the end

        List<String> types = r.get("recommendations").findValuesAsText("type");
        assertThat(types).contains("SELLS_OUT", "LEFT_OVER", "ADD_WEIGHTS");
        assertThat(r.get("forecast")).anyMatch(f -> f.get("name").asText().equals("Sourdough") && f.get("days").size() == 7);
        // Fewer than 5 followers: hidden, not shown as a number.
        assertThat(r.get("audience").get("followers").isNull()).isTrue();
    }

    @Test
    void theCsvExportHasOneLinePerOfferAndIsSafeToOpen() throws Exception {
        long bread = template("=SUM(A1)", 500);
        pastOffer(bread, 1, 5, 4, 0, user("student@example.com"));

        String csv = call(get("/api/v1/stores/" + storeId + "/insights/export.csv?days=7"), owner, null)
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("attachment")))
                .andReturn().getResponse().getContentAsString();
        String[] lines = csv.split("\n");
        assertThat(lines[0]).startsWith("date,product,rescue_drop,offered,collected");
        assertThat(lines).hasSize(2);
        assertThat(lines[1]).contains("\"'=SUM(A1)\",false,5,4,0,0,1,8.00,32.00,2.0");
    }

    @Test
    void onlyTheOwnerSeesInsightsAndOnlyAdminsTheOverview() throws Exception {
        String other = register("STORE_OWNER")[0];
        String customer = register("CONSUMER")[0];
        call(get("/api/v1/stores/" + storeId + "/insights"), other, null).andExpect(status().isForbidden());
        call(get("/api/v1/stores/" + storeId + "/insights"), customer, null).andExpect(status().isForbidden());
        call(get("/api/v1/stores/" + storeId + "/insights?days=3"), owner, null).andExpect(status().isBadRequest());
        call(get("/api/v1/admin/insights"), owner, null).andExpect(status().isForbidden());

        String admin = read(call(post("/api/v1/auth/login"), null, Map.of("email", "admin@example.com", "password", "demo1234"))).get("token").asText();
        JsonNode overview = read(call(get("/api/v1/admin/insights"), admin, null).andExpect(status().isOk()));
        assertThat(overview.get("totals").get("offered").asLong()).isGreaterThanOrEqualTo(0);
        // This test's store has never published, so it is listed as quiet.
        assertThat(overview.get("quiet")).anyMatch(s -> s.get("storeId").asLong() == storeId
                && s.get("reason").asText().equals("Never published"));
    }

    @Test
    void weightsAreValidatedAndCopiedToOffers() throws Exception {
        call(post("/api/v1/stores/" + storeId + "/templates"), owner, Map.of("name", "Heavy", "kind", "PRODUCT", "originalPrice", 20,
                "discountedPrice", 8, "pickupStart", "06:00", "pickupEnd", "23:00", "unitWeightGrams", 60000))
                .andExpect(status().isBadRequest());
        long bread = template("Bread", 750);
        LocalTime local = ZonedDateTime.now(zone).toLocalTime();
        JsonNode offer = read(call(post("/api/v1/stores/" + storeId + "/offers"), owner, Map.of("publish", true, "offers", List.of(
                Map.of("templateId", bread, "quantityAvailable", 5, "payItForwardQuantity", 0,
                        "pickupStart", local.minusHours(1).format(HHMM), "pickupEnd", local.plusHours(3).format(HHMM)))))
                .andExpect(status().isCreated())).get(0);
        assertThat(offer.get("unitWeightGrams").asInt()).isEqualTo(750);

        // Two reserved, then the owner finds one more loaf: published becomes 6 (2 reserved + 4 on the shelf).
        long id = offer.get("id").asLong();
        call(post("/api/v1/food-items/" + id + "/reservations"), register("CONSUMER")[0], Map.of("quantity", 2)).andExpect(status().isCreated());
        call(patch("/api/v1/stores/" + storeId + "/offers/" + id), owner, Map.of("quantityAvailable", 4, "payItForwardQuantity", 0,
                "pickupStart", local.minusHours(1).format(HHMM), "pickupEnd", local.plusHours(3).format(HHMM))).andExpect(status().isOk());
        assertThat(foodItemRepository.findById(id).orElseThrow().getQuantityPublished()).isEqualTo(6);
    }

    @Test
    void pickupRemindersGoOutOnceAndOnlyToThoseWhoWantThem() throws Exception {
        long bread = template("Bread", null);
        String[] wants = register("CONSUMER");
        String[] optedOut = register("CONSUMER");
        call(put("/api/v1/me/radar-profile"), optedOut[0], Map.of("personalised", true, "notifyFavourites", false,
                "timeZone", "Europe/Bucharest", "pickupReminders", false)).andExpect(status().isOk());

        // An offer whose pickup closes in 20 minutes.
        ProductTemplate t = templateRepository.findById(bread).orElseThrow();
        FoodItem f = new FoodItem();
        f.setStore(storeRepository.findById(storeId).orElseThrow());
        f.setTemplate(t);
        f.setName("Bread");
        f.setOriginalPrice(new BigDecimal("20.00"));
        f.setDiscountedPrice(new BigDecimal("8.00"));
        f.setQuantityAvailable(3);
        f.setExpiresAt(Instant.now().plus(Duration.ofMinutes(20)));
        f.setPickupStartsAt(Instant.now().minus(Duration.ofHours(1)));
        f = foodItemRepository.save(f);
        Order early = order(f, user(wants[1]), 2, OrderStatus.RESERVED, Instant.now().minus(Duration.ofHours(2)), null);
        order(f, user(optedOut[1]), 1, OrderStatus.RESERVED, Instant.now().minus(Duration.ofHours(2)), null);
        // Reserved 5 minutes ago: no reminder needed.
        String[] justNow = register("CONSUMER");
        order(f, user(justNow[1]), 1, OrderStatus.RESERVED, Instant.now().minus(Duration.ofMinutes(5)), null);

        alertService.sendPickupReminders();
        alertService.sendPickupReminders();

        JsonNode items = read(call(get("/api/v1/me/notifications"), wants[0], null)).get("items");
        assertThat(items).hasSize(1);
        assertThat(items.get(0).get("kind").asText()).isEqualTo("REMINDER");
        assertThat(items.get(0).get("title").asText()).startsWith("Pick up by ").endsWith(" at Insight Bakery");
        assertThat(items.get(0).get("body").asText()).isEqualTo("Bread × 2 · your pickup code is in My orders")
                .doesNotContain(early.getPickupCode());
        assertThat(orderRepository.findById(early.getId()).orElseThrow().getReminderSentAt()).isNotNull();
        assertThat(read(call(get("/api/v1/me/notifications"), optedOut[0], null)).get("items")).isEmpty();
        assertThat(read(call(get("/api/v1/me/notifications"), justNow[0], null)).get("items")).isEmpty();
        assertThat(read(call(get("/api/v1/me/radar-profile"), wants[0], null)).get("pickupReminders").asBoolean()).isTrue();
    }
}
