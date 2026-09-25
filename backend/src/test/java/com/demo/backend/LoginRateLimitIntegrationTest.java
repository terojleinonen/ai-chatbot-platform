package com.demo.backend;

import com.demo.backend.service.AiClientService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class LoginRateLimitIntegrationTest {
    @Autowired MockMvc mvc;
    @MockBean AiClientService ai;

    private ResultActions login(String ip, String username, String password) throws Exception {
        return mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}")
                .with(req -> { req.setRemoteAddr(ip); return req; }));
    }

    @Test
    void lockedOutAfterFiveFailuresEvenWithCorrectPassword() throws Exception {
        String ip = "10.0.0.1";
        for (int i = 0; i < 5; i++) login(ip, "admin", "wrong").andExpect(status().isUnauthorized());
        login(ip, "admin", "test-password")
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.retryAfterSeconds").isNumber());
        // A different client IP is unaffected
        login("10.0.0.2", "admin", "test-password").andExpect(status().isOk());
    }

    @Test
    void successfulLoginResetsUserCounter() throws Exception {
        String ip = "10.0.0.3";
        for (int i = 0; i < 4; i++) login(ip, "admin", "wrong").andExpect(status().isUnauthorized());
        login(ip, "admin", "test-password").andExpect(status().isOk());
        for (int i = 0; i < 4; i++) login(ip, "admin", "wrong").andExpect(status().isUnauthorized());
        login(ip, "admin", "test-password").andExpect(status().isOk());
    }

    @Test
    void ipBlockedAfterTwentyFailuresAcrossUsernames() throws Exception {
        String ip = "10.0.0.4";
        for (int i = 0; i < 20; i++) login(ip, "user" + i, "wrong").andExpect(status().isUnauthorized());
        login(ip, "someone-new", "wrong").andExpect(status().isTooManyRequests());
    }

    @Test
    void parallelBurstCannotExceedTheLimit() throws Exception {
        String ip = "10.0.0.5";
        ExecutorService pool = Executors.newFixedThreadPool(16);
        List<Future<Integer>> results = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            results.add(pool.submit(() -> login(ip, "admin", "wrong").andReturn().getResponse().getStatus()));
        }
        int unauthorized = 0;
        for (Future<Integer> f : results) if (f.get(30, TimeUnit.SECONDS) == 401) unauthorized++;
        pool.shutdown();
        assertEquals(5, unauthorized, "only 5 password checks may run; the rest must get 429");
    }
}
