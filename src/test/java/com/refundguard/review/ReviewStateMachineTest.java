package com.refundguard.review;

import com.refundguard.model.RefundStatus;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ReviewStateMachineTest {

    @Test
    void onlyNewlyCreatedRefundsCanBeHeld() {
        assertTrue(ReviewStateMachine.canHold(RefundStatus.REQUESTED));
        assertTrue(ReviewStateMachine.canHold(RefundStatus.GATEWAY_PENDING));
        for (RefundStatus s : RefundStatus.values()) {
            if (s != RefundStatus.REQUESTED && s != RefundStatus.GATEWAY_PENDING) assertFalse(ReviewStateMachine.canHold(s), s.name());
        }
    }

    @Test
    void decisionOnlyValidWhenOnHoldAndPending() {
        assertTrue(ReviewStateMachine.canDecide(RefundStatus.ON_HOLD, ReviewStatus.PENDING));
        for (RefundStatus s : RefundStatus.values()) {
            if (s != RefundStatus.ON_HOLD) assertFalse(ReviewStateMachine.canDecide(s, ReviewStatus.PENDING), s.name());
        }
        // repeated / conflicting decisions: review already closed
        assertFalse(ReviewStateMachine.canDecide(RefundStatus.ON_HOLD, ReviewStatus.APPROVED));
        assertFalse(ReviewStateMachine.canDecide(RefundStatus.ON_HOLD, ReviewStatus.REJECTED));
        assertFalse(ReviewStateMachine.canDecide(RefundStatus.ON_HOLD, ReviewStatus.SUPERSEDED));
    }

    @Test
    void approvalRestoresPreviousStatusWithSafeDefault() {
        assertEquals(RefundStatus.GATEWAY_PENDING, ReviewStateMachine.statusAfter(ReviewDecision.APPROVE, RefundStatus.GATEWAY_PENDING));
        assertEquals(RefundStatus.REQUESTED, ReviewStateMachine.statusAfter(ReviewDecision.APPROVE, RefundStatus.REQUESTED));
        assertEquals(RefundStatus.REQUESTED, ReviewStateMachine.statusAfter(ReviewDecision.APPROVE, null));
        assertEquals(RefundStatus.REQUESTED, ReviewStateMachine.statusAfter(ReviewDecision.APPROVE, RefundStatus.SETTLEMENT_MATCHED));
    }

    @Test
    void rejectionAlwaysEndsRejected() {
        assertEquals(RefundStatus.REJECTED, ReviewStateMachine.statusAfter(ReviewDecision.REJECT, RefundStatus.REQUESTED));
        assertEquals(RefundStatus.REJECTED, ReviewStateMachine.statusAfter(ReviewDecision.REJECT, null));
    }

    @Test
    void reasonIsMandatoryAndBounded() {
        assertNotNull(ReviewStateMachine.validateReason(null));
        assertNotNull(ReviewStateMachine.validateReason(""));
        assertNotNull(ReviewStateMachine.validateReason("   "));
        assertNotNull(ReviewStateMachine.validateReason("abcd"));
        assertNull(ReviewStateMachine.validateReason("abcde"));
        assertNull(ReviewStateMachine.validateReason("x".repeat(500)));
        assertNotNull(ReviewStateMachine.validateReason("x".repeat(501)));
    }

    @Test
    void decisionMapsToReviewStatus() {
        assertEquals(ReviewStatus.APPROVED, ReviewDecision.APPROVE.toStatus());
        assertEquals(ReviewStatus.REJECTED, ReviewDecision.REJECT.toStatus());
    }

    // ── reviewer case workflow ──

    @Test
    void manualSubmissionOnlyBeforeSettlementAndWhenNotHeld() {
        for (RefundStatus s : new RefundStatus[]{RefundStatus.REQUESTED, RefundStatus.GATEWAY_PENDING, RefundStatus.GATEWAY_PROCESSED,
                RefundStatus.POTENTIAL_DELAY, RefundStatus.SLA_BREACHED, RefundStatus.FAILED_REPORTED}) {
            assertTrue(ReviewStateMachine.canSubmit(s), s.name());
        }
        for (RefundStatus s : new RefundStatus[]{RefundStatus.SETTLEMENT_MATCHED, RefundStatus.AMOUNT_MISMATCH,
                RefundStatus.RESOLVED, RefundStatus.ON_HOLD, RefundStatus.REJECTED}) {
            assertFalse(ReviewStateMachine.canSubmit(s), s.name());
        }
    }

    @Test
    void lifecycleTransitions() {
        assertEquals(ReviewStatus.UNDER_REVIEW, ReviewStateMachine.next(ReviewStatus.PENDING, ReviewAction.START));
        assertEquals(ReviewStatus.MORE_INFO_REQUIRED, ReviewStateMachine.next(ReviewStatus.UNDER_REVIEW, ReviewAction.REQUEST_INFO));
        assertEquals(ReviewStatus.PENDING, ReviewStateMachine.next(ReviewStatus.MORE_INFO_REQUIRED, ReviewAction.RESUBMIT));
        assertEquals(ReviewStatus.ESCALATED, ReviewStateMachine.next(ReviewStatus.UNDER_REVIEW, ReviewAction.ESCALATE));
        assertEquals(ReviewStatus.ESCALATED, ReviewStateMachine.next(ReviewStatus.PENDING, ReviewAction.ESCALATE));
        assertEquals(ReviewStatus.MORE_INFO_REQUIRED, ReviewStateMachine.next(ReviewStatus.ESCALATED, ReviewAction.REQUEST_INFO));
        assertEquals(ReviewStatus.APPROVED, ReviewStateMachine.next(ReviewStatus.ESCALATED, ReviewAction.APPROVE));
        assertEquals(ReviewStatus.APPROVED, ReviewStateMachine.next(ReviewStatus.PENDING, ReviewAction.APPROVE)); // existing direct decision
        assertEquals(ReviewStatus.REJECTED, ReviewStateMachine.next(ReviewStatus.UNDER_REVIEW, ReviewAction.REJECT));
        assertEquals(ReviewStatus.UNDER_REVIEW, ReviewStateMachine.next(ReviewStatus.UNDER_REVIEW, ReviewAction.NOTE));
    }

    @Test
    void illegalTransitionsAreRefused() {
        assertFalse(ReviewStateMachine.allows(ReviewStatus.MORE_INFO_REQUIRED, ReviewAction.APPROVE));
        assertFalse(ReviewStateMachine.allows(ReviewStatus.MORE_INFO_REQUIRED, ReviewAction.START));
        assertFalse(ReviewStateMachine.allows(ReviewStatus.UNDER_REVIEW, ReviewAction.START));
        assertFalse(ReviewStateMachine.allows(ReviewStatus.ESCALATED, ReviewAction.ESCALATE));
        assertFalse(ReviewStateMachine.allows(ReviewStatus.ESCALATED, ReviewAction.START));
        assertFalse(ReviewStateMachine.allows(ReviewStatus.PENDING, ReviewAction.RESUBMIT));
        for (ReviewStatus closed : new ReviewStatus[]{ReviewStatus.APPROVED, ReviewStatus.REJECTED, ReviewStatus.SUPERSEDED}) {
            assertFalse(ReviewStateMachine.isOpen(closed));
            for (ReviewAction a : ReviewAction.values()) assertFalse(ReviewStateMachine.allows(closed, a), closed + " " + a);
        }
        assertFalse(ReviewStateMachine.allows(null, ReviewAction.APPROVE));
        assertThrows(IllegalStateException.class, () -> ReviewStateMachine.next(ReviewStatus.APPROVED, ReviewAction.APPROVE));
    }

    @Test
    void decisionPossibleOnlyWhileWaitingForAReviewer() {
        assertTrue(ReviewStateMachine.canDecide(RefundStatus.ON_HOLD, ReviewStatus.UNDER_REVIEW));
        assertTrue(ReviewStateMachine.canDecide(RefundStatus.ON_HOLD, ReviewStatus.ESCALATED));
        assertFalse(ReviewStateMachine.canDecide(RefundStatus.ON_HOLD, ReviewStatus.MORE_INFO_REQUIRED));
        assertFalse(ReviewStateMachine.canDecide(RefundStatus.REQUESTED, ReviewStatus.UNDER_REVIEW));
    }

    @Test
    void approvalRestoresManuallyHeldStatuses() {
        assertEquals(RefundStatus.GATEWAY_PROCESSED, ReviewStateMachine.statusAfter(ReviewDecision.APPROVE, RefundStatus.GATEWAY_PROCESSED));
        assertEquals(RefundStatus.SLA_BREACHED, ReviewStateMachine.statusAfter(ReviewDecision.APPROVE, RefundStatus.SLA_BREACHED));
        assertEquals(RefundStatus.REQUESTED, ReviewStateMachine.statusAfter(ReviewDecision.APPROVE, RefundStatus.ON_HOLD));
    }
}
