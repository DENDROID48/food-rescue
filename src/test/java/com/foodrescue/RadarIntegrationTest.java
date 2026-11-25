package com.foodrescue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.foodrescue.domain.Notification;
import com.foodrescue.domain.NotificationKind;
import com.foodrescue.repository.NotificationRepository;
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

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
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
 * Rescue Radar over HTTP: favourites, food types, "For you" picks, favourite alerts, the anti-spam
 * back-off, the daily digest, preferences and push subscriptions.
 *
 * <p>Each test uses its own spot, far from other tests' stores and customers.</p>
 */
@SpringBootTest(properties = "foodrescue.geocoding.enabled=false")
@AutoConfigureMockMvc
class RadarIntegrationTest {

    private static final DateTimeFormatter HHMM = DateTimeFormatter.ofPattern("HH:mm");
    private static final AtomicInteger SPOT = new AtomicInteger();

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired NotificationRepository notificationRepository;
    @Autowired UserRepository userRepository;
    @Autowired AlertService alertService;
    @MockitoBean GeocodingService geocodingService;

    String owner;
    long storeId;
    double lat, lng;
    ZoneId zone;
    LocalTime local;

    // ---------- helpers ----------

    private String[] registerWithEmail(String role) throws Exception {
        String email = role.toLowerCase() + "-" + UUID.randomUUID() + "@test.ro";
        String body = mvc.perform(post("/api/v1/users").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("fullName", "Test " + role, "email", email, "password", "demo1234", "role", role))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return new String[]{json.readTree(body).get("token").asText(), email};
    }

