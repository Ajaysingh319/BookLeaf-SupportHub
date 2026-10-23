package com.bookleaf.support.security;

import com.bookleaf.support.domain.Role;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.UUID;

/** Starts, reads, renews and ends sessions carried in the cookie. */
@Service
public class SessionService {

    private final JwtService jwt;
    private final SessionCookies cookies;
    private final SessionRevocations revocations;

    public SessionService(JwtService jwt, SessionCookies cookies, SessionRevocations revocations) {
        this.jwt = jwt;
        this.cookies = cookies;
        this.revocations = revocations;
    }

    /** Signs the user in: creates the session and sets its cookie on the response. */
    public SessionClaims start(UUID userId, Role role, HttpServletResponse response) {
        SessionClaims claims = jwt.newSession(userId, role);
        response.addHeader(HttpHeaders.SET_COOKIE, cookies.create(jwt.sign(claims), claims.expiresAt()));
        return claims;
    }

    /**
     * The valid session of this request, if any. Empty for a missing, malformed, tampered, expired,
     * signed-out or too-old session: callers cannot tell these apart, and neither can an attacker.
     */
    public Optional<SessionClaims> resolve(HttpServletRequest request) {
        String token = cookies.read(request);
        if (token == null) {
            return Optional.empty();
        }
        try {
            SessionClaims claims = jwt.parse(token);
            if (revocations.isRevoked(claims.sessionId()) || jwt.pastAbsoluteLimit(claims)) {
                return Optional.empty();
            }
            return Optional.of(claims);
        } catch (JwtException | IllegalArgumentException ex) {
            return Optional.empty();
        }
    }

    /** Sliding session: while the person keeps using the app, hand out a fresh cookie before the old one runs low. */
    public void renewIfNeeded(SessionClaims claims, HttpServletResponse response) {
        if (jwt.needsRenewal(claims)) {
            SessionClaims renewed = jwt.renewed(claims);
            response.addHeader(HttpHeaders.SET_COOKIE, cookies.create(jwt.sign(renewed), renewed.expiresAt()));
        }
    }

    /** Signs out: revokes the session (if there is one) and tells the browser to delete the cookie. */
    public void end(HttpServletRequest request, HttpServletResponse response) {
        resolve(request).ifPresent(claims ->
                revocations.revoke(claims.sessionId(), jwt.absoluteLimit(claims.authenticatedAt())));
        response.addHeader(HttpHeaders.SET_COOKIE, cookies.clear());
    }
}
