package com.foodrescue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.foodrescue.domain.Order;
import com.foodrescue.domain.OrderStatus;
import com.foodrescue.repository.OrderRepository;
import com.foodrescue.service.GeocodingService;
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
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** The no-show rule (missed pickups) and administrators blocking accounts. */
@SpringBootTest(properties = "foodrescue.geocoding.enabled=false")
@AutoConfigureMockMvc
class NoShowAndBlockingIntegrationTest {

    private static final String PASSWORD = "demo1234";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired OrderRepository orderRepository;
    @MockitoBean GeocodingService geocodingService;

    private ResultActions call(MockHttpServletRequestBuilder request, String token, Object body) throws Exception {
        if (token != null) request.header("Authorization", "Bearer " + token);
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        return mvc.perform(request);
    }

    private JsonNode read(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString());
    }

    /** {token, email, id} of a new account. */
    private String[] register(String role) throws Exception {
        String email = role.toLowerCase() + "-" + UUID.randomUUID() + "@test.ro";
        JsonNode body = read(call(post("/api/v1/users"), null, Map.of("fullName", "Test " + role, "email", email,
                "password", PASSWORD, "role", role, "acceptTerms", true)).andExpect(status().isCreated()));
        return new String[]{body.get("token").asText(), email, body.get("user").get("id").asText()};
    }

    private String admin() throws Exception {
        return read(call(post("/api/v1/auth/login"), null, Map.of("email", "admin@example.com", "password", PASSWORD))).get("token").asText();
    }

    private long[] storeWithItem(String ownerToken) throws Exception {
        long storeId = read(call(post("/api/v1/stores"), ownerToken,
                Map.of("name", "No-show Shop", "address", "Strada Test 2, Timisoara", "latitude", 45.7510, "longitude", 21.2210))
                .andExpect(status().isCreated())).get("id").asLong();
        long itemId = read(call(post("/api/v1/stores/" + storeId + "/food-items"), ownerToken, Map.of("name", "Soup",
                "originalPrice", 20, "discountedPrice", 8, "quantityAvailable", 30, "payItForwardQuantity", 0,
                "expiresAt", Instant.now().plus(Duration.ofHours(5)).toString()))
                .andExpect(status().isCreated())).get("id").asLong();
        return new long[]{storeId, itemId};
    }

    private long reserve(String token, long itemId) throws Exception {
        return read(call(post("/api/v1/food-items/" + itemId + "/reservations"), token, Map.of("quantity", 1))
                .andExpect(status().isCreated())).get("orderId").asLong();
    }

    /** As if the pickup time ended without the customer coming (what ReservationExpiryJob does). */
    private void missPickup(long orderId, Duration ago) {
        Order o = orderRepository.findById(orderId).orElseThrow();
        o.setStatus(OrderStatus.EXPIRED);
        o.setPickupDeadline(Instant.now().minus(ago));
        orderRepository.save(o);
    }

    @Test
    void missedPickupsFirstWarnThenLimitThenPause() throws Exception {
        String customer = register("CONSUMER")[0];
        long item = storeWithItem(register("STORE_OWNER")[0])[1];
        assertThat(read(call(get("/api/v1/orders/standing"), customer, null)).get("level").asText()).isEqualTo("OK");

        missPickup(reserve(customer, item), Duration.ofDays(10));
        JsonNode warned = read(call(get("/api/v1/orders/standing"), customer, null));
        assertThat(warned.get("level").asText()).isEqualTo("WARNED");
        assertThat(warned.get("noShows").asInt()).isEqualTo(1);

        // Cancelling in time never counts.
        call(post("/api/v1/orders/" + reserve(customer, item) + "/cancel"), customer, null).andExpect(status().isOk());

        missPickup(reserve(customer, item), Duration.ofDays(5));
        missPickup(reserve(customer, item), Duration.ofDays(1));
        JsonNode limited = read(call(get("/api/v1/orders/standing"), customer, null));
        assertThat(limited.get("level").asText()).isEqualTo("LIMITED");
        // The limit lifts when the oldest of the three leaves the 30-day window: 20 days from now.
        Instant until = Instant.parse(limited.get("until").asText());
        assertThat(Duration.between(Instant.now(), until).toDays()).isEqualTo(19);

        long open = reserve(customer, item); // one at a time is still fine
        call(post("/api/v1/food-items/" + item + "/reservations"), customer, Map.of("quantity", 1))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("one reservation at a time")));

        missPickup(open, Duration.ofHours(2));
        missPickup(reserve(customer, item), Duration.ofHours(1));
        assertThat(read(call(get("/api/v1/orders/standing"), customer, null)).get("level").asText()).isEqualTo("PAUSED");
        call(post("/api/v1/food-items/" + item + "/reservations"), customer, Map.of("quantity", 1))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.startsWith("Reserving is paused until ")));
    }

    @Test
    void oldMissedPickupsNoLongerCount() throws Exception {
        String customer = register("CONSUMER")[0];
        long item = storeWithItem(register("STORE_OWNER")[0])[1];
        for (int i = 0; i < 5; i++) missPickup(reserve(customer, item), Duration.ofDays(31 + i));
        assertThat(read(call(get("/api/v1/orders/standing"), customer, null)).get("level").asText()).isEqualTo("OK");
        reserve(customer, item);
    }

    @Test
    void aBlockedCustomerCannotSignInAndLosesOpenReservations() throws Exception {
        String admin = admin();
        String[] customer = register("CONSUMER");
        long item = storeWithItem(register("STORE_OWNER")[0])[1];
        long open = reserve(customer[0], item);
        missPickup(reserve(customer[0], item), Duration.ofDays(2));

        JsonNode listed = read(call(get("/api/v1/admin/users").param("q", customer[1].substring(0, 20).toUpperCase()), admin, null)
                .andExpect(status().isOk()));
        assertThat(listed).hasSize(1);
        assertThat(listed.get(0).get("noShows").asInt()).isEqualTo(1);

        call(post("/api/v1/admin/users/" + customer[2] + "/block"), admin, Map.of("reason", "Keeps reserving without coming"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.blockedAt").exists())
                .andExpect(jsonPath("$.blockedReason").value("Keeps reserving without coming"));
        assertThat(orderRepository.findById(open).orElseThrow().getStatus()).isEqualTo(OrderStatus.CANCELLED);
        call(get("/api/v1/users/me"), customer[0], null).andExpect(status().isUnauthorized());
        call(post("/api/v1/auth/login"), null, Map.of("email", customer[1], "password", PASSWORD))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.startsWith("This account is blocked")));
        // A wrong password still gets the usual answer, so blocking reveals nothing to others.
        call(post("/api/v1/auth/login"), null, Map.of("email", customer[1], "password", "wrong-one"))
                .andExpect(status().isUnauthorized());

        call(post("/api/v1/admin/users/" + customer[2] + "/unblock"), admin, null)
                .andExpect(status().isOk()).andExpect(jsonPath("$.blockedAt").doesNotExist());
        call(post("/api/v1/auth/login"), null, Map.of("email", customer[1], "password", PASSWORD)).andExpect(status().isOk());
    }

    @Test
    void blockingAShopOwnerSuspendsTheirStores() throws Exception {
        String admin = admin();
        String[] owner = register("STORE_OWNER");
        long storeId = storeWithItem(owner[0])[0];
        call(post("/api/v1/admin/users/" + owner[2] + "/block"), admin, Map.of()).andExpect(status().isOk());
        JsonNode stores = read(call(get("/api/v1/admin/stores"), admin, null));
        boolean active = true;
        for (JsonNode s : stores) if (s.get("id").asLong() == storeId) active = s.get("active").asBoolean();
        assertThat(active).isFalse();
    }

    @Test
    void administratorsCannotBeBlockedAndOnlyAdminsMayBlock() throws Exception {
        String admin = admin();
        String adminId = read(call(get("/api/v1/users/me"), admin, null)).get("id").asText();
        call(post("/api/v1/admin/users/" + adminId + "/block"), admin, Map.of()).andExpect(status().isBadRequest());
        String[] customer = register("CONSUMER");
        call(post("/api/v1/admin/users/" + customer[2] + "/block"), customer[0], Map.of()).andExpect(status().isForbidden());
        call(get("/api/v1/admin/users"), customer[0], null).andExpect(status().isForbidden());
    }
}
