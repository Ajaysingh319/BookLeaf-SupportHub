package com.bookleaf.support.security;

import com.bookleaf.support.domain.Role;

import java.time.Instant;
import java.util.UUID;

/**
 * What a session cookie says. sessionId stays the same while the cookie is renewed, so signing out can
 * revoke every cookie of that session. csrfToken is what the browser app must echo in a header on writes.
 */
public record SessionClaims(
        UUID userId,
        Role role,
        String sessionId,
        String csrfToken,
        Instant authenticatedAt,
        Instant issuedAt,
        Instant expiresAt) {
}
