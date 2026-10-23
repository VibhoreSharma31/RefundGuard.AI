package com.refundguard.security;

import com.refundguard.exception.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

public final class CurrentUser {

    private CurrentUser() {}

    /** Identity + role of the caller, derived solely from the authenticated security context. */
    public static Actor require() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        if (a == null || !a.isAuthenticated() || a instanceof AnonymousAuthenticationToken) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Authentication required.");
        }
        Role role = null;
        for (GrantedAuthority g : a.getAuthorities()) {
            String auth = g.getAuthority();
            if (auth != null && auth.startsWith("ROLE_")) {
                try {
                    role = Role.valueOf(auth.substring(5));
                    break;
                } catch (IllegalArgumentException ignored) {
                    // not one of ours
                }
            }
        }
        if (role == null) throw new ApiException(HttpStatus.FORBIDDEN, "No application role assigned.");
        return new Actor(a.getName(), role);
    }
}
