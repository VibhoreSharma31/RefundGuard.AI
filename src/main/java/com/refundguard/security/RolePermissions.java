package com.refundguard.security;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import static com.refundguard.security.Permission.*;

/** Single source of truth for the role -> permission matrix. */
public final class RolePermissions {

    private static final Map<Role, Set<Permission>> MATRIX = new EnumMap<>(Role.class);

    static {
        MATRIX.put(Role.OPERATOR, Collections.unmodifiableSet(EnumSet.of(
                REFUND_VIEW, ORDER_VIEW, ORDER_CREATE,
                CASE_VIEW, CASE_INVESTIGATE, CASE_RESOLVE,
                SETTLEMENT_UPLOAD, RECONCILIATION_RUN, SLA_RUN,
                SIMULATOR_USE,
                RISK_VIEW, RISK_ASSESS,
                REVIEW_VIEW, REVIEW_SUBMIT)));

        MATRIX.put(Role.REVIEWER, Collections.unmodifiableSet(EnumSet.of(
                REFUND_VIEW, ORDER_VIEW,
                CASE_VIEW,
                RISK_VIEW, RISK_ASSESS,
                REVIEW_VIEW, REVIEW_DECIDE)));

        MATRIX.put(Role.ADMIN, Collections.unmodifiableSet(EnumSet.allOf(Permission.class)));
    }

    private RolePermissions() {}

    public static Set<Permission> of(Role role) {
        if (role == null) return Set.of();
        return MATRIX.getOrDefault(role, Set.of());
    }

    public static boolean has(Role role, Permission permission) {
        return of(role).contains(permission);
    }
}
