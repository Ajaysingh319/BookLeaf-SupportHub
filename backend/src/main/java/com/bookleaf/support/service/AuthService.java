package com.bookleaf.support.service;

import com.bookleaf.support.domain.UserAccount;
import com.bookleaf.support.dto.AuthDtos.LoginRequest;
import com.bookleaf.support.dto.AuthDtos.UserSummary;
import com.bookleaf.support.repository.UserAccountRepository;
import com.bookleaf.support.security.LoginThrottle;
import com.bookleaf.support.web.error.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.UUID;

@Service
public class AuthService {

    private final UserAccountRepository users;
    private final PasswordEncoder passwordEncoder;
    private final LoginThrottle throttle;
    /** A real hash of a throwaway value, so an unknown email costs the same time as a wrong password. */
    private final String decoyHash;

    public AuthService(UserAccountRepository users, PasswordEncoder passwordEncoder, LoginThrottle throttle) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.throttle = throttle;
        this.decoyHash = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    /**
     * Checks the credentials. The same error and the same work for an unknown email and a wrong password,
     * so neither the message nor the timing reveals which accounts exist; failures are counted against the
     * throttle and a locked address or account is refused before any password is checked.
     */
    @Transactional(readOnly = true)
    public UserSummary authenticate(LoginRequest request, String remoteAddress) {
        String email = request.email().trim().toLowerCase(Locale.ROOT);
        throttle.checkAllowed(remoteAddress, email);

        UserAccount user = users.findByEmail(email).orElse(null);
        boolean valid = passwordEncoder.matches(request.password(), user == null ? decoyHash : user.getPasswordHash());
        if (user == null || !valid) {
            throttle.recordFailure(remoteAddress, email);
            throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "Invalid email or password.");
        }
        throttle.recordSuccess(remoteAddress, email);
        return toSummary(user);
    }

    @Transactional(readOnly = true)
    public UserSummary currentUser(UUID userId) {
        UserAccount user = users.findById(userId).orElseThrow(() ->
                new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Please sign in to continue."));
        return toSummary(user);
    }

    private static UserSummary toSummary(UserAccount user) {
        return new UserSummary(user.getId(), user.getName(), user.getEmail(), user.getRole());
    }
}
