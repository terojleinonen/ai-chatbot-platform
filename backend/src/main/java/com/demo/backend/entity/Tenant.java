package com.demo.backend.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.*;

import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;

@Entity
public class Tenant {
    private static final SecureRandom RANDOM = new SecureRandom();

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String name;

    /** Public identifier used by the embeddable widget; unguessable, unlike the id. */
    @Column(name = "widget_key", nullable = false, unique = true, length = 64)
    private String widgetKey;

    /** Websites allowed to use this tenant's chat, one origin per line. Empty means any website. */
    @Column(name = "allowed_origins", length = 4000)
    private String allowedOrigins;

    public Tenant() {}
    public Tenant(String name) { this.name = name; }

    @PrePersist
    void assignWidgetKey() {
        if (widgetKey == null) rotateWidgetKey();
    }

    /** Replaces the widget key; widgets embedded with the old key stop working. */
    public void rotateWidgetKey() {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        widgetKey = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); // 32 characters
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getWidgetKey() { return widgetKey; }

    @JsonProperty("allowedOrigins")
    public List<String> getAllowedOriginList() {
        if (allowedOrigins == null || allowedOrigins.isBlank()) return List.of();
        return Arrays.stream(allowedOrigins.split("\n")).filter(s -> !s.isBlank()).toList();
    }

    public void setAllowedOriginList(List<String> origins) {
        allowedOrigins = origins == null || origins.isEmpty() ? null : String.join("\n", origins);
    }

    /** True if a chat connection opened from {@code origin} may use this tenant (no list = any website). */
    @JsonIgnore
    public boolean allowsOrigin(String origin) {
        List<String> allowed = getAllowedOriginList();
        return allowed.isEmpty() || (origin != null && allowed.contains(origin.toLowerCase()));
    }
}