    private String register(String role) throws Exception {
        return registerWithEmail(role)[0];
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

    private long store(String token, String name, double latitude, double longitude) throws Exception {
        return read(call(post("/api/v1/stores"), token, Map.of("name", name, "address", "Strada 3",
                "latitude", latitude, "longitude", longitude, "timeZone", zone.getId()))
                .andExpect(status().isCreated())).get("id").asLong();
    }

    private long template(String token, long store, String name, List<String> tags) throws Exception {
        Map<String, Object> body = new HashMap<>(Map.of("name", name, "kind", "PRODUCT", "originalPrice", 20,
                "discountedPrice", 8, "pickupStart", "06:00", "pickupEnd", "23:00", "tags", tags));
        return read(call(post("/api/v1/stores/" + store + "/templates"), token, body).andExpect(status().isCreated())).get("id").asLong();
    }

    private Map<String, Object> line(long templateId) {
        return Map.of("templateId", templateId, "quantityAvailable", 3, "payItForwardQuantity", 0,
                "pickupStart", local.minusHours(1).format(HHMM), "pickupEnd", local.plusHours(3).format(HHMM));
    }

    private JsonNode publish(boolean live, long store, String token, Object... lines) throws Exception {
        return read(call(post("/api/v1/stores/" + store + "/offers"), token, Map.of("offers", List.of(lines), "publish", live))
                .andExpect(status().isCreated()));
    }

    private void profile(String token, Map<String, Object> changes) throws Exception {
        Map<String, Object> body = new HashMap<>(Map.of("personalised", true, "interests", List.of(),
                "notifyFavourites", false, "timeZone", "Europe/Bucharest"));
        body.putAll(changes);
        call(put("/api/v1/me/radar-profile"), token, body).andExpect(status().isOk());
    }

    private void favouriteProduct(String token, long templateId) throws Exception {
        call(put("/api/v1/me/favourites/products/" + templateId), token, null).andExpect(status().isNoContent());
    }

    private JsonNode inbox(String token) throws Exception {
        return read(call(get("/api/v1/me/notifications"), token, null).andExpect(status().isOk())).get("items");
    }

    private JsonNode radar(String token) throws Exception {
        return read(call(get("/api/v1/me/radar?lat=" + lat + "&lng=" + lng + "&radiusKm=2"), token, null).andExpect(status().isOk()));
    }

    private Map<String, Object> drop(long templateId) {
        return Map.of("templateId", templateId, "quantityAvailable", 2, "durationMinutes", 30, "alertCustomers", true);
    }

    @BeforeEach
    void createStore() throws Exception {
        zone = daytimeZone();
        local = ZonedDateTime.now(zone).toLocalTime();
        lat = 20.0 + SPOT.incrementAndGet() * 1.5;
        lng = 60.0;
        owner = register("STORE_OWNER");
        storeId = store(owner, "Radar Bakery", lat, lng);
    }

    // ---------- food types ----------

    @Test
    void foodTypesTravelFromTheProductToTheOfferAndSearch() throws Exception {
        long bread = template(owner, storeId, "Rye bread", List.of("BAKERY", "VEGAN"));
        long id = publish(true, storeId, owner, line(bread)).get(0).get("id").asLong();

        JsonNode found = json.readTree(mvc.perform(get("/api/v1/food-items/nearby?lat=" + lat + "&lng=" + lng + "&radiusKm=2"))
                .andReturn().getResponse().getContentAsString());
        JsonNode item = found.get(0);
        assertThat(item.get("id").asLong()).isEqualTo(id);
        assertThat(item.get("templateId").asLong()).isEqualTo(bread);
        assertThat(item.get("tags").toString()).isEqualTo("[\"BAKERY\",\"VEGAN\"]");

        call(post("/api/v1/stores/" + storeId + "/templates"), owner, Map.of("name", "X", "kind", "PRODUCT", "originalPrice", 5,
                "discountedPrice", 2, "pickupStart", "06:00", "pickupEnd", "23:00", "tags", List.of("CHEAP")))
                .andExpect(status().isBadRequest());
    }

    // ---------- favourites and picks ----------

    @Test
    void favouritesAreKeptAndDriveTheForYouPicks() throws Exception {
        long bread = template(owner, storeId, "Sourdough", List.of("BAKERY"));
        long soup = template(owner, storeId, "Soup", List.of("READY_MEALS"));
        long cake = template(owner, storeId, "Cake", List.of("SWEETS"));
        JsonNode offers = publish(true, storeId, owner, line(bread), line(soup), line(cake));
        String customer = register("CONSUMER");

        // A brand-new customer gets no picks: nothing personal is known yet.
        assertThat(radar(customer).get("picks")).isEmpty();

        favouriteProduct(customer, bread);
        favouriteProduct(customer, bread); // idempotent
        profile(customer, Map.of("interests", List.of("READY_MEALS")));

        JsonNode r = radar(customer);
        assertThat(r.get("favouriteTemplateIds").toString()).isEqualTo("[" + bread + "]");
        JsonNode picks = r.get("picks");
        assertThat(picks).hasSize(2);
        assertThat(picks.get(0).get("itemId").asLong()).isEqualTo(offers.get(0).get("id").asLong());
        assertThat(picks.get(0).get("reasons").get(0).asText()).isEqualTo("One of your favourites");
        assertThat(picks.get(1).get("reasons").get(0).asText()).isEqualTo("Matches your interest in ready meals");

        call(put("/api/v1/me/favourites/stores/" + storeId), customer, null).andExpect(status().isNoContent());
        JsonNode favs = read(call(get("/api/v1/me/favourites"), customer, null));
        assertThat(favs.get("stores").get(0).get("name").asText()).isEqualTo("Radar Bakery");
        assertThat(favs.get("products").get(0).get("name").asText()).isEqualTo("Sourdough");
        assertThat(radar(customer).get("picks")).hasSize(3); // the whole shop is followed now

        // Turning personalisation off removes the picks but keeps the hearts.
        profile(customer, Map.of("personalised", false));
        JsonNode off = radar(customer);
        assertThat(off.get("personalised").asBoolean()).isFalse();
        assertThat(off.get("picks")).isEmpty();
        assertThat(off.get("favouriteStoreIds").toString()).isEqualTo("[" + storeId + "]");

        call(delete("/api/v1/me/favourites/products/" + bread), customer, null).andExpect(status().isNoContent());
        call(delete("/api/v1/me/favourites/stores/" + storeId), customer, null).andExpect(status().isNoContent());
        assertThat(read(call(get("/api/v1/me/favourites"), customer, null)).get("products")).isEmpty();
        call(put("/api/v1/me/favourites/stores/999999"), customer, null).andExpect(status().isNotFound());
    }

    // ---------- favourite alerts ----------

    @Test
    void favouritesOnOfferBecomeOneAlertForThoseWhoAskedForIt() throws Exception {
        long bread = template(owner, storeId, "Sourdough", List.of());
        long croissant = template(owner, storeId, "Croissants", List.of());
        String wantsAlerts = register("CONSUMER");
        String quiet = register("CONSUMER");
        for (String c : List.of(wantsAlerts, quiet)) {
            favouriteProduct(c, bread);
            favouriteProduct(c, croissant);
        }
        profile(wantsAlerts, Map.of("notifyFavourites", true));

        publish(true, storeId, owner, line(bread), line(croissant));

        JsonNode alerts = inbox(wantsAlerts);
        assertThat(alerts).hasSize(1);
        assertThat(alerts.get(0).get("title").asText()).isEqualTo("2 of your favourites at Radar Bakery");
        assertThat(alerts.get(0).get("body").asText()).startsWith("Sourdough, Croissants are on offer · pickup ");
        assertThat(inbox(quiet)).isEmpty();
    }

    @Test
    void aDraftAlertsOnlyWhenItGoesLiveAndADropReachesFollowersAnywhere() throws Exception {
        long bread = template(owner, storeId, "Sourdough", List.of());
        String fan = register("CONSUMER");
        favouriteProduct(fan, bread);
        profile(fan, Map.of("notifyFavourites", true));

        long draft = publish(false, storeId, owner, line(bread)).get(0).get("id").asLong();
        assertThat(inbox(fan)).isEmpty();
        call(put("/api/v1/stores/" + storeId + "/offers/" + draft + "/status"), owner, Map.of("status", "ACTIVE")).andExpect(status().isOk());
        assertThat(inbox(fan)).hasSize(1);
        assertThat(inbox(fan).get(0).get("title").asText()).isEqualTo("Your favourite at Radar Bakery");

        // A follower of another shop, far from it and without nearby drop alerts, hears about its drop.
        String otherOwner = register("STORE_OWNER");
        long farStore = store(otherOwner, "Far Deli", lat, lng + 5);
        long soup = template(otherOwner, farStore, "Soup", List.of());
        call(put("/api/v1/me/favourites/stores/" + farStore), fan, null).andExpect(status().isNoContent());
        JsonNode result = read(call(post("/api/v1/stores/" + farStore + "/drops"), otherOwner, drop(soup)).andExpect(status().isCreated()));
        assertThat(result.get("notified").asInt()).isEqualTo(1);
        assertThat(inbox(fan).get(0).get("title").asText()).isEqualTo("Rescue Drop at Far Deli");
    }

    @Test
    void customersWhoIgnoreAlertsGetFewer() throws Exception {
        String[] customer = registerWithEmail("CONSUMER");
        Long userId = userRepository.findByEmail(customer[1]).orElseThrow().getId();
        call(put("/api/v1/me/drop-alerts"), customer[0], Map.of("enabled", true, "latitude", lat, "longitude", lng,
                "radiusKm", 5, "maxPerDay", 3)).andExpect(status().isOk());
        // Five unopened alerts from earlier this week.
        List<Notification> old = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            Notification n = new Notification();
            n.setUserId(userId);
            n.setKind(NotificationKind.DROP);
            n.setTitle("Old alert");
            n.setBody("…");
            n.setCreatedAt(Instant.now().minus(Duration.ofDays(2)).minusSeconds(i));
            old.add(n);
        }
        notificationRepository.saveAll(old);
        assertThat(read(call(get("/api/v1/me/radar-profile"), customer[0], null)).get("throttled").asBoolean()).isTrue();

        // Two drops from two shops: normally both would arrive (limit 3); now only one (3 / 2).
        long a = template(owner, storeId, "Bread", List.of());
        call(post("/api/v1/stores/" + storeId + "/drops"), owner, drop(a)).andExpect(status().isCreated());
        String owner2 = register("STORE_OWNER");
        long store2 = store(owner2, "Second Shop", lat + 0.002, lng);
        long b = template(owner2, store2, "Cake", List.of());
        call(post("/api/v1/stores/" + store2 + "/drops"), owner2, drop(b)).andExpect(status().isCreated());

        assertThat(inbox(customer[0])).filteredOn(n -> !n.get("title").asText().equals("Old alert")).hasSize(1);

        // Opening the inbox (reading) lifts the back-off.
        call(post("/api/v1/me/notifications/read"), customer[0], null).andExpect(status().isNoContent());
        assertThat(read(call(get("/api/v1/me/radar-profile"), customer[0], null)).get("throttled").asBoolean()).isFalse();
    }

