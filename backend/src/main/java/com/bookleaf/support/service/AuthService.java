package com.bookleaf.support.service;

import com.bookleaf.support.domain.UserAccount;
import com.bookleaf.support.dto.AuthDtos.LoginRequest;
import com.bookleaf.support.dto.AuthDtos.LoginResponse;
import com.bookleaf.support.dto.AuthDtos.UserSummary;
import com.bookleaf.support.repository.UserAccountRepository;
import com.bookleaf.support.security.JwtService;
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
    private final JwtService jwtService;

    public AuthService(UserAccountRepository users, PasswordEncoder passwordEncoder, JwtService jwtService) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    @Transactional(readOnly = true)
    public LoginResponse login(LoginRequest request) {
        String email = request.email().trim().toLowerCase(Locale.ROOT);
        UserAccount user = users.findByEmail(email).orElse(null);
        // Same message for an unknown email and a wrong password, so accounts can't be probed.
        if (user == null || !passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "Invalid email or password.");
        }
        return new LoginResponse(jwtService.issue(user.getId(), user.getRole()), toSummary(user));
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
