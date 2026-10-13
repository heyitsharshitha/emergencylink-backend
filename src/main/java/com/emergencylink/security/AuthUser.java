package com.emergencylink.security;

import com.emergencylink.domain.Role;

/** Authenticated principal carried in the SecurityContext. */
public record AuthUser(Long id, String email, String fullName, Role role, Long hospitalId, String hospitalName) {
    public boolean isAdmin() { return role == Role.ADMIN; }
}
