package com.demo.ai.security;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Component
public class ApiKeyFilter extends OncePerRequestFilter {
    /** Prefix of the key in application-dev.yml; it is public, so production must reject it. */
    static final String DEV_SECRET_PREFIX = "dev-only-insecure";
    static final int MIN_KEY_LENGTH = 32;

    private final byte[] expected;

    public ApiKeyFilter(@Value("${security.api-key}") String apiKey,
                        @Value("${security.dev-mode:false}") boolean devMode) {
        this.expected = requireStrong(apiKey, devMode).getBytes(StandardCharsets.UTF_8);
    }

    /** Refuses to start with a missing, short, or public development key. */
    static String requireStrong(String key, boolean devMode) {
        if (key == null || key.isBlank()) throw new IllegalStateException("AI_API_KEY must be set");
        if (key.length() < MIN_KEY_LENGTH) {
            throw new IllegalStateException("AI_API_KEY must be at least " + MIN_KEY_LENGTH + " characters");
        }
        if (!devMode && key.startsWith(DEV_SECRET_PREFIX)) {
            throw new IllegalStateException("AI_API_KEY is the public development value; set a real secret");
        }
        return key;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String provided = request.getHeader("X-API-KEY");
        // Constant-time comparison so response timing does not reveal how much of the key matched.
        if (provided == null || !MessageDigest.isEqual(provided.getBytes(StandardCharsets.UTF_8), expected)) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.getWriter().write("Unauthorized");
            return;
        }
        filterChain.doFilter(request, response);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.equals("/health");
    }
}