    // ---------- daily digest ----------

    @Test
    void theDigestSummarisesWhatIsNearOncePerDay() throws Exception {
        long bread = template(owner, storeId, "Sourdough", List.of());
        long soup = template(owner, storeId, "Soup", List.of());
        long cake = template(owner, storeId, "Cake", List.of());
        publish(true, storeId, owner, line(bread), line(soup), line(cake));
        String customer = register("CONSUMER");
        favouriteProduct(customer, cake);
        call(put("/api/v1/me/drop-alerts"), customer, Map.of("enabled", false, "latitude", lat, "longitude", lng,
                "radiusKm", 2, "maxPerDay", 3)).andExpect(status().isOk());
        LocalTime now = LocalTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MINUTES);
        profile(customer, Map.of("timeZone", "UTC", "digestTime", now.minusMinutes(now.getHour() == 0 && now.getMinute() == 0 ? 0 : 1).toString()));

        alertService.sendDigests();
        alertService.sendDigests();

        JsonNode alerts = inbox(customer);
        assertThat(alerts).hasSize(1);
        assertThat(alerts.get(0).get("title").asText()).isEqualTo("3 offers near you today");
        // The favourite comes first.
        assertThat(alerts.get(0).get("body").asText()).startsWith("Cake at Radar Bakery, ").endsWith(" and 1 more · within 2 km");
    }

    // ---------- preferences and push ----------

    @Test
    void preferencesAreValidated() throws Exception {
        String customer = register("CONSUMER");
        JsonNode defaults = read(call(get("/api/v1/me/radar-profile"), customer, null));
        assertThat(defaults.get("personalised").asBoolean()).isTrue();
        assertThat(defaults.get("notifyFavourites").asBoolean()).isFalse();

        call(put("/api/v1/me/radar-profile"), customer, Map.of("personalised", true, "notifyFavourites", true,
                "quietStart", "22:00", "timeZone", "Europe/Bucharest")).andExpect(status().isBadRequest());
        call(put("/api/v1/me/radar-profile"), customer, Map.of("personalised", true, "notifyFavourites", true,
                "timeZone", "Mars/Olympus")).andExpect(status().isBadRequest());
        call(put("/api/v1/me/radar-profile"), customer, Map.of("personalised", true, "notifyFavourites", true,
                "quietStart", "22:00", "quietEnd", "07:00", "digestTime", "17:30", "interests", List.of("BAKERY"),
                "timeZone", "Europe/Bucharest"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.quietEnd").value("07:00:00"))
                .andExpect(jsonPath("$.interests[0]").value("BAKERY"));
    }

    @Test
    void theOfferToGetVerifiedCanBeTurnedOffAndBackOn() throws Exception {
        String customer = register("CONSUMER");
        assertThat(read(call(get("/api/v1/me/radar-profile"), customer, null)).get("freeItemsOffer").asBoolean()).isTrue();

        call(put("/api/v1/me/free-items-offer"), customer, Map.of("show", false))
                .andExpect(status().isOk()).andExpect(jsonPath("$.freeItemsOffer").value(false));
        // Saving the other settings without the field keeps the choice.
        call(put("/api/v1/me/radar-profile"), customer, Map.of("personalised", true, "notifyFavourites", false,
                "timeZone", "Europe/Bucharest")).andExpect(jsonPath("$.freeItemsOffer").value(false));
        // Settings can turn it back on.
        call(put("/api/v1/me/radar-profile"), customer, Map.of("personalised", true, "notifyFavourites", false,
                "timeZone", "Europe/Bucharest", "freeItemsOffer", true)).andExpect(jsonPath("$.freeItemsOffer").value(true));

        call(put("/api/v1/me/free-items-offer"), customer, Map.of()).andExpect(status().isBadRequest());
        call(put("/api/v1/me/free-items-offer"), register("STORE_OWNER"), Map.of("show", false)).andExpect(status().isForbidden());
    }

    @Test
    void pushSubscriptionsAcceptOnlyRealPushServices() throws Exception {
        String customer = register("CONSUMER");
        mvc.perform(get("/api/v1/push/public-key")).andExpect(status().isOk())
                .andExpect(jsonPath("$.publicKey").value(org.hamcrest.Matchers.matchesPattern("[A-Za-z0-9_-]{87}")));

        byte[] key = new byte[65];
        key[0] = 4;
        String p256dh = Base64.getUrlEncoder().withoutPadding().encodeToString(key);
        String auth = Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[16]);
        String endpoint = "https://fcm.googleapis.com/fcm/send/" + UUID.randomUUID();
        Map<String, Object> subscription = Map.of("endpoint", endpoint, "keys", Map.of("p256dh", p256dh, "auth", auth));

        call(post("/api/v1/me/push-subscriptions"), customer, subscription).andExpect(status().isNoContent());
        assertThat(read(call(get("/api/v1/me/radar-profile"), customer, null)).get("pushDevices").asLong()).isEqualTo(1);

        call(post("/api/v1/me/push-subscriptions"), customer, Map.of("endpoint", "https://attacker.example/hook",
                "keys", Map.of("p256dh", p256dh, "auth", auth))).andExpect(status().isBadRequest());
        call(post("/api/v1/me/push-subscriptions"), customer, Map.of("endpoint", endpoint,
                "keys", Map.of("p256dh", "short", "auth", auth))).andExpect(status().isBadRequest());
        call(post("/api/v1/me/push-subscriptions"), owner, subscription).andExpect(status().isForbidden());

        // The same browser signing in as someone else moves the subscription to them.
        String other = register("CONSUMER");
        call(post("/api/v1/me/push-subscriptions"), other, subscription).andExpect(status().isNoContent());
        assertThat(read(call(get("/api/v1/me/radar-profile"), customer, null)).get("pushDevices").asLong()).isZero();

        call(delete("/api/v1/me/push-subscriptions"), other, Map.of("endpoint", endpoint)).andExpect(status().isNoContent());
        assertThat(read(call(get("/api/v1/me/radar-profile"), other, null)).get("pushDevices").asLong()).isZero();
    }
}
