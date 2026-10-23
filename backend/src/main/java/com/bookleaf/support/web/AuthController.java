package com.bookleaf.support.web;

import com.bookleaf.support.dto.AuthDtos.LoginRequest;
import com.bookleaf.support.dto.AuthDtos.LoginResponse;
import com.bookleaf.support.dto.AuthDtos.SessionResponse;
import com.bookleaf.support.dto.AuthDtos.UserSummary;
import com.bookleaf.support.security.AuthUser;
import com.bookleaf.support.security.SessionClaims;
import com.bookleaf.support.security.SessionService;
import com.bookleaf.support.service.AuthService;
import com.bookleaf.support.web.error.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.Optional;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;
    private final SessionService sessions;

    public AuthController(AuthService authService, SessionService sessions) {
        this.authService = authService;
        this.sessions = sessions;
    }

    /** Signs in: sets the HttpOnly session cookie and returns the user and the CSRF token for writes. */
    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest request, HttpServletRequest http,
                               HttpServletResponse response) {
        UserSummary user = authService.authenticate(request, http.getRemoteAddr());
        SessionClaims session = sessions.start(user.id(), user.role(), response);
        return new LoginResponse(user, session.csrfToken());
    }

    /**
     * Who is signed in right now, for the browser app at startup. Always 200: a visitor with no session
     * gets nulls instead of an error, so the login page does not start with a failed request.
     */
    @GetMapping("/session")
    public SessionResponse session(HttpServletRequest http, HttpServletResponse response) {
        Optional<SessionClaims> session = sessions.resolve(http);
        if (session.isEmpty()) {
            return new SessionResponse(null, null);
        }
        try {
            return new SessionResponse(authService.currentUser(session.get().userId()), session.get().csrfToken());
        } catch (ApiException ex) {
            // the account no longer exists: end the session instead of leaving a dead cookie behind
            sessions.end(http, response);
            return new SessionResponse(null, null);
        }
    }

    @GetMapping("/me")
    public UserSummary me(@RequestAttribute(AuthUser.REQUEST_ATTRIBUTE) AuthUser user) {
        return authService.currentUser(user.id());
    }

    /**
     * Signs out: the session is revoked on the server (not just forgotten by the browser) and the cookie is
     * deleted. Public on purpose: signing out must work even when the session has already expired.
     */
    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(HttpServletRequest http, HttpServletResponse response) {
        sessions.end(http, response);
    }
}
