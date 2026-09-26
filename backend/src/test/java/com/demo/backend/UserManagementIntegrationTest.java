package com.demo.backend;

import com.demo.backend.service.AiClientService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.UUID;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class UserManagementIntegrationTest {
    private static final String STRONG = "correct-horse-battery";
    private static final java.util.concurrent.atomic.AtomicInteger nextIp = new java.util.concurrent.atomic.AtomicInteger();

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @MockBean AiClientService ai;

    // Each login uses its own client IP so the login rate limiter never interferes between tests.
    private String login(String username, String password) throws Exception {
        String body = mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new Creds(username, password)))
                        .with(r -> { int n = nextIp.incrementAndGet(); r.setRemoteAddr("10.1." + n / 250 + "." + n % 250); return r; }))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("token").asText();
    }

    record Creds(String username, String password) {}
    record NewUser(String username, String password, String role) {}

    private ResultActions call(MockHttpServletRequestBuilder req, String token, Object body) throws Exception {
        req.header("Authorization", "Bearer " + token);
        if (body != null) req.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        return mvc.perform(req);
    }

    private JsonNode createUser(String adminToken, String username) throws Exception {
        return createUser(adminToken, username, "TENANT_ADMIN");
    }

    private JsonNode createUser(String adminToken, String username, String role) throws Exception {
        String body = call(post("/users"), adminToken, new NewUser(username, STRONG, role))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return json.readTree(body);
    }

    private static String uniqueName() {
        return "user-" + UUID.randomUUID().toString().substring(0, 8);
    }

    @Test
    void requiresAuthentication() throws Exception {
        mvc.perform(get("/users")).andExpect(status().isUnauthorized());
    }

    @Test
    void createListAndLoginAsNewUser() throws Exception {
        String admin = login("admin", "test-password");
        String name = uniqueName();
        JsonNode created = createUser(admin, name.toUpperCase());
        org.junit.jupiter.api.Assertions.assertEquals(name, created.get("username").asText(), "stored lowercase");
        call(get("/users"), admin, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].username", hasItem(name)))
                .andExpect(jsonPath("$.items[0].passwordHash").doesNotExist());
        login(name.toUpperCase(), STRONG); // usernames are case-insensitive
    }

    @Test
    void validatesInput() throws Exception {
        String admin = login("admin", "test-password");
        call(post("/users"), admin, new NewUser(uniqueName(), STRONG, null)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Role is required")));
        call(post("/users"), admin, new NewUser("ab", STRONG, "TENANT_ADMIN")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Username")));
        call(post("/users"), admin, new NewUser("bad name!", STRONG, "TENANT_ADMIN")).andExpect(status().isBadRequest());
        call(post("/users"), admin, new NewUser(uniqueName(), "short", "TENANT_ADMIN")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Password must be at least 12 characters"));
        call(post("/users"), admin, new NewUser(uniqueName(), "x".repeat(73), "TENANT_ADMIN")).andExpect(status().isBadRequest());
        call(post("/users"), admin, new NewUser("admin", STRONG, "TENANT_ADMIN")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Username already exists"));
    }

    @Test
    void deletingUserRevokesTheirTokenImmediately() throws Exception {
        String admin = login("admin", "test-password");
        String name = uniqueName();
        long id = createUser(admin, name).get("id").asLong();
        String userToken = login(name, STRONG);
        call(get("/tenants/list"), userToken, null).andExpect(status().isOk());

        call(delete("/users/" + id), admin, null).andExpect(status().isNoContent());
        call(get("/tenants/list"), userToken, null).andExpect(status().isUnauthorized());
        call(get("/users"), admin, null).andExpect(jsonPath("$.items[*].username", not(hasItem(name))));
    }

    @Test
    void cannotDeleteYourself() throws Exception {
        String name = uniqueName();
        long id = createUser(login("admin", "test-password"), name, "SUPER_ADMIN").get("id").asLong();
        String self = login(name, STRONG);
        call(delete("/users/" + id), self, null).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("You cannot delete your own account"));
    }

    @Test
    void resetPasswordRevokesOldTokensAndOldPassword() throws Exception {
        String admin = login("admin", "test-password");
        String name = uniqueName();
        long id = createUser(admin, name).get("id").asLong();
        String userToken = login(name, STRONG);

        call(put("/users/" + id + "/password"), admin, java.util.Map.of("password", "a-brand-new-password"))
                .andExpect(status().isNoContent());
        call(get("/tenants/list"), userToken, null).andExpect(status().isUnauthorized());
        mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new Creds(name, STRONG))))
                .andExpect(status().isUnauthorized());
        login(name, "a-brand-new-password");
    }

    @Test
    void cannotResetOwnPasswordWithoutCurrentPassword() throws Exception {
        String name = uniqueName();
        long id = createUser(login("admin", "test-password"), name, "SUPER_ADMIN").get("id").asLong();
        String self = login(name, STRONG);
        call(put("/users/" + id + "/password"), self, java.util.Map.of("password", "another-long-password"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void changeOwnPasswordReturnsFreshTokenAndRevokesOthers() throws Exception {
        String name = uniqueName();
        createUser(login("admin", "test-password"), name);
        String sessionA = login(name, STRONG);
        String sessionB = login(name, STRONG);

        call(put("/users/me/password"), sessionA,
                java.util.Map.of("currentPassword", "wrong-password", "newPassword", "a-brand-new-password"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Current password is incorrect"));

        String body = call(put("/users/me/password"), sessionA,
                java.util.Map.of("currentPassword", STRONG, "newPassword", "a-brand-new-password"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String fresh = json.readTree(body).get("token").asText();

        call(get("/tenants/list"), sessionA, null).andExpect(status().isUnauthorized());
        call(get("/tenants/list"), sessionB, null).andExpect(status().isUnauthorized());
        call(get("/tenants/list"), fresh, null).andExpect(status().isOk());
    }

    @Test
    void wrongCurrentPasswordIsRateLimited() throws Exception {
        String name = uniqueName();
        createUser(login("admin", "test-password"), name);
        String token = login(name, STRONG);
        var wrong = java.util.Map.of("currentPassword", "wrong-password", "newPassword", "a-brand-new-password");
        for (int i = 0; i < 5; i++) call(put("/users/me/password"), token, wrong).andExpect(status().isBadRequest());
        call(put("/users/me/password"), token, java.util.Map.of("currentPassword", STRONG, "newPassword", "a-brand-new-password"))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    void tokenWithoutVersionClaimIsRejected() throws Exception {
        // Tokens issued before this change carried no version claim.
        mvc.perform(get("/tenants/list").header("Authorization", "Bearer " + legacyToken()))
                .andExpect(status().isUnauthorized());
    }

    @Autowired org.springframework.security.oauth2.jwt.JwtEncoder encoder;

    private String legacyToken() {
        var claims = org.springframework.security.oauth2.jwt.JwtClaimsSet.builder()
                .subject("admin").issuedAt(java.time.Instant.now())
                .expiresAt(java.time.Instant.now().plusSeconds(600)).build();
        var header = org.springframework.security.oauth2.jwt.JwsHeader
                .with(org.springframework.security.oauth2.jose.jws.MacAlgorithm.HS256).build();
        return encoder.encode(org.springframework.security.oauth2.jwt.JwtEncoderParameters.from(header, claims)).getTokenValue();
    }
}
