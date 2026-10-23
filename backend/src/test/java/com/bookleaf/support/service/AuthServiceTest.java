package com.bookleaf.support.service;

import com.bookleaf.support.TestProperties;
import com.bookleaf.support.domain.Role;
import com.bookleaf.support.domain.UserAccount;
import com.bookleaf.support.dto.AuthDtos.LoginRequest;
import com.bookleaf.support.dto.AuthDtos.LoginResponse;
import com.bookleaf.support.repository.UserAccountRepository;
import com.bookleaf.support.security.AuthUser;
import com.bookleaf.support.security.JwtService;
import com.bookleaf.support.web.error.ApiException;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Password verification and login rules (TDD section 13, unit level). */
class AuthServiceTest {

    private static final String PASSWORD = "correct horse battery staple";

    private final PasswordEncoder encoder = new BCryptPasswordEncoder();
    private final JwtService jwt = new JwtService(TestProperties.create());
    private final UserAccountRepository users = mock(UserAccountRepository.class);
    private final AuthService service = new AuthService(users, encoder, jwt);
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
    void theCorrectPasswordGivesATokenForThatUserAndRole() {
        LoginResponse response = service.login(new LoginRequest("author@example.test", PASSWORD));

        AuthUser verified = jwt.verify(response.token());
        assertThat(verified.id()).isEqualTo(account.getId());
        assertThat(verified.role()).isEqualTo(Role.AUTHOR);
        assertThat(response.user().email()).isEqualTo("author@example.test");
    }

    @Test
    void aWrongPasswordIsRejectedWithTheSameErrorAsAnUnknownEmail() {
        assertThatThrownBy(() -> service.login(new LoginRequest("author@example.test", PASSWORD + "x")))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
                    assertThat(ex.getCode()).isEqualTo("INVALID_CREDENTIALS");
                    assertThat(ex.getMessage()).isEqualTo("Invalid email or password.");
                });
        assertThatThrownBy(() -> service.login(new LoginRequest("missing@example.test", PASSWORD)))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
                    assertThat(ex.getCode()).isEqualTo("INVALID_CREDENTIALS");
                    assertThat(ex.getMessage()).isEqualTo("Invalid email or password.");
                });
    }

    @Test
    void theEmailIsTrimmedAndLowercasedBeforeTheLookup() {
        service.login(new LoginRequest("  Author@Example.TEST ", PASSWORD));
        verify(users).findByEmail("author@example.test");
    }

    @Test
    void theStoredHashIsNeverThePlainPasswordAndPlainTextDoesNotMatchTheHash() {
        assertThat(account.getPasswordHash()).startsWith("$2").isNotEqualTo(PASSWORD);
        // a plain-text "hash" must never be accepted
        account.setPasswordHash(PASSWORD);
        assertThatThrownBy(() -> service.login(new LoginRequest("author@example.test", PASSWORD)))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void theProfileReturnedAfterLoginHasNoPasswordHash() {
        LoginResponse response = service.login(new LoginRequest("author@example.test", PASSWORD));
        assertThat(response.user().toString()).doesNotContain("$2").doesNotContainIgnoringCase("hash");
    }
}
