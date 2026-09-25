package com.demo.backend.security;

import com.demo.backend.entity.AdminUser;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;

@Service
public class TokenService {
    /** JWT claim carrying {@link AdminUser#getTokenVersion()}. */
    public static final String VERSION_CLAIM = "ver";

    private final JwtEncoder encoder;
    private final Duration ttl;

    public TokenService(JwtEncoder encoder, @Value("${security.jwt.ttl}") Duration ttl) {
        this.encoder = encoder;
        this.ttl = ttl;
    }

    public record IssuedToken(String token, Instant expiresAt, String username) {}

    public IssuedToken issue(AdminUser user) {
        Instant now = Instant.now();
        Instant expiresAt = now.plus(ttl);
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("ai-chatbot-backend")
                .subject(user.getUsername())
                .issuedAt(now)
                .expiresAt(expiresAt)
                .claim(VERSION_CLAIM, user.getTokenVersion())
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        String token = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new IssuedToken(token, expiresAt, user.getUsername());
    }
}
