package com.foodrescue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.foodrescue.dto.PlaceResponse;
import com.foodrescue.service.GeocodingService;
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

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Starts the whole application (Flyway migrations on H2, security, controllers, services) and
 * calls it over HTTP the way the web page does. Address lookup is replaced by a mock so the
 * tests need no internet access.
 */
@SpringBootTest(properties = "foodrescue.geocoding.enabled=false")
@AutoConfigureMockMvc
class ApiIntegrationTest {

    private static final String PASSWORD = "demo1234";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @MockitoBean GeocodingService geocodingService;

    // ---------- helpers ----------

    private String login(String email) throws Exception {
        String body = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", email, "password", PASSWORD))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("token").asText();
    }

    /** Registers a fresh account with a unique email and returns its token. */
    private String register(String role) throws Exception {
        String email = role.toLowerCase() + "-" + UUID.randomUUID() + "@test.ro";
        String body = mvc.perform(post("/api/v1/users").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "fullName", "Test " + role, "email", email, "password", PASSWORD, "role", role))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("token").asText();
    }

    private ResultActions call(MockHttpServletRequestBuilder request, String token, Object body) throws Exception {
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        }
        return mvc.perform(request);
    }

    private JsonNode read(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString());
    }

    private Map<String, Object> item(String name, int forSale, int free) {
        return Map.of("name", name, "originalPrice", 20, "discountedPrice", 8,
                "quantityAvailable", forSale, "payItForwardQuantity", free,
                "expiresAt", Instant.now().plus(Duration.ofHours(5)).toString());
    }

    private long createStoreWithItem(String ownerToken, int forSale, int free) throws Exception {
        long storeId = read(call(post("/api/v1/stores"), ownerToken,
                Map.of("name", "Test Shop", "address", "Strada Test 1, Timisoara", "latitude", 45.7500, "longitude", 21.2200))
                .andExpect(status().isCreated())).get("id").asLong();
        return read(call(post("/api/v1/stores/" + storeId + "/food-items"), ownerToken, item("Soup", forSale, free))
                .andExpect(status().isCreated())).get("id").asLong();
    }

    // ---------- authentication ----------

    @Test
    void customerSeesTheirImpactStreakAchievementsAndChallenge() throws Exception {
        String student = login("student@example.com");
        mvc.perform(get("/api/v1/me/impact").header("Authorization", "Bearer " + student))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rescues").value(org.hamcrest.Matchers.greaterThan(0)))
                .andExpect(jsonPath("$.kgRescued").value(org.hamcrest.Matchers.greaterThan(0.0)))
                .andExpect(jsonPath("$.achievements.length()").value(9))
                .andExpect(jsonPath("$.achievements[0].code").value("FIRST_RESCUE"))
                .andExpect(jsonPath("$.achievements[0].unlocked").value(true))
                .andExpect(jsonPath("$.challenge.targetUnits").value(400));

        mvc.perform(get("/api/v1/me/impact").header("Authorization", "Bearer " + login("owner@example.com")))
                .andExpect(status().isForbidden());
    }

    @Test
    void ownerGetsAListingSuggestionFromImageLabelsButNothingIsSaved() throws Exception {
        String owner = login("owner@example.com");
        Map<String, Object> body = Map.of("name", "", "labels", java.util.List.of(
                Map.of("className", "French loaf", "probability", 0.78), Map.of("className", "plate", "probability", 0.12)));
        mvc.perform(post("/api/v1/stores/listing-suggestions").header("Authorization", "Bearer " + owner)
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recognised").value(true))
                .andExpect(jsonPath("$.name").value("Bread"))
                .andExpect(jsonPath("$.tags[0]").value("BAKERY"))
                .andExpect(jsonPath("$.seen[1]").value("Plate (12%)"));

        // Customers cannot use it, and the labels are validated.
        mvc.perform(post("/api/v1/stores/listing-suggestions").header("Authorization", "Bearer " + login("student@example.com"))
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
                .andExpect(status().isForbidden());
        Map<String, Object> bad = Map.of("labels", java.util.List.of(Map.of("className", "French loaf", "probability", 1.5)));
        mvc.perform(post("/api/v1/stores/listing-suggestions").header("Authorization", "Bearer " + owner)
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(bad)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void settingsTellThePageTheReservationLimitWithoutSigningIn() throws Exception {
        mvc.perform(get("/api/v1/settings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.maxQuantityPerReservation").value(5));
    }

    @Test
    void wrongPasswordIsRejectedWithoutRevealingWhetherTheEmailExists() throws Exception {
        for (String email : new String[]{"student@example.com", "nobody@example.com"}) {
            mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(Map.of("email", email, "password", "wrong-password"))))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.detail").value("Wrong email or password"));
        }
    }

    @Test
    void protectedEndpointsNeedAValidTokenOfTheRightRole() throws Exception {
        call(get("/api/v1/orders/mine"), null, null).andExpect(status().isUnauthorized());
        call(get("/api/v1/orders/mine"), "not-a-real-token", null).andExpect(status().isUnauthorized());
        call(get("/api/v1/orders/mine"), login("student@example.com"), null).andExpect(status().isOk());
        call(get("/api/v1/orders/mine"), login("owner@example.com"), null).andExpect(status().isForbidden());
        call(get("/api/v1/admin/stores"), login("student@example.com"), null).andExpect(status().isForbidden());
        call(get("/api/v1/food-items/nearby?lat=45.7489&lng=21.2087&radiusKm=3"), null, null).andExpect(status().isOk());
    }

    @Test
    void registrationSignsTheUserInAndHidesThePassword() throws Exception {
        String token = register("CONSUMER");
        JsonNode me = read(call(get("/api/v1/users/me"), token, null).andExpect(status().isOk()));
        assertThat(me.get("role").asText()).isEqualTo("CONSUMER");
        assertThat(me.has("passwordHash")).isFalse();
    }

    // ---------- the main journey ----------

    @Test
    void storePublishesConsumerReservesStoreHandsOver() throws Exception {
        String owner = register("STORE_OWNER");
        String consumer = register("CONSUMER");
        long itemId = createStoreWithItem(owner, 3, 0);

        JsonNode order = read(call(post("/api/v1/food-items/" + itemId + "/reservations"), consumer, Map.of("quantity", 2))
                .andExpect(status().isCreated()));
        assertThat(order.get("totalPrice").decimalValue()).isEqualByComparingTo("16.00");

        long storeId = read(call(get("/api/v1/stores/mine"), owner, null)).get(0).get("id").asLong();
        call(post("/api/v1/stores/" + storeId + "/pickups"), owner, Map.of("pickupCode", order.get("pickupCode").asText()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PICKED_UP"));

        call(get("/api/v1/orders/mine"), consumer, null)
                .andExpect(jsonPath("$[0].status").value("PICKED_UP"));
    }

    @Test
    void anotherOwnerCannotTouchTheStore() throws Exception {
        String owner = register("STORE_OWNER");
        createStoreWithItem(owner, 1, 0);
        long storeId = read(call(get("/api/v1/stores/mine"), owner, null)).get(0).get("id").asLong();

        call(get("/api/v1/stores/" + storeId + "/food-items"), register("STORE_OWNER"), null)
                .andExpect(status().isForbidden());
    }

    @Test
    void storeAddressIsLookedUpWhenNoCoordinatesAreSent() throws Exception {
        when(geocodingService.locateAddress(anyString()))
                .thenReturn(new PlaceResponse("Piata Unirii, Timisoara", 45.7578, 21.2290));

        call(post("/api/v1/stores"), register("STORE_OWNER"), Map.of("name", "Map Shop", "address", "Piata Unirii, Timisoara"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.latitude").value(45.7578))
                .andExpect(jsonPath("$.longitude").value(21.2290));
    }

    // ---------- Pay-It-Forward verification ----------

    @Test
    void consumerRequestsVerificationAdminApprovesThenFreeClaimWorks() throws Exception {
        String consumer = register("CONSUMER");
        long itemId = createStoreWithItem(register("STORE_OWNER"), 0, 1);

        call(post("/api/v1/food-items/" + itemId + "/free-claims"), consumer, null).andExpect(status().isForbidden());

        long userId = read(call(post("/api/v1/users/me/eligibility-request"), consumer, null)
                .andExpect(jsonPath("$.eligibilityStatus").value("PENDING"))).get("id").asLong();

        String admin = login("admin@example.com");
        call(put("/api/v1/admin/users/" + userId + "/eligibility"), admin, Map.of("approved", true))
                .andExpect(jsonPath("$.eligibilityStatus").value("APPROVED"));

        call(post("/api/v1/food-items/" + itemId + "/free-claims"), consumer, null)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.totalPrice").value(0.0));
    }

    @Test
    void suspendedStoreDisappearsFromSearch() throws Exception {
        String owner = register("STORE_OWNER");
        long itemId = createStoreWithItem(owner, 2, 0);
        long storeId = read(call(get("/api/v1/stores/mine"), owner, null)).get(0).get("id").asLong();
        String search = "/api/v1/food-items/nearby?lat=45.7500&lng=21.2200&radiusKm=1";

        assertThat(read(call(get(search), null, null)).findValuesAsText("id")).contains(String.valueOf(itemId));

        call(put("/api/v1/admin/stores/" + storeId + "/status"), login("admin@example.com"), Map.of("active", false))
                .andExpect(jsonPath("$.active").value(false));

        assertThat(read(call(get(search), null, null)).findValuesAsText("id")).doesNotContain(String.valueOf(itemId));
    }

    // ---------- photos ----------

    @Test
    void photoUploadIsServedAndNonImagesAreRefused() throws Exception {
        String owner = register("STORE_OWNER");
        long itemId = createStoreWithItem(owner, 1, 0);
        long storeId = read(call(get("/api/v1/stores/mine"), owner, null)).get(0).get("id").asLong();
        String uploadUrl = "/api/v1/stores/" + storeId + "/food-items/" + itemId + "/photo";

        byte[] png = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0};
        mvc.perform(multipart(uploadUrl).file(new MockMultipartFile("file", "soup.png", "image/png", png))
                        .header("Authorization", "Bearer " + owner))
                .andExpect(status().isNoContent());

        byte[] fakeImage = "<script>alert(1)</script>".getBytes();
        mvc.perform(multipart(uploadUrl).file(new MockMultipartFile("file", "evil.png", "image/png", fakeImage))
                        .header("Authorization", "Bearer " + owner))
                .andExpect(status().isUnsupportedMediaType());

        String photoUrl = read(call(get("/api/v1/stores/" + storeId + "/food-items"), owner, null))
                .get(0).get("photoUrl").asText();
        mvc.perform(get(photoUrl))
                .andExpect(status().isOk())
                .andExpect(content().contentType("image/png"))
                .andExpect(content().bytes(png));
    }
}
