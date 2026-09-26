package com.demo.backend.security;

/** Startup checks that stop the service from running with missing, weak or development secrets. */
public final class SecretChecks {
    /** Prefix of the secrets shipped in application-dev.yml; they are public, so production must reject them. */
    public static final String DEV_SECRET_PREFIX = "dev-only-insecure";
    public static final int MIN_SECRET_LENGTH = 32;

    private SecretChecks() {}

    public static String requireStrong(String name, String value, boolean devMode) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " must be set");
        }
        if (value.length() < MIN_SECRET_LENGTH) {
            throw new IllegalStateException(name + " must be at least " + MIN_SECRET_LENGTH + " characters");
        }
        if (!devMode && value.startsWith(DEV_SECRET_PREFIX)) {
            throw new IllegalStateException(name + " is the public development value; set a real secret");
        }
        return value;
    }
}
