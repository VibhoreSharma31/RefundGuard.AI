package com.refundguard.model;

import com.refundguard.review.ReviewDecision;
import com.refundguard.review.ReviewStatus;
import jakarta.persistence.*;

import java.time.LocalDateTime;

/** One hold-and-release review of a refund: why it was held, who decided, when, and on which assessment. */
@Entity
@Table(name = "rg_refund_reviews", indexes = @Index(name = "ix_rg_review_refund", columnList = "refund_id"))
public class RefundReview {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Version
    @Column(name = "row_version")
    private Long rowVersion;

    @Column(name = "refund_id", nullable = false)
    private Long refundId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ReviewStatus status;

    /** Refund status before the hold; restored on approval. */
    @Enumerated(EnumType.STRING)
    @Column(name = "previous_status", length = 30)
    private RefundStatus previousStatus;

    /** Assessment that caused the hold. */
    @Column(name = "hold_assessment_id")
    private Long holdAssessmentId;

    @Column(name = "hold_reason", length = 1000)
    private String holdReason;

    @Column(name = "held_at", nullable = false)
    private LocalDateTime heldAt;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private ReviewDecision decision;

    @Column(name = "decided_by", length = 64)
    private String decidedBy;

    @Column(name = "decided_by_role", length = 20)
    private String decidedByRole;

    @Column(name = "decision_reason", length = 1000)
    private String decisionReason;

    @Column(name = "decided_at")
    private LocalDateTime decidedAt;

    /** Assessment re-computed at decision time (the one the reviewer's decision is linked to). */
    @Column(name = "decision_assessment_id")
    private Long decisionAssessmentId;

    /** True when approval triggered the SIMULATED release (no real payment system is integrated). */
    @Column(name = "simulated_release")
    private Boolean simulatedRelease;

    // ── Review-case fields (all nullable: rows written by earlier versions remain valid) ──

    /** AUTO_RISK (held by the risk engine) or MANUAL (submitted by an operator/admin). Null = AUTO_RISK. */
    @Column(name = "origin", length = 20)
    private String origin;

    @Column(name = "submitted_by", length = 64)
    private String submittedBy;

    @Column(name = "submitted_by_role", length = 20)
    private String submittedByRole;

    @Column(name = "submission_reason", length = 1000)
    private String submissionReason;

    /** Reviewer who started the review (UNDER_REVIEW). */
    @Column(name = "assigned_to", length = 64)
    private String assignedTo;

    @Column(name = "review_started_at")
    private LocalDateTime reviewStartedAt;

    @Column(name = "escalated_by", length = 64)
    private String escalatedBy;

    @Column(name = "escalated_at")
    private LocalDateTime escalatedAt;

    @Column(name = "escalation_reason", length = 1000)
    private String escalationReason;

    @Column(name = "info_requested_by", length = 64)
    private String infoRequestedBy;

    @Column(name = "info_requested_at")
    private LocalDateTime infoRequestedAt;

    @Column(name = "info_request_reason", length = 1000)
    private String infoRequestReason;

    @Column(name = "resubmission_count")
    private Integer resubmissionCount;

    @Column(name = "last_action_at")
    private LocalDateTime lastActionAt;

