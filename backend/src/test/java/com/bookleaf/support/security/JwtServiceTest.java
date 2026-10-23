package com.bookleaf.support.security;

import com.bookleaf.support.TestProperties;
import com.bookleaf.support.domain.Role;
import io.jsonwebtoken.JwtException;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtServiceTest {

    private static final String OTHER_SECRET = "another-unit-test-secret-32-characters-long";

    private static JwtService service(String secret, long ttlMinutes) {
        return new JwtService(TestProperties.withAuth(secret, ttlMinutes));
    }

    @Test
    void roundTripsIdAndRole() {
        JwtService service = service(TestProperties.SECRET, 60);
        UUID id = UUID.randomUUID();
        AuthUser user = service.verify(service.issue(id, Role.ADMIN));
        assertThat(user.id()).isEqualTo(id);
        assertThat(user.role()).isEqualTo(Role.ADMIN);
    }

    @Test
    void rejectsATokenSignedWithAnotherSecret() {
        String token = service(OTHER_SECRET, 60).issue(UUID.randomUUID(), Role.AUTHOR);
        assertThatThrownBy(() -> service(TestProperties.SECRET, 60).verify(token)).isInstanceOf(JwtException.class);
    }

    @Test
    void rejectsExpiredTokens() {
        JwtService service = service(TestProperties.SECRET, -1);
        String token = service.issue(UUID.randomUUID(), Role.AUTHOR);
        assertThatThrownBy(() -> service.verify(token)).isInstanceOf(JwtException.class);
    }

    @Test
    void rejectsGarbage() {
        assertThatThrownBy(() -> service(TestProperties.SECRET, 60).verify("not-a-token"))
                .isInstanceOf(JwtException.class);
    }

    @Test
    void refusesToStartWithAWeakSecret() {
        assertThatThrownBy(() -> service("short", 60)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> service("", 60)).isInstanceOf(IllegalStateException.class);
    }
}
