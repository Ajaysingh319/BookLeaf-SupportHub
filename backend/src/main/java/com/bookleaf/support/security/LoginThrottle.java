package com.bookleaf.support.security;

import com.bookleaf.support.config.AppProperties;
import com.bookleaf.support.web.error.TooManyAttemptsException;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Slows down password guessing. Failed sign-ins are counted for each (address, email) pair and for each
 * address on its own; reaching a limit locks that key for the lockout period, during which even the right
 * password is refused. Unknown emails are counted exactly like known ones, so the throttle never reveals
 * which accounts exist.
 *
 * In memory, per server instance, and keyed by the connection's remote address: behind a proxy that
 * address is the proxy's, which is why the per-address limit is much higher than the per-account one.
 */
@Component
public class LoginThrottle {

    private record Entry(int failures, Instant windowStart, Instant lockedUntil) {
    }

    private final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();
    private final AppProperties.Auth.Throttle config;
    private final Duration lockout;
    private final Clock clock;

    public LoginThrottle(AppProperties properties, Clock clock) {
        this.config = properties.auth().throttle();
        this.lockout = Duration.ofMinutes(config.lockoutMinutes());
        this.clock = clock;
    }

    /** Throws TooManyAttemptsException while this address or this (address, email) is locked. */
    public void checkAllowed(String address, String email) {
        Instant now = clock.instant();
        long wait = Math.max(remainingLock(pairKey(address, email), now), remainingLock(addressKey(address), now));
        if (wait > 0) {
            throw new TooManyAttemptsException(wait);
        }
    }

    public void recordFailure(String address, String email) {
        purgeIfLarge();
        bump(pairKey(address, email), config.maxFailedLogins());
        bump(addressKey(address), config.maxFailedLoginsPerIp());
    }

    /** A correct sign-in clears the account's counter (the per-address counter keeps counting). */
    public void recordSuccess(String address, String email) {
        entries.remove(pairKey(address, email));
    }

    private long remainingLock(String key, Instant now) {
        Entry entry = entries.get(key);
        if (entry == null || entry.lockedUntil() == null || !now.isBefore(entry.lockedUntil())) {
            return 0;
        }
        return Math.max(1, Duration.between(now, entry.lockedUntil()).toSeconds());
    }

    private void bump(String key, int limit) {
        Instant now = clock.instant();
        entries.compute(key, (k, existing) -> {
            Entry entry = existing;
            boolean lockEnded = entry != null && entry.lockedUntil() != null && !now.isBefore(entry.lockedUntil());
            boolean windowEnded = entry != null && entry.lockedUntil() == null
                    && !now.isBefore(entry.windowStart().plus(lockout));
            if (entry == null || lockEnded || windowEnded) {
                entry = new Entry(0, now, null);
            }
            if (entry.lockedUntil() != null) {
                return entry;   // already locked: do not extend the lock
            }
            int failures = entry.failures() + 1;
            Instant lockedUntil = failures >= limit ? now.plus(lockout) : null;
            return new Entry(failures, entry.windowStart(), lockedUntil);
        });
    }

    /** Keeps memory bounded: when too many keys are tracked, drop the ones that no longer matter. */
    private void purgeIfLarge() {
        if (entries.size() < config.maxTrackedKeys()) {
            return;
        }
        Instant now = clock.instant();
        entries.entrySet().removeIf(e -> {
            Entry entry = e.getValue();
            boolean lockOver = entry.lockedUntil() != null && !now.isBefore(entry.lockedUntil());
            boolean windowOver = entry.lockedUntil() == null && !now.isBefore(entry.windowStart().plus(lockout));
            return lockOver || windowOver;
        });
    }

    private static String pairKey(String address, String email) {
        String normalized = email == null ? "" : email.strip().toLowerCase(Locale.ROOT);
        return "pair:" + address + "|" + normalized;
    }

    private static String addressKey(String address) {
        return "address:" + address;
    }

    int trackedKeys() {
        return entries.size();
    }
}
