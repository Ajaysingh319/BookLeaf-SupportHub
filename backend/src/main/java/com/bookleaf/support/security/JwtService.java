package com.bookleaf.support.security;

import com.bookleaf.support.config.AppProperties;
import com.bookleaf.support.domain.Role;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
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

    /** HS256 needs a key of at least 256 bits. */
    private static final int MIN_SECRET_BYTES = 32;

    private final SecretKey key;
    private final Duration ttl;

    public JwtService(AppProperties properties) {
        String secret = properties.auth().secret();
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_BYTES) {
            throw new IllegalStateException("AUTH_SECRET must be set and at least " + MIN_SECRET_BYTES + " characters long.");
        }
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.ttl = Duration.ofMinutes(properties.auth().tokenTtlMinutes());
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