    public RefundReview() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getRowVersion() { return rowVersion; }
    public Long getRefundId() { return refundId; }
    public void setRefundId(Long refundId) { this.refundId = refundId; }
    public ReviewStatus getStatus() { return status; }
    public void setStatus(ReviewStatus status) { this.status = status; }
    public RefundStatus getPreviousStatus() { return previousStatus; }
    public void setPreviousStatus(RefundStatus previousStatus) { this.previousStatus = previousStatus; }
    public Long getHoldAssessmentId() { return holdAssessmentId; }
    public void setHoldAssessmentId(Long holdAssessmentId) { this.holdAssessmentId = holdAssessmentId; }
    public String getHoldReason() { return holdReason; }
    public void setHoldReason(String holdReason) { this.holdReason = holdReason; }
    public LocalDateTime getHeldAt() { return heldAt; }
    public void setHeldAt(LocalDateTime heldAt) { this.heldAt = heldAt; }
    public ReviewDecision getDecision() { return decision; }
    public void setDecision(ReviewDecision decision) { this.decision = decision; }
    public String getDecidedBy() { return decidedBy; }
    public void setDecidedBy(String decidedBy) { this.decidedBy = decidedBy; }
    public String getDecidedByRole() { return decidedByRole; }
    public void setDecidedByRole(String decidedByRole) { this.decidedByRole = decidedByRole; }
    public String getDecisionReason() { return decisionReason; }
    public void setDecisionReason(String decisionReason) { this.decisionReason = decisionReason; }
    public LocalDateTime getDecidedAt() { return decidedAt; }
    public void setDecidedAt(LocalDateTime decidedAt) { this.decidedAt = decidedAt; }
    public Long getDecisionAssessmentId() { return decisionAssessmentId; }
    public void setDecisionAssessmentId(Long decisionAssessmentId) { this.decisionAssessmentId = decisionAssessmentId; }
    public Boolean getSimulatedRelease() { return simulatedRelease; }
    public void setSimulatedRelease(Boolean simulatedRelease) { this.simulatedRelease = simulatedRelease; }
    public String getOrigin() { return origin == null ? ORIGIN_AUTO : origin; }
    public void setOrigin(String origin) { this.origin = origin; }
    public String getSubmittedBy() { return submittedBy; }
    public void setSubmittedBy(String submittedBy) { this.submittedBy = submittedBy; }
    public String getSubmittedByRole() { return submittedByRole; }
    public void setSubmittedByRole(String submittedByRole) { this.submittedByRole = submittedByRole; }
    public String getSubmissionReason() { return submissionReason; }
    public void setSubmissionReason(String submissionReason) { this.submissionReason = submissionReason; }
    public String getAssignedTo() { return assignedTo; }
    public void setAssignedTo(String assignedTo) { this.assignedTo = assignedTo; }
    public LocalDateTime getReviewStartedAt() { return reviewStartedAt; }
    public void setReviewStartedAt(LocalDateTime reviewStartedAt) { this.reviewStartedAt = reviewStartedAt; }
    public String getEscalatedBy() { return escalatedBy; }
    public void setEscalatedBy(String escalatedBy) { this.escalatedBy = escalatedBy; }
    public LocalDateTime getEscalatedAt() { return escalatedAt; }
    public void setEscalatedAt(LocalDateTime escalatedAt) { this.escalatedAt = escalatedAt; }
    public String getEscalationReason() { return escalationReason; }
    public void setEscalationReason(String escalationReason) { this.escalationReason = escalationReason; }
    public String getInfoRequestedBy() { return infoRequestedBy; }
    public void setInfoRequestedBy(String infoRequestedBy) { this.infoRequestedBy = infoRequestedBy; }
    public LocalDateTime getInfoRequestedAt() { return infoRequestedAt; }
    public void setInfoRequestedAt(LocalDateTime infoRequestedAt) { this.infoRequestedAt = infoRequestedAt; }
    public String getInfoRequestReason() { return infoRequestReason; }
    public void setInfoRequestReason(String infoRequestReason) { this.infoRequestReason = infoRequestReason; }
    public int getResubmissionCount() { return resubmissionCount == null ? 0 : resubmissionCount; }
    public void setResubmissionCount(Integer resubmissionCount) { this.resubmissionCount = resubmissionCount; }
    public LocalDateTime getLastActionAt() { return lastActionAt; }
    public void setLastActionAt(LocalDateTime lastActionAt) { this.lastActionAt = lastActionAt; }

    public static final String ORIGIN_AUTO = "AUTO_RISK";
    public static final String ORIGIN_MANUAL = "MANUAL";
}
