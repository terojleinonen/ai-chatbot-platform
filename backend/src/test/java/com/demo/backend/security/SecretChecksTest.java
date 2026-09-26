package com.demo.backend.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SecretChecksTest {
    private static final String STRONG = "a-real-secret-that-is-long-enough-123";
    private static final String DEV = "dev-only-insecure-ai-key-do-not-use-in-production";

    @Test
    void acceptsStrongSecret() {
        assertEquals(STRONG, SecretChecks.requireStrong("X", STRONG, false));
    }

    @Test
    void rejectsMissingOrShort() {
        assertThrows(IllegalStateException.class, () -> SecretChecks.requireStrong("X", null, false));
        assertThrows(IllegalStateException.class, () -> SecretChecks.requireStrong("X", " ", true));
        var e = assertThrows(IllegalStateException.class, () -> SecretChecks.requireStrong("X", "MY_INTERNAL_AI_KEY", true));
        assertTrue(e.getMessage().contains("at least 32"));
    }

    @Test
    void devSecretOnlyAllowedInDevMode() {
        assertEquals(DEV, SecretChecks.requireStrong("X", DEV, true));
        var e = assertThrows(IllegalStateException.class, () -> SecretChecks.requireStrong("X", DEV, false));
        assertTrue(e.getMessage().contains("public development value"));
    }
}
