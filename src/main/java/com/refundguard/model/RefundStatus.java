package com.refundguard.model;

public enum RefundStatus {
    REQUESTED,
    GATEWAY_PENDING,
    GATEWAY_PROCESSED,
    SETTLEMENT_MATCHED,
    POTENTIAL_DELAY,
    SLA_BREACHED,
    AMOUNT_MISMATCH,
    FAILED_REPORTED,
    RESOLVED,
    /** Held for human review by the risk engine (hold-and-release workflow). */
    ON_HOLD,
    /** Refund rejected by a reviewer; must not be released. */
    REJECTED
}
