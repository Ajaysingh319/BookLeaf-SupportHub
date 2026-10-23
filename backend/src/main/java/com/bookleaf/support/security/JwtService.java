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
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;

/**
 * Creates and checks the signed session token carried in the cookie. The secret comes from AUTH_SECRET.
 * A session is short-lived and renewed on activity (sliding), but never beyond the absolute limit
 * counted from sign-in.
 */
@Service
public class JwtService {

    /** HS256 needs a key of at least 256 bits. */
    private static final int MIN_SECRET_BYTES = 32;
    private static final int CSRF_TOKEN_BYTES = 32;

    private final SecretKey key;
    private final Duration ttl;
    private final Duration sessionMax;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public JwtService(AppProperties properties, Clock clock) {
        String secret = properties.auth().secret();
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_BYTES) {
            throw new IllegalStateException("AUTH_SECRET must be set and at least " + MIN_SECRET_BYTES + " characters long.");
        }
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.ttl = Duration.ofMinutes(properties.auth().tokenTtlMinutes());
        this.sessionMax = Duration.ofMinutes(properties.auth().sessionMaxMinutes());
        this.clock = clock;
    }

    /** A brand-new session for a user who has just signed in. */
    public SessionClaims newSession(UUID userId, Role role) {
        Instant now = clock.instant();
        byte[] csrf = new byte[CSRF_TOKEN_BYTES];
        random.nextBytes(csrf);
        return new SessionClaims(userId, role, UUID.randomUUID().toString(),
                Base64.getUrlEncoder().withoutPadding().encodeToString(csrf),
                now, now, expiryFor(now, now));
    }

    /** The same session with a fresh lifetime (sliding renewal), still capped by the absolute limit. */
    public SessionClaims renewed(SessionClaims claims) {
        Instant now = clock.instant();
        return new SessionClaims(claims.userId(), claims.role(), claims.sessionId(), claims.csrfToken(),
                claims.authenticatedAt(), now, expiryFor(now, claims.authenticatedAt()));
    }

    /** True once less than half the idle lifetime is left, and the absolute limit still allows a longer one. */
    public boolean needsRenewal(SessionClaims claims) {
        Instant now = clock.instant();
        boolean halfGone = Duration.between(now, claims.expiresAt()).compareTo(ttl.dividedBy(2)) < 0;
        return halfGone && claims.expiresAt().isBefore(absoluteLimit(claims.authenticatedAt()));
    }

    /** True when the session has outlived the absolute limit from sign-in, whatever the cookie says. */
    public boolean pastAbsoluteLimit(SessionClaims claims) {
        return !clock.instant().isBefore(absoluteLimit(claims.authenticatedAt()));
    }

    public Instant absoluteLimit(Instant authenticatedAt) {
        return authenticatedAt.plus(sessionMax);
    }

    private Instant expiryFor(Instant now, Instant authenticatedAt) {
        Instant idleExpiry = now.plus(ttl);
        Instant limit = absoluteLimit(authenticatedAt);
        return idleExpiry.isBefore(limit) ? idleExpiry : limit;
    }

    public String sign(SessionClaims claims) {
        return Jwts.builder()
                .subject(claims.userId().toString())
                .claim("role", claims.role().name())
                .claim("sid", claims.sessionId())
                .claim("csrf", claims.csrfToken())
                .claim("auth", claims.authenticatedAt().getEpochSecond())
                .issuedAt(Date.from(claims.issuedAt()))
                .expiration(Date.from(claims.expiresAt()))
                .signWith(key)
                .compact();
    }

    /** Throws JwtException (or IllegalArgumentException) if the token is invalid, tampered with or expired. */
    public SessionClaims parse(String token) {
        Claims claims = Jwts.parser()
                .verifyWith(key)
                .clock(() -> Date.from(clock.instant()))
                .build()
                .parseSignedClaims(token)
                .getPayload();
        try {
            Number authenticatedAt = claims.get("auth", Number.class);
            String sessionId = claims.get("sid", String.class);
            String csrf = claims.get("csrf", String.class);
            if (authenticatedAt == null || sessionId == null || csrf == null) {
                throw new JwtException("Token is missing session claims.");
            }
            return new SessionClaims(
                    UUID.fromString(claims.getSubject()),
                    Role.valueOf(claims.get("role", String.class)),
                    sessionId,
                    csrf,
                    Instant.ofEpochSecond(authenticatedAt.longValue()),
                    claims.getIssuedAt().toInstant(),
                    claims.getExpiration().toInstant());
        } catch (IllegalArgumentException | NullPointerException ex) {
            throw new JwtException("Token claims are not valid.", ex);
        }
    }
}
