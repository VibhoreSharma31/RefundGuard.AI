package com.refundguard.review;

/** Everything a user can do to a review case. Rules: {@link ReviewStateMachine#allows} and RefundReviewService#denial. */
public enum ReviewAction {
    START,
    REQUEST_INFO,
    RESUBMIT,
    ESCALATE,
    APPROVE,
    REJECT,
    NOTE
}
