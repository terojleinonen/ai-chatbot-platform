package com.demo.ai.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Fails startup with one clear message listing every required setting that is missing, instead of an
 * obscure error from whichever component happens to read it first. Runs after application.yml and any
 * profile files are loaded, so the dev profile's defaults count as set.
 */
public class RequiredSettingsCheck implements EnvironmentPostProcessor {
    /** Property → environment variable that normally provides it. */
    static final Map<String, String> REQUIRED = new LinkedHashMap<>();
    static {
        REQUIRED.put("spring.datasource.url", "DB_URL");
        REQUIRED.put("spring.datasource.username", "DB_USER");
        REQUIRED.put("spring.datasource.password", "DB_PASSWORD");
        REQUIRED.put("security.api-key", "AI_API_KEY");
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        List<String> missing = new ArrayList<>();
        REQUIRED.forEach((property, variable) -> {
            String value;
            try {
                value = environment.getProperty(property);
            } catch (IllegalArgumentException unresolvedPlaceholder) {
                value = null;
            }
            // Only unset counts as missing: an empty value can be deliberate (e.g. a password-less DB user);
            // secrets are additionally checked for strength where they are used.
            if (value == null) missing.add(variable);
        });
        if (!missing.isEmpty()) {
            throw new IllegalStateException("Missing required configuration: " + String.join(", ", missing)
                    + ". Set these environment variables, or run with SPRING_PROFILES_ACTIVE=dev for local development.");
        }
    }
}
