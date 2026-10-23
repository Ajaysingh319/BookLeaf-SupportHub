package com.bookleaf.support.security;

import com.bookleaf.support.domain.Role;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

/** Issues and verifies short-lived signed tokens. The secret comes from AUTH_SECRET. */
@Service
public class JwtService {

    private final SecretKey key;
    private final Duration ttl;

    public JwtService(@Value("${app.auth.secret}") String secret,
                      @Value("${app.auth.token-ttl-minutes}") long ttlMinutes) {
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("AUTH_SECRET must be set and at least 32 characters long.");
        }
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.ttl = Duration.ofMinutes(ttlMinutes);
    }

    public String issue(UUID userId, Role role) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(userId.toString())
                .claim("role", role.name())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(ttl)))
                .signWith(key)
                .compact();
    }

    /** Throws JwtException (or IllegalArgumentException) if the token is invalid or expired. */
    public AuthUser verify(String token) {
        Claims claims = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
        try {
            return new AuthUser(UUID.fromString(claims.getSubject()), Role.valueOf(claims.get("role", String.class)));
        } catch (IllegalArgumentException | NullPointerException ex) {
            throw new JwtException("Token claims are not valid.", ex);
        }
    }
}
