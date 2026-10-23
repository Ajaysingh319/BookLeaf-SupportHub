package com.bookleaf.support.service;

import com.bookleaf.support.TestProperties;
import com.bookleaf.support.domain.Role;
import com.bookleaf.support.domain.UserAccount;
import com.bookleaf.support.dto.AuthDtos.LoginRequest;
import com.bookleaf.support.dto.AuthDtos.UserSummary;
import com.bookleaf.support.repository.UserAccountRepository;
import com.bookleaf.support.security.LoginThrottle;
import com.bookleaf.support.testsupport.MutableClock;
import com.bookleaf.support.web.error.ApiException;
import com.bookleaf.support.web.error.TooManyAttemptsException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Password verification and sign-in rules (TDD section 13, unit level). */
class AuthServiceTest {

    private static final String PASSWORD = "correct horse battery staple";
    private static final String ADDRESS = "203.0.113.7";

    private final PasswordEncoder encoder = spy(new BCryptPasswordEncoder());
    private final MutableClock clock = new MutableClock();
    private final UserAccountRepository users = mock(UserAccountRepository.class);
    private final LoginThrottle throttle = new LoginThrottle(TestProperties.create(), clock);
    private final AuthService service = new AuthService(users, encoder, throttle);
    private UserAccount account;

    @BeforeEach
    void setUp() {
        account = new UserAccount();
        ReflectionTestUtils.setField(account, "id", UUID.randomUUID());
        account.setName("Test Author");
        account.setEmail("author@example.test");
        account.setRole(Role.AUTHOR);
        account.setPasswordHash(encoder.encode(PASSWORD));
        when(users.findByEmail("author@example.test")).thenReturn(Optional.of(account));
        when(users.findByEmail("missing@example.test")).thenReturn(Optional.empty());
    }

    @Test
    void theCorrectPasswordGivesTheUsersProfile() {
        UserSummary user = service.authenticate(new LoginRequest("author@example.test", PASSWORD), ADDRESS);

        assertThat(user.id()).isEqualTo(account.getId());
        assertThat(user.role()).isEqualTo(Role.AUTHOR);
        assertThat(user.email()).isEqualTo("author@example.test");
    }

    @Test
    void aWrongPasswordIsRejectedWithTheSameErrorAsAnUnknownEmail() {
        assertThatThrownBy(() -> service.authenticate(new LoginRequest("author@example.test", PASSWORD + "x"), ADDRESS))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
                    assertThat(ex.getCode()).isEqualTo("INVALID_CREDENTIALS");
                    assertThat(ex.getMessage()).isEqualTo("Invalid email or password.");
                });
        assertThatThrownBy(() -> service.authenticate(new LoginRequest("missing@example.test", PASSWORD), ADDRESS))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
                    assertThat(ex.getCode()).isEqualTo("INVALID_CREDENTIALS");
                    assertThat(ex.getMessage()).isEqualTo("Invalid email or password.");
                });
    }

    @Test
    void anUnknownEmailStillCostsAPasswordHashCheckSoTimingDoesNotRevealAccounts() {
        assertThatThrownBy(() -> service.authenticate(new LoginRequest("missing@example.test", PASSWORD), ADDRESS))
                .isInstanceOf(ApiException.class);

        // a password comparison happened even though no such account exists
        verify(encoder).matches(eq(PASSWORD), anyString());
    }

    @Test
    void theEmailIsTrimmedAndLowercasedBeforeTheLookup() {
        service.authenticate(new LoginRequest("  Author@Example.TEST ", PASSWORD), ADDRESS);
        verify(users).findByEmail("author@example.test");
    }

    @Test
    void theStoredHashIsNeverThePlainPasswordAndPlainTextDoesNotMatchTheHash() {
        assertThat(account.getPasswordHash()).startsWith("$2").isNotEqualTo(PASSWORD);
        // a plain-text "hash" must never be accepted
        account.setPasswordHash(PASSWORD);
        assertThatThrownBy(() -> service.authenticate(new LoginRequest("author@example.test", PASSWORD), ADDRESS))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void theProfileReturnedAfterSignInHasNoPasswordHash() {
        UserSummary user = service.authenticate(new LoginRequest("author@example.test", PASSWORD), ADDRESS);
        assertThat(user.toString()).doesNotContain("$2").doesNotContainIgnoringCase("hash");
    }

    // ---- throttling ----

    @Test
    void repeatedFailuresLockTheAccountEvenAgainstTheRightPassword() {
        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> service.authenticate(new LoginRequest("author@example.test", "wrong"), ADDRESS))
                    .isInstanceOf(ApiException.class);
        }
        assertThatThrownBy(() -> service.authenticate(new LoginRequest("author@example.test", PASSWORD), ADDRESS))
                .isInstanceOfSatisfying(TooManyAttemptsException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
                    assertThat(ex.getCode()).isEqualTo("TOO_MANY_ATTEMPTS");
                    assertThat(ex.getRetryAfterSeconds()).isBetween(1L, 15L * 60);
                });
    }

    @Test
    void aSuccessfulSignInClearsTheCount() {
        for (int round = 0; round < 3; round++) {
            for (int i = 0; i < 4; i++) {
                assertThatThrownBy(() -> service.authenticate(new LoginRequest("author@example.test", "wrong"), ADDRESS))
                        .isInstanceOf(ApiException.class);
            }
            service.authenticate(new LoginRequest("author@example.test", PASSWORD), ADDRESS);   // resets the count
        }
    }
}
