package com.refundguard.security;

/**
 * Fine-grained permissions. Controllers are protected with
 * {@code @PreAuthorize("hasAuthority('<PERMISSION>')")}; roles are just bundles of permissions
 * (see {@link RolePermissions}).
 */
public enum Permission {
    REFUND_VIEW,
    ORDER_VIEW,
    ORDER_CREATE,
    CASE_VIEW,
    CASE_INVESTIGATE,
    CASE_RESOLVE,
    SETTLEMENT_UPLOAD,
    RECONCILIATION_RUN,
    SLA_RUN,
    SIMULATOR_USE,
    SIMULATOR_RESET,
    RISK_VIEW,
    RISK_ASSESS,
    /** Admin-portal risk analytics (portfolio summary, scored-refund list, risk detail). ADMIN only. */
    RISK_ANALYTICS_VIEW,
    REVIEW_VIEW,
    REVIEW_DECIDE,
    /** Flag a refund / submit it for review, answer a reviewer's request for more information. */
    REVIEW_SUBMIT,
    /** Act on review cases that a reviewer escalated (ADMIN only). */
    REVIEW_ESCALATION_DECIDE,
    USER_MANAGE,
    SYSTEM_CONFIG_VIEW
}
