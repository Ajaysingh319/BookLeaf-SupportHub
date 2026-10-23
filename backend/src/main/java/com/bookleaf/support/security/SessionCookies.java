package com.bookleaf.support.security;

import com.bookleaf.support.config.AppProperties;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Builds and reads the session cookie. It is HttpOnly (scripts cannot read it), only sent to the API
 * (Path /api), and Secure / SameSite follow the configuration for the environment.
 */
@Component
public class SessionCookies {

    /** The cookie is only ever sent to API requests. */
    private static final String PATH = "/api";

    private final AppProperties.Auth.Cookie config;
    private final Clock clock;

    public SessionCookies(AppProperties properties, Clock clock) {
        this.config = properties.auth().cookie();
        this.clock = clock;
    }

    /** The Set-Cookie header value for a session token that expires at the given time. */
    public String create(String token, Instant expiresAt) {
        Duration maxAge = Duration.between(clock.instant(), expiresAt);
        return base(token).maxAge(maxAge.isNegative() ? Duration.ZERO : maxAge).build().toString();
    }

    /** The Set-Cookie header value that makes the browser delete the session cookie. */
    public String clear() {
        return base("").maxAge(Duration.ZERO).build().toString();
    }

    /** The raw token from the request's cookies, or null. */
    public String read(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (config.name().equals(cookie.getName()) && cookie.getValue() != null && !cookie.getValue().isBlank()) {
                return cookie.getValue();
            }
        }
        return null;
    }

    private ResponseCookie.ResponseCookieBuilder base(String value) {
        return ResponseCookie.from(config.name(), value)
                .httpOnly(true)
                .secure(config.secure())
                .sameSite(config.sameSite())
                .path(PATH);
    }
}
