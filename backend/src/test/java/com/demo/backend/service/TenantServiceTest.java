package com.demo.backend.service;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TenantServiceTest {
    @Test
    void normalizesOriginsToTheFormBrowsersSend() {
        assertEquals(List.of("https://www.example.com", "http://localhost:3000"),
                TenantService.normalizeOrigins(Arrays.asList(" HTTPS://WWW.Example.com/ ", "http://localhost:3000", "",
                        null, "https://www.example.com")));
    }

    @Test
    void rejectsAnythingThatIsNotAnOrigin() {
        for (String bad : List.of("www.example.com", "ftp://example.com", "https://example.com/path",
                "https://example.com?x=1", "https://user@example.com", "javascript:alert(1)", "https://")) {
            assertThrows(ResponseStatusException.class, () -> TenantService.normalizeOrigins(List.of(bad)), bad);
        }
    }

    @Test
    void emptyListMeansAnyWebsite() {
        assertEquals(List.of(), TenantService.normalizeOrigins(null));
        assertEquals(List.of(), TenantService.normalizeOrigins(List.of(" ")));
    }
}
