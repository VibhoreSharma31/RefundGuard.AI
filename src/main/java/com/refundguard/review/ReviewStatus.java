package com.refundguard.review;

/**
 * Lifecycle of a review case (one row in rg_refund_reviews).
 *
 *   PENDING (= "pending review") -> UNDER_REVIEW -> APPROVED (= "approved for release") / REJECTED
 *                                                -> MORE_INFO_REQUIRED -> (operator resubmits) -> PENDING
 *                                                -> ESCALATED -> (ADMIN) APPROVED / REJECTED / MORE_INFO_REQUIRED
 *
 * PENDING / APPROVED keep their original names so review rows written by earlier versions stay valid.
 * While a case is open (PENDING, UNDER_REVIEW, MORE_INFO_REQUIRED, ESCALATED) the refund stays ON_HOLD.
 */
public enum ReviewStatus {
    PENDING,
    /** A reviewer has picked the case up (assigned to them). */
    UNDER_REVIEW,
    /** Reviewer asked operations for more information; the refund stays on hold until it is resubmitted. */
    MORE_INFO_REQUIRED,
    /** Reviewer escalated the case; only an ADMIN may decide it. */
    ESCALATED,
    APPROVED,
    REJECTED,
    /** Closed without a human decision because the bank settlement arrived while the refund was on hold. */
    SUPERSEDED
}
