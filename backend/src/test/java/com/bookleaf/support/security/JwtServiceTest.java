package com.bookleaf.support.security;

import com.bookleaf.support.TestProperties;
import com.bookleaf.support.domain.Role;
import com.bookleaf.support.testsupport.MutableClock;
import io.jsonwebtoken.JwtException;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The session token: what it carries, when it expires, and when it is renewed (sliding, with an absolute limit). */
class JwtServiceTest {

    private static final String OTHER_SECRET = "another-unit-test-secret-32-characters-long";
    private final MutableClock clock = new MutableClock();

    private JwtService service(String secret, long ttlMinutes) {
        return new JwtService(TestProperties.withAuth(secret, ttlMinutes), clock);
    }

    private JwtService service() {
        return service(TestProperties.SECRET, 60);   // idle 60 minutes, absolute 720 (test configuration)
    }

    @Test
    void aSessionRoundTripsEverythingItCarries() {
        JwtService service = service();
        UUID id = UUID.randomUUID();
        SessionClaims session = service.newSession(id, Role.ADMIN);

        SessionClaims read = service.parse(service.sign(session));

        assertThat(read.userId()).isEqualTo(id);
        assertThat(read.role()).isEqualTo(Role.ADMIN);
        assertThat(read.sessionId()).isEqualTo(session.sessionId());
        assertThat(read.csrfToken()).isEqualTo(session.csrfToken());
        assertThat(read.authenticatedAt().getEpochSecond()).isEqualTo(session.authenticatedAt().getEpochSecond());
        assertThat(read.expiresAt().getEpochSecond()).isEqualTo(session.expiresAt().getEpochSecond());
    }

    @Test
    void everySessionGetsItsOwnUnguessableIdAndCsrfToken() {
        JwtService service = service();
        SessionClaims one = service.newSession(UUID.randomUUID(), Role.AUTHOR);
        SessionClaims two = service.newSession(UUID.randomUUID(), Role.AUTHOR);

        assertThat(one.sessionId()).isNotEqualTo(two.sessionId());
        assertThat(one.csrfToken()).isNotEqualTo(two.csrfToken());
        assertThat(one.csrfToken()).hasSizeGreaterThanOrEqualTo(40).matches("[A-Za-z0-9_-]+");
    }

    @Test
    void aTokenSignedWithAnotherSecretIsRejected() {
        String token = service(OTHER_SECRET, 60).sign(service(OTHER_SECRET, 60).newSession(UUID.randomUUID(), Role.AUTHOR));
        assertThatThrownBy(() -> service().parse(token)).isInstanceOf(JwtException.class);
    }

    @Test
    void aTamperedTokenIsRejected() {
        JwtService service = service();
        String token = service.sign(service.newSession(UUID.randomUUID(), Role.AUTHOR));
        String[] parts = token.split("\\.");
        String forged = parts[0] + "." + parts[1].substring(0, parts[1].length() - 2) + "xx." + parts[2];
        assertThatThrownBy(() -> service.parse(forged)).isInstanceOf(JwtException.class);
        assertThatThrownBy(() -> service.parse(token.substring(0, token.length() - 3) + "abc")).isInstanceOf(JwtException.class);
    }

    @Test
    void garbageIsRejected() {
        assertThatThrownBy(() -> service().parse("not-a-token")).isInstanceOf(JwtException.class);
    }

    @Test
    void aTokenStopsWorkingWhenItsIdleTimeIsUp() {
        JwtService service = service();
        String token = service.sign(service.newSession(UUID.randomUUID(), Role.AUTHOR));
        assertThat(service.parse(token)).isNotNull();

        clock.advance(Duration.ofMinutes(61));
        assertThatThrownBy(() -> service.parse(token)).isInstanceOf(JwtException.class);
    }

    @Test
    void renewalHappensOnlyOnceHalfTheIdleTimeIsGone() {
        JwtService service = service();
        SessionClaims session = service.newSession(UUID.randomUUID(), Role.AUTHOR);

        assertThat(service.needsRenewal(session)).isFalse();
        clock.advance(Duration.ofMinutes(20));
        assertThat(service.needsRenewal(session)).isFalse();     // 40 of 60 minutes left
        clock.advance(Duration.ofMinutes(15));
        assertThat(service.needsRenewal(session)).isTrue();      // 25 of 60 left: less than half
    }

    @Test
    void aRenewedSessionKeepsItsIdentityAndGetsALongerLife() {
        JwtService service = service();
        SessionClaims first = service.newSession(UUID.randomUUID(), Role.ADMIN);
        clock.advance(Duration.ofMinutes(40));

        SessionClaims renewed = service.renewed(first);

        assertThat(renewed.sessionId()).isEqualTo(first.sessionId());
        assertThat(renewed.csrfToken()).isEqualTo(first.csrfToken());
        assertThat(renewed.authenticatedAt()).isEqualTo(first.authenticatedAt());
        assertThat(renewed.expiresAt()).isAfter(first.expiresAt());
        assertThat(service.needsRenewal(renewed)).isFalse();
    }

    @Test
    void renewalNeverExtendsTheSessionBeyondTheAbsoluteLimitFromSignIn() {
        JwtService service = service();   // absolute limit 720 minutes
        SessionClaims session = service.newSession(UUID.randomUUID(), Role.AUTHOR);
        // keep "using the app" every 30 minutes for 11 hours
        for (int i = 0; i < 22; i++) {
            clock.advance(Duration.ofMinutes(30));
            session = service.renewed(session);
        }
        assertThat(service.pastAbsoluteLimit(session)).isFalse();

        clock.advance(Duration.ofMinutes(30));
        SessionClaims last = service.renewed(session);
        // the new expiry is capped at the absolute limit, not now + 60 minutes
        assertThat(last.expiresAt()).isEqualTo(service.absoluteLimit(last.authenticatedAt()));
        assertThat(service.needsRenewal(last)).isFalse();   // nothing more can be gained

        clock.advance(Duration.ofMinutes(31));
        assertThat(service.pastAbsoluteLimit(last)).isTrue();
    }

    @Test
    void refusesToStartWithAWeakSecret() {
        assertThatThrownBy(() -> service("short", 60)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> service("", 60)).isInstanceOf(IllegalStateException.class);
    }
}
