package com.bookleaf.support.security;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sessions that were signed out. A signed cookie is otherwise valid until it expires, so signing out
 * also records the session here, and the filter rejects any cookie of a revoked session.
 *
 * Kept in memory: it is correct for one server instance and is forgotten on restart (a revoked session
 * is then valid again until its short idle expiry). A shared store would be needed to run several instances.
 */
@Component
public class SessionRevocations {

    private final ConcurrentHashMap<String, Instant> revoked = new ConcurrentHashMap<>();
    private final Clock clock;

    public SessionRevocations(Clock clock) {
        this.clock = clock;
    }

    /** Remember the session as signed out until the moment it could not be valid anyway. */
    public void revoke(String sessionId, Instant until) {
        purgeExpired();
        revoked.put(sessionId, until);
    }

    public boolean isRevoked(String sessionId) {
        Instant until = revoked.get(sessionId);
        if (until == null) {
            return false;
        }
        if (!clock.instant().isBefore(until)) {
            revoked.remove(sessionId, until);
            return false;
        }
        return true;
    }

    private void purgeExpired() {
        Instant now = clock.instant();
        revoked.entrySet().removeIf(entry -> !now.isBefore(entry.getValue()));
    }

    int size() {
        return revoked.size();
    }
}
