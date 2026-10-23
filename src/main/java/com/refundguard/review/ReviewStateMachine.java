package com.refundguard.review;

import com.refundguard.model.RefundStatus;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/** Pure transition rules for the hold-and-release / review-case workflow (no I/O, unit-testable). */
public final class ReviewStateMachine {

    /** Refund statuses from which the risk engine may place a refund on hold automatically. */
    private static final Set<RefundStatus> HOLDABLE = EnumSet.of(RefundStatus.REQUESTED, RefundStatus.GATEWAY_PENDING);

    /**
     * Refund statuses from which an operator/admin may submit a refund for review manually: anything that is not
     * yet settled by the bank, closed, rejected or already on hold.
     */
    private static final Set<RefundStatus> SUBMITTABLE = EnumSet.of(
            RefundStatus.REQUESTED, RefundStatus.GATEWAY_PENDING, RefundStatus.GATEWAY_PROCESSED,
            RefundStatus.POTENTIAL_DELAY, RefundStatus.SLA_BREACHED, RefundStatus.FAILED_REPORTED);

    /** Statuses a released refund may be restored to (the ones it can have been held from). */
    private static final Set<RefundStatus> RESTORABLE = SUBMITTABLE;

    /** Review cases that are still open: the refund is ON_HOLD while one of these exists. */
    private static final Set<ReviewStatus> OPEN = Collections.unmodifiableSet(EnumSet.of(
            ReviewStatus.PENDING, ReviewStatus.UNDER_REVIEW, ReviewStatus.MORE_INFO_REQUIRED, ReviewStatus.ESCALATED));

    /** Open review cases that are waiting for a reviewer/admin (not for operations). */
    private static final Set<ReviewStatus> AWAITING_REVIEWER = Collections.unmodifiableSet(EnumSet.of(
            ReviewStatus.PENDING, ReviewStatus.UNDER_REVIEW, ReviewStatus.ESCALATED));

    public static final int MIN_REASON_LENGTH = 5;
    public static final int MAX_REASON_LENGTH = 500;

    private ReviewStateMachine() {}

    public static boolean canHold(RefundStatus current) {
        return HOLDABLE.contains(current);
    }

    public static boolean canSubmit(RefundStatus current) {
        return SUBMITTABLE.contains(current);
    }

    public static Set<ReviewStatus> openStatuses() {
        return OPEN;
    }

    public static Set<ReviewStatus> awaitingReviewerStatuses() {
        return AWAITING_REVIEWER;
    }

    public static boolean isOpen(ReviewStatus s) {
        return s != null && OPEN.contains(s);
    }

    /** A decision is only valid for a refund that is ON_HOLD with a review case waiting for a reviewer. */
    public static boolean canDecide(RefundStatus refundStatus, ReviewStatus reviewStatus) {
        return refundStatus == RefundStatus.ON_HOLD && reviewStatus != null && AWAITING_REVIEWER.contains(reviewStatus);
    }

    /** Whether {@code action} is a legal transition from {@code from} (who may do it is checked by the service). */
    public static boolean allows(ReviewStatus from, ReviewAction action) {
        if (from == null || action == null) return false;
        return switch (action) {
            case START -> from == ReviewStatus.PENDING;
            case REQUEST_INFO -> AWAITING_REVIEWER.contains(from);
            case RESUBMIT -> from == ReviewStatus.MORE_INFO_REQUIRED;
            case ESCALATE -> from == ReviewStatus.PENDING || from == ReviewStatus.UNDER_REVIEW;
            case APPROVE, REJECT -> AWAITING_REVIEWER.contains(from);
            case NOTE -> OPEN.contains(from);
        };
    }

    /** Status of the review case after a legal action. */
    public static ReviewStatus next(ReviewStatus from, ReviewAction action) {
        if (!allows(from, action)) throw new IllegalStateException(action + " is not allowed from " + from);
        return switch (action) {
            case START -> ReviewStatus.UNDER_REVIEW;
            case REQUEST_INFO -> ReviewStatus.MORE_INFO_REQUIRED;
            case RESUBMIT -> ReviewStatus.PENDING;
            case ESCALATE -> ReviewStatus.ESCALATED;
            case APPROVE -> ReviewStatus.APPROVED;
            case REJECT -> ReviewStatus.REJECTED;
            case NOTE -> from;
        };
    }

    /** Status the refund returns to on approval (the one it had before the hold; safe default REQUESTED). */
    public static RefundStatus statusAfterApproval(RefundStatus previous) {
        return previous != null && RESTORABLE.contains(previous) ? previous : RefundStatus.REQUESTED;
    }

    public static RefundStatus statusAfter(ReviewDecision decision, RefundStatus previous) {
        return decision == ReviewDecision.APPROVE ? statusAfterApproval(previous) : RefundStatus.REJECTED;
    }

    /** Returns an error message, or null when the reason is acceptable. */
    public static String validateReason(String reason) {
        if (reason == null || reason.isBlank()) return "A reason is required for every decision.";
        String t = reason.trim();
        if (t.length() < MIN_REASON_LENGTH) return "Reason must be at least " + MIN_REASON_LENGTH + " characters.";
        if (t.length() > MAX_REASON_LENGTH) return "Reason must be at most " + MAX_REASON_LENGTH + " characters.";
        return null;
    }
}
