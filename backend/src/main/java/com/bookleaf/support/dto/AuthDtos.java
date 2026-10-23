package com.bookleaf.support.dto;

import com.bookleaf.support.domain.Role;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public final class AuthDtos {

    private AuthDtos() {
    }

    public record LoginRequest(
            @NotBlank(message = "Email is required.")
            @Size(max = ValidationLimits.EMAIL_MAX, message = "Email is too long.")
            String email,

            @NotBlank(message = "Password is required.")
            @Size(max = ValidationLimits.PASSWORD_MAX, message = "Password is too long.")
            String password) {
    }

    /** Safe public view of an account: never includes the password hash. */
    public record UserSummary(UUID id, String name, String email, Role role) {
    }

    public record LoginResponse(String token, UserSummary user) {
    }
}
