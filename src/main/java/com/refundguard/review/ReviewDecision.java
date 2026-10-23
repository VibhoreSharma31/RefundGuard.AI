package com.refundguard.review;

public enum ReviewDecision {
    APPROVE,
    REJECT;

    public ReviewStatus toStatus() {
        return this == APPROVE ? ReviewStatus.APPROVED : ReviewStatus.REJECTED;
    }
}
