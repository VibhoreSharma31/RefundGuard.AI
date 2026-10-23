package com.refundguard.security;

/** Application roles. Exactly one role per user. */
public enum Role {
    OPERATOR,
    REVIEWER,
    ADMIN;

    /** Spring Security authority string, e.g. ROLE_ADMIN. */
    public String authority() {
        return "ROLE_" + name();
    }
}
