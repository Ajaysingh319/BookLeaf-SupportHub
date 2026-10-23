package com.bookleaf.support.security;

import com.bookleaf.support.domain.Role;

import java.util.UUID;

/** The authenticated caller, taken from a verified token. */
public record AuthUser(UUID id, Role role) {

    public static final String REQUEST_ATTRIBUTE = "authUser";
}
