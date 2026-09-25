package com.demo.backend.security;

import com.demo.backend.repository.AdminUserRepository;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Rejects tokens of users that were deleted or whose password changed after the token was issued.
 * Costs one indexed lookup per API request, which is fine for an admin panel.
 */
public class TokenVersionValidator implements OAuth2TokenValidator<Jwt> {
    private static final OAuth2Error REVOKED = new OAuth2Error("invalid_token", "Token has been revoked", null);

    private final AdminUserRepository users;

    public TokenVersionValidator(AdminUserRepository users) {
        this.users = users;
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt jwt) {
        Object claim = jwt.getClaims().get(TokenService.VERSION_CLAIM);
        if (!(claim instanceof Number version)) return OAuth2TokenValidatorResult.failure(REVOKED);
        boolean current = users.findByUsername(jwt.getSubject())
                .map(u -> u.getTokenVersion() == version.intValue())
                .orElse(false);
        return current ? OAuth2TokenValidatorResult.success() : OAuth2TokenValidatorResult.failure(REVOKED);
    }
}
