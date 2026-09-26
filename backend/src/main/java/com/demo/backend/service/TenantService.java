package com.demo.backend.service;

import com.demo.backend.entity.Tenant;
import com.demo.backend.repository.TenantRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

@Service
public class TenantService {
    static final int MAX_ALLOWED_ORIGINS = 50;

    private final TenantRepository repo;

    public TenantService(TenantRepository repo) {
        this.repo = repo;
    }

    public Tenant create(String name) {
        if (name == null || name.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Tenant name is required");
        }
        if (name.trim().length() > 255) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Tenant name is too long (at most 255 characters)");
        }
        return repo.save(new Tenant(name.trim()));
    }

    public List<Tenant> list() { return repo.findAll(); }

    public Optional<Tenant> findByWidgetKey(String widgetKey) {
        if (widgetKey == null || widgetKey.isBlank() || widgetKey.length() > 64) return Optional.empty();
        return repo.findByWidgetKey(widgetKey);
    }

    @Transactional
    public Tenant updateAllowedOrigins(Long id, List<String> origins) {
        Tenant tenant = get(id);
        tenant.setAllowedOriginList(normalizeOrigins(origins));
        return tenant;
    }

    @Transactional
    public Tenant rotateWidgetKey(Long id) {
        Tenant tenant = get(id);
        tenant.rotateWidgetKey();
        return tenant;
    }

    private Tenant get(Long id) {
        return repo.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Tenant not found"));
    }

    /**
     * Validates and normalizes origins to the exact form browsers send in the Origin header:
     * lowercase scheme://host[:port], no path. Duplicates are removed.
     */
    static List<String> normalizeOrigins(List<String> origins) {
        if (origins == null) return List.of();
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String raw : origins) {
            if (raw == null || raw.isBlank()) continue;
            String value = raw.trim();
            URI uri;
            try {
                uri = URI.create(value);
            } catch (IllegalArgumentException e) {
                throw invalidOrigin(value);
            }
            String scheme = uri.getScheme() == null ? null : uri.getScheme().toLowerCase(Locale.ROOT);
            boolean hasPath = uri.getRawPath() != null && !uri.getRawPath().isEmpty() && !uri.getRawPath().equals("/");
            if (!("http".equals(scheme) || "https".equals(scheme)) || uri.getHost() == null || hasPath
                    || uri.getRawQuery() != null || uri.getRawFragment() != null || uri.getRawUserInfo() != null) {
                throw invalidOrigin(value);
            }
            String origin = scheme + "://" + uri.getHost().toLowerCase(Locale.ROOT) + (uri.getPort() == -1 ? "" : ":" + uri.getPort());
            result.add(origin);
        }
        if (result.size() > MAX_ALLOWED_ORIGINS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "At most " + MAX_ALLOWED_ORIGINS + " websites");
        }
        return new ArrayList<>(result);
    }

    private static ResponseStatusException invalidOrigin(String value) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Invalid website '" + value + "': use the form https://www.example.com");
    }
}
