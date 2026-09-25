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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class TenantPermissionsIntegrationTest {
    private static final String PW = "correct-horse-battery";
    private static final AtomicInteger nextIp = new AtomicInteger();

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @MockBean AiClientService ai;

    private String superToken;
    private long tenantA, tenantB, faqB;
    private String userName;
    private long userId;
    private String userToken; // tenant admin assigned to tenant A only

    private String login(String username, String password) throws Exception {
        String body = mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("username", username, "password", password)))
                        .with(r -> { int n = nextIp.incrementAndGet(); r.setRemoteAddr("10.2." + n / 250 + "." + n % 250); return r; }))
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
        org.mockito.Mockito.when(ai.train(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn("Trained");
        superToken = login("admin", "test-password");
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        tenantA = read(call(post("/tenants/create"), superToken, Map.of("name", "A-" + suffix))).get("id").asLong();
        tenantB = read(call(post("/tenants/create"), superToken, Map.of("name", "B-" + suffix))).get("id").asLong();
        faqB = read(call(post("/faq/create"), superToken,
                Map.of("tenantId", tenantB, "question", "B question", "answer", "B answer"))).get("id").asLong();
        userName = "tadmin-" + suffix;
        userId = read(call(post("/users"), superToken,
                Map.of("username", userName, "password", PW, "role", "TENANT_ADMIN", "tenantIds", List.of(tenantA))))
                .get("id").asLong();
        userToken = login(userName, PW);
    }

    @Test
    void tenantAdminSeesOnlyAssignedTenants() throws Exception {
        call(get("/tenants/list"), userToken, null).andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id", contains((int) tenantA)));
        call(get("/tenants/list"), superToken, null)
                .andExpect(jsonPath("$[*].id", hasItems((int) tenantA, (int) tenantB)));
    }

    @Test
    void tenantAdminManagesFaqsOfAssignedTenant() throws Exception {
        long id = read(call(post("/faq/create"), userToken,
                Map.of("tenantId", tenantA, "question", "Q", "answer", "A")).andExpect(status().isOk())).get("id").asLong();
        call(get("/faq/list/" + tenantA), userToken, null).andExpect(status().isOk());
        call(put("/faq/" + id), userToken, Map.of("question", "Q2", "answer", "A2")).andExpect(status().isOk());
        call(post("/faq/import/" + tenantA), userToken, List.of(Map.of("question", "Q", "answer", "A")))
                .andExpect(status().isOk());
        call(post("/faq/train/" + tenantA), userToken, null).andExpect(status().isOk());
    }

    @Test
    void tenantAdminIsDeniedOtherTenants() throws Exception {
        call(get("/faq/list/" + tenantB), userToken, null).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("You do not have access to this tenant"));
        call(post("/faq/create"), userToken, Map.of("tenantId", tenantB, "question", "Q", "answer", "A"))
                .andExpect(status().isForbidden());
        call(put("/faq/" + faqB), userToken, Map.of("question", "hacked", "answer", "hacked"))
                .andExpect(status().isForbidden());
        call(delete("/faq/" + faqB), userToken, null).andExpect(status().isForbidden());
        call(post("/faq/import/" + tenantB), userToken, List.of()).andExpect(status().isForbidden());
        call(post("/faq/train/" + tenantB), userToken, null).andExpect(status().isForbidden());
        // B's FAQ is untouched
        call(get("/faq/list/" + tenantB), superToken, null)
                .andExpect(jsonPath("$[*].question", contains("B question")));
    }

    @Test
    void createWithExistingIdCannotHijackAnotherTenantsFaq() throws Exception {
        long newId = read(call(post("/faq/create"), userToken,
                Map.of("id", faqB, "tenantId", tenantA, "question", "mine", "answer", "mine"))
                .andExpect(status().isOk())).get("id").asLong();
        org.junit.jupiter.api.Assertions.assertNotEquals(faqB, newId, "must create a new FAQ, not overwrite");
        call(get("/faq/list/" + tenantB), superToken, null)
                .andExpect(jsonPath("$[*].question", contains("B question")));
    }

    @Test
    void tenantAdminCannotCreateTenantsOrManageUsers() throws Exception {
        call(post("/tenants/create"), userToken, Map.of("name", "X")).andExpect(status().isForbidden());
        call(get("/users"), userToken, null).andExpect(status().isForbidden());
        call(post("/users"), userToken, Map.of("username", "x" + userName, "password", PW, "role", "SUPER_ADMIN"))
                .andExpect(status().isForbidden());
        call(put("/users/" + userId + "/access"), userToken, Map.of("role", "SUPER_ADMIN"))
                .andExpect(status().isForbidden());
        call(delete("/users/" + userId), userToken, null).andExpect(status().isForbidden());
    }

    @Test
    void tenantAdminCanStillChangeOwnPassword() throws Exception {
        call(put("/users/me/password"), userToken, Map.of("currentPassword", PW, "newPassword", "another-good-password"))
                .andExpect(status().isOk());
    }

    @Test
    void accessChangesTakeEffectImmediately() throws Exception {
        call(put("/users/" + userId + "/access"), superToken, Map.of("role", "TENANT_ADMIN", "tenantIds", List.of(tenantB)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenantIds", contains((int) tenantB)));
        call(get("/faq/list/" + tenantA), userToken, null).andExpect(status().isForbidden());
        call(get("/faq/list/" + tenantB), userToken, null).andExpect(status().isOk());

        call(put("/users/" + userId + "/access"), superToken, Map.of("role", "SUPER_ADMIN"))
                .andExpect(jsonPath("$.tenantIds", empty()));
        call(get("/users"), userToken, null).andExpect(status().isOk());
        call(get("/auth/me"), userToken, null).andExpect(jsonPath("$.role").value("SUPER_ADMIN"));
    }

    @Test
    void superAdminCannotChangeOwnAccess() throws Exception {
        long adminId = -1;
        for (JsonNode u : read(call(get("/users"), superToken, null))) {
            if (u.get("username").asText().equals("admin")) adminId = u.get("id").asLong();
        }
        call(put("/users/" + adminId + "/access"), superToken, Map.of("role", "TENANT_ADMIN"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("You cannot change your own access"));
    }

    @Test
    void validatesTenantIds() throws Exception {
        call(put("/users/" + userId + "/access"), superToken, Map.of("role", "TENANT_ADMIN", "tenantIds", List.of(999999)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Unknown tenant id"));
    }

    @Test
    void meReportsRoleAndTenants() throws Exception {
        call(get("/auth/me"), userToken, null)
                .andExpect(jsonPath("$.username").value(userName))
                .andExpect(jsonPath("$.role").value("TENANT_ADMIN"))
                .andExpect(jsonPath("$.tenantIds", contains((int) tenantA)));
    }
}
