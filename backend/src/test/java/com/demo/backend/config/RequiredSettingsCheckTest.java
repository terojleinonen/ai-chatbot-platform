package com.demo.backend.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.*;

class RequiredSettingsCheckTest {
    private final RequiredSettingsCheck check = new RequiredSettingsCheck();

    @Test
    void listsEveryMissingSettingByEnvironmentVariableName() {
        var env = new MockEnvironment()
                .withProperty("spring.datasource.url", "${DB_URL}")   // unresolvable placeholder
                .withProperty("spring.datasource.username", "user")
                .withProperty("spring.datasource.password", "");      // empty but set: allowed
        var e = assertThrows(IllegalStateException.class, () -> check.postProcessEnvironment(env, null));
        assertEquals("Missing required configuration: DB_URL, AI_API_KEY, JWT_SECRET, CORS_ALLOWED_ORIGINS. "
                + "Set these environment variables, or run with SPRING_PROFILES_ACTIVE=dev for local development.",
                e.getMessage());
    }

    @Test
    void passesWhenEverythingIsSet() {
        var env = new MockEnvironment();
        RequiredSettingsCheck.REQUIRED.keySet().forEach(p -> env.setProperty(p, "value"));
        assertDoesNotThrow(() -> check.postProcessEnvironment(env, null));
    }
}
