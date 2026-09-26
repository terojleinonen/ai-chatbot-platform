package com.demo.backend;

import com.demo.backend.service.AiClientService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class TenantSettingsIntegrationTest {
    private static final AtomicInteger nextIp = new AtomicInteger();
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @MockBean AiClientService ai;
    private String superToken;

    private String login(String username, String password) throws Exception {
        String body = mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("username", username, "password", password)))
                        .with(r -> { int n = nextIp.incrementAndGet(); r.setRemoteAddr("10.3." + n / 250 + "." + n % 250); return r; }))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("token").asText();
    }

    private ResultActions call(MockHttpServletRequestBuilder req, String token, Object body) throws Exception {
        req.header("Authorization", "Bearer " + token);
        if (body != null) req.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        return mvc.perform(req);
    }

    private JsonNode read(ResultActions r) throws Exception {
        return json.readTree(r.andReturn().getResponse().getContentAsString());
    }

    @BeforeEach
    void setUp() throws Exception {
        superToken = login("admin", "test-password");
    }

    @Test
    void newTenantsGetAnUnguessableWidgetKeyThatClientsCannotChoose() throws Exception {
        JsonNode t = read(call(post("/tenants/create"), superToken,
                Map.of("name", "Acme", "widgetKey", "chosen-by-client", "id", 1)).andExpect(status().isOk()));
        String key = t.get("widgetKey").asText();
        assertEquals(32, key.length());
        assertNotEquals("chosen-by-client", key);
        assertTrue(t.get("allowedOrigins").isEmpty());
        call(post("/tenants/create"), superToken, Map.of("name", " ")).andExpect(status().isBadRequest());
    }

    @Test
    void allowedOriginsAreValidatedAndNormalized() throws Exception {
        long id = read(call(post("/tenants/create"), superToken, Map.of("name", "Shop"))).get("id").asLong();
        call(put("/tenants/" + id + "/settings"), superToken,
                Map.of("allowedOrigins", List.of("HTTPS://Shop.Example.com/", "http://localhost:3000")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allowedOrigins", contains("https://shop.example.com", "http://localhost:3000")));
        call(put("/tenants/" + id + "/settings"), superToken, Map.of("allowedOrigins", List.of("shop.example.com")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("use the form https://")));
        call(get("/tenants/list"), superToken, null)
                .andExpect(jsonPath("$.items[?(@.id == " + id + ")].allowedOrigins[0]", contains("https://shop.example.com")));
    }

    @Test
    void rotatingTheWidgetKeyReplacesIt() throws Exception {
        JsonNode t = read(call(post("/tenants/create"), superToken, Map.of("name", "Rotate")));
        JsonNode rotated = read(call(post("/tenants/" + t.get("id").asLong() + "/widget-key"), superToken, null)
                .andExpect(status().isOk()));
        assertNotEquals(t.get("widgetKey").asText(), rotated.get("widgetKey").asText());
    }

    @Test
    void tenantAdminsCanOnlyChangeTheirOwnTenants() throws Exception {
        long mine = read(call(post("/tenants/create"), superToken, Map.of("name", "Mine"))).get("id").asLong();
        long other = read(call(post("/tenants/create"), superToken, Map.of("name", "Other"))).get("id").asLong();
        String name = "tadmin-" + UUID.randomUUID().toString().substring(0, 8);
        call(post("/users"), superToken, Map.of("username", name, "password", "correct-horse-battery",
                "role", "TENANT_ADMIN", "tenantIds", List.of(mine))).andExpect(status().isCreated());
        String token = login(name, "correct-horse-battery");

        call(put("/tenants/" + mine + "/settings"), token, Map.of("allowedOrigins", List.of("https://mine.example")))
                .andExpect(status().isOk());
        call(post("/tenants/" + mine + "/widget-key"), token, null).andExpect(status().isOk());
        call(put("/tenants/" + other + "/settings"), token, Map.of("allowedOrigins", List.of()))
                .andExpect(status().isForbidden());
        call(post("/tenants/" + other + "/widget-key"), token, null).andExpect(status().isForbidden());
    }
}
