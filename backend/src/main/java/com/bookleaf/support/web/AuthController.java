package com.bookleaf.support.web;

import com.bookleaf.support.dto.AuthDtos.LoginRequest;
import com.bookleaf.support.dto.AuthDtos.LoginResponse;
import com.bookleaf.support.dto.AuthDtos.UserSummary;
import com.bookleaf.support.security.AuthUser;
import com.bookleaf.support.service.AuthService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest request) {
        return authService.login(request);
    }

    @GetMapping("/me")
    public UserSummary me(@RequestAttribute(AuthUser.REQUEST_ATTRIBUTE) AuthUser user) {
        return authService.currentUser(user.id());
    }

    /** Tokens are stateless; the client discards its token. Kept so clients have one sign-out call. */
    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout() {
    }
}
