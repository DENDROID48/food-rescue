package com.foodrescue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.foodrescue.service.MailService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** One's own account: details, password, signing out everywhere, data export, deletion and password reset. */
// A database of its own: mocking the mail service starts a separate application, whose demo data
// would clash with the shared test database.
@SpringBootTest(properties = {"foodrescue.geocoding.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:accounts;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH"})
@AutoConfigureMockMvc
class AccountIntegrationTest {

    private static final String PASSWORD = "demo1234";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @MockitoBean MailService mailService;

    @BeforeEach
    void mailWorks() {
        when(mailService.canSendResetLinks()).thenReturn(true);
    }

    private ResultActions call(MockHttpServletRequestBuilder request, String token, Object body) throws Exception {
        if (token != null) request.header("Authorization", "Bearer " + token);
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        return mvc.perform(request);
    }

    private JsonNode read(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString());
    }

    /** Registers a customer; returns {token, email}. */
    private String[] register() throws Exception {
        String email = "acc-" + UUID.randomUUID() + "@test.ro";
        JsonNode body = read(call(post("/api/v1/users"), null, Map.of("fullName", "Ana Pop", "email", email,
                "password", PASSWORD, "role", "CONSUMER", "acceptTerms", true, "language", "ro")).andExpect(status().isCreated()));
        assertThat(body.get("user").get("language").asText()).isEqualTo("ro");
        return new String[]{body.get("token").asText(), email};
    }

    private ResultActions login(String email, String password) throws Exception {
        return call(post("/api/v1/auth/login"), null, Map.of("email", email, "password", password));
    }

    @Test
    void registrationNeedsTheTermsAndAKnownLanguage() throws Exception {
        Map<String, Object> body = new HashMap<>(Map.of("fullName", "A", "email", "t-" + UUID.randomUUID() + "@test.ro",
                "password", PASSWORD, "role", "CONSUMER"));
        call(post("/api/v1/users"), null, body).andExpect(status().isBadRequest());
        body.put("acceptTerms", true);
        body.put("language", "xx");
        call(post("/api/v1/users"), null, body).andExpect(status().isBadRequest());
        body.remove("language");
        call(post("/api/v1/users"), null, body).andExpect(status().isCreated())
                .andExpect(jsonPath("$.user.language").value("en"));
    }

    @Test
    void detailsChangeAndTheEmailNeedsThePassword() throws Exception {
        String[] a = register();
        String other = register()[1];
        call(put("/api/v1/users/me"), a[0], Map.of("fullName", "Ana Maria", "email", a[1], "language", "de"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.fullName").value("Ana Maria")).andExpect(jsonPath("$.language").value("de"));

        String newEmail = "new-" + UUID.randomUUID() + "@test.ro";
        call(put("/api/v1/users/me"), a[0], Map.of("fullName", "Ana", "email", newEmail, "language", "de"))
                .andExpect(status().isForbidden());
        call(put("/api/v1/users/me"), a[0], Map.of("fullName", "Ana", "email", other, "language", "de", "currentPassword", PASSWORD))
                .andExpect(status().isConflict());
        call(put("/api/v1/users/me"), a[0], Map.of("fullName", "Ana", "email", newEmail.toUpperCase(), "language", "de", "currentPassword", PASSWORD))
                .andExpect(status().isOk()).andExpect(jsonPath("$.email").value(newEmail));
        login(newEmail, PASSWORD).andExpect(status().isOk());
    }

    @Test
    void aNewPasswordSignsOutOtherDevices() throws Exception {
        String[] a = register();
        String otherDevice = read(login(a[1], PASSWORD)).get("token").asText();
        call(put("/api/v1/users/me/password"), a[0], Map.of("currentPassword", "wrong-one", "newPassword", "another-pass"))
                .andExpect(status().isForbidden());
        String fresh = read(call(put("/api/v1/users/me/password"), a[0], Map.of("currentPassword", PASSWORD, "newPassword", "another-pass"))
                .andExpect(status().isOk())).get("token").asText();

        call(get("/api/v1/users/me"), otherDevice, null).andExpect(status().isUnauthorized());
        call(get("/api/v1/users/me"), a[0], null).andExpect(status().isUnauthorized());
        call(get("/api/v1/users/me"), fresh, null).andExpect(status().isOk());
        login(a[1], PASSWORD).andExpect(status().isUnauthorized());
        login(a[1], "another-pass").andExpect(status().isOk());
        verify(mailService).sendPasswordChanged(eq(a[1]), eq("Ana Pop"), eq("ro"));
    }

    @Test
    void signOutEverywhereKeepsOnlyThisDevice() throws Exception {
        String[] a = register();
        String fresh = read(call(post("/api/v1/users/me/sign-out-everywhere"), a[0], null).andExpect(status().isOk())).get("token").asText();
        call(get("/api/v1/users/me"), a[0], null).andExpect(status().isUnauthorized());
        call(get("/api/v1/users/me"), fresh, null).andExpect(status().isOk());
    }

    @Test
    void theDataExportHasTheAccountAndOrders() throws Exception {
        String[] a = register();
        JsonNode data = read(call(get("/api/v1/users/me/export"), a[0], null)
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("attachment"))));
        assertThat(data.get("account").get("email").asText()).isEqualTo(a[1]);
        assertThat(data.get("account").get("termsAcceptedAt").isNull()).isFalse();
        assertThat(data.get("orders").isArray()).isTrue();
        assertThat(data.has("favouriteShops")).isTrue();
    }

    @Test
    void deletingTheAccountRemovesItsPersonalData() throws Exception {
        String[] a = register();
        call(post("/api/v1/users/me/deletion"), a[0], Map.of("password", "wrong-one")).andExpect(status().isForbidden());
        call(post("/api/v1/users/me/deletion"), a[0], Map.of("password", PASSWORD)).andExpect(status().isNoContent());

        call(get("/api/v1/users/me"), a[0], null).andExpect(status().isUnauthorized());
        login(a[1], PASSWORD).andExpect(status().isUnauthorized());
        // The email can be used again for a new account.
        call(post("/api/v1/users"), null, Map.of("fullName", "Ana", "email", a[1], "password", PASSWORD,
                "role", "CONSUMER", "acceptTerms", true)).andExpect(status().isCreated());
    }

    @Test
    void theOnlyAdministratorCannotDeleteTheirAccount() throws Exception {
        String admin = read(login("admin@example.com", PASSWORD)).get("token").asText();
        call(post("/api/v1/users/me/deletion"), admin, Map.of("password", PASSWORD)).andExpect(status().isConflict());
    }

    @Test
    void forgotPasswordSendsALinkThatWorksOnce() throws Exception {
        String[] a = register();
        // Unknown addresses get the same answer, and no email.
        call(post("/api/v1/auth/password-reset"), null, Map.of("email", "nobody-" + UUID.randomUUID() + "@test.ro"))
                .andExpect(status().isAccepted());
        verify(mailService, never()).sendPasswordReset(anyString(), anyString(), anyString(), anyString());

        call(post("/api/v1/auth/password-reset"), null, Map.of("email", a[1].toUpperCase())).andExpect(status().isAccepted());
        ArgumentCaptor<String> link = ArgumentCaptor.forClass(String.class);
        verify(mailService).sendPasswordReset(eq(a[1]), eq("Ana Pop"), eq("ro"), link.capture());
        assertThat(link.getValue()).startsWith("http://localhost:8080/#reset/");
        String token = link.getValue().substring(link.getValue().lastIndexOf('/') + 1);

        call(post("/api/v1/auth/password-reset/confirm"), null, Map.of("token", token, "newPassword", "short"))
                .andExpect(status().isBadRequest());
        call(post("/api/v1/auth/password-reset/confirm"), null, Map.of("token", token, "newPassword", "brand-new-pass"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.token").exists());
        call(post("/api/v1/auth/password-reset/confirm"), null, Map.of("token", token, "newPassword", "brand-new-pass-2"))
                .andExpect(status().isBadRequest());
        call(get("/api/v1/users/me"), a[0], null).andExpect(status().isUnauthorized());
        login(a[1], "brand-new-pass").andExpect(status().isOk());
    }

    @Test
    void withoutAMailServerForgotPasswordIsNotOffered() throws Exception {
        when(mailService.canSendResetLinks()).thenReturn(false);
        mvc.perform(get("/api/v1/settings")).andExpect(jsonPath("$.passwordReset").value(false));
        call(post("/api/v1/auth/password-reset"), null, Map.of("email", "a@test.ro")).andExpect(status().isServiceUnavailable());
    }

    @Test
    void healthAndSecurityHeaders() throws Exception {
        mvc.perform(get("/api/v1/health")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
        mvc.perform(get("/index.html"))
                .andExpect(header().string("Content-Security-Policy", org.hamcrest.Matchers.containsString("default-src 'self'")))
                .andExpect(header().string("Referrer-Policy", "strict-origin-when-cross-origin"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().exists("Permissions-Policy"));
    }
}
