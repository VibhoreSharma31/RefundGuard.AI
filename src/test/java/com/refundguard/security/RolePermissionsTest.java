package com.refundguard.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RolePermissionsTest {

    @Test
    void adminHasEveryPermission() {
        for (Permission p : Permission.values()) assertTrue(RolePermissions.has(Role.ADMIN, p), p.name());
    }

    @Test
    void reviewerCanDecideButNotOperateOrAdminister() {
        assertTrue(RolePermissions.has(Role.REVIEWER, Permission.REVIEW_DECIDE));
        assertTrue(RolePermissions.has(Role.REVIEWER, Permission.REVIEW_VIEW));
        assertTrue(RolePermissions.has(Role.REVIEWER, Permission.RISK_VIEW));
        assertFalse(RolePermissions.has(Role.REVIEWER, Permission.SETTLEMENT_UPLOAD));
        assertFalse(RolePermissions.has(Role.REVIEWER, Permission.CASE_RESOLVE));
        assertFalse(RolePermissions.has(Role.REVIEWER, Permission.SIMULATOR_RESET));
        assertFalse(RolePermissions.has(Role.REVIEWER, Permission.USER_MANAGE));
    }

    @Test
    void operatorCanInvestigateButNeverDecideResetOrManageUsers() {
        assertTrue(RolePermissions.has(Role.OPERATOR, Permission.REFUND_VIEW));
        assertTrue(RolePermissions.has(Role.OPERATOR, Permission.CASE_RESOLVE));
        assertTrue(RolePermissions.has(Role.OPERATOR, Permission.SETTLEMENT_UPLOAD));
        assertTrue(RolePermissions.has(Role.OPERATOR, Permission.REVIEW_VIEW));
        assertFalse(RolePermissions.has(Role.OPERATOR, Permission.REVIEW_DECIDE));
        assertFalse(RolePermissions.has(Role.OPERATOR, Permission.SIMULATOR_RESET));
        assertFalse(RolePermissions.has(Role.OPERATOR, Permission.USER_MANAGE));
        assertFalse(RolePermissions.has(Role.OPERATOR, Permission.SYSTEM_CONFIG_VIEW));
    }

    @Test
    void onlyAdminHasResetUserManagementAndConfig() {
        for (Role r : Role.values()) {
            boolean admin = r == Role.ADMIN;
            assertEquals(admin, RolePermissions.has(r, Permission.SIMULATOR_RESET), r + " reset");
            assertEquals(admin, RolePermissions.has(r, Permission.USER_MANAGE), r + " users");
            assertEquals(admin, RolePermissions.has(r, Permission.SYSTEM_CONFIG_VIEW), r + " config");
        }
    }

    @Test
    void operatorsAndAdminsSubmitReviewersDecideOnlyAdminsDecideEscalations() {
        assertTrue(RolePermissions.has(Role.OPERATOR, Permission.REVIEW_SUBMIT));
        assertTrue(RolePermissions.has(Role.ADMIN, Permission.REVIEW_SUBMIT));
        assertFalse(RolePermissions.has(Role.REVIEWER, Permission.REVIEW_SUBMIT));
        assertFalse(RolePermissions.has(Role.OPERATOR, Permission.REVIEW_DECIDE));
        for (Role r : Role.values()) {
            assertEquals(r == Role.ADMIN, RolePermissions.has(r, Permission.REVIEW_ESCALATION_DECIDE), r + " escalation");
            assertEquals(r == Role.ADMIN, RolePermissions.has(r, Permission.RISK_ANALYTICS_VIEW), r + " risk analytics");
        }
    }

    @Test
    void nullRoleHasNothing() {
        assertTrue(RolePermissions.of(null).isEmpty());
        assertFalse(RolePermissions.has(null, Permission.REFUND_VIEW));
    }

    @Test
    void authorityStringHasRolePrefix() {
        assertEquals("ROLE_REVIEWER", Role.REVIEWER.authority());
    }
}
