package com.refundguard.model;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "rg_cases")
public class Case {

    /** Long text columns are capped so they always fit Oracle VARCHAR2(4000 BYTE) (₹ is 3 bytes in UTF-8). */
    public static final int MAX_TEXT = 1200;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Null for settlement-side cases where no refund record exists (UNMATCHED_SETTLEMENT). */
    @Column(name = "refund_id")
    private Long refundId;

    /** Set when the case was raised from a bank settlement row. */
    @Column(name = "settlement_row_id")
    private Long settlementRowId;

    @Column(name = "order_id")
    private String orderId;

    @Column(name = "issue_type", nullable = false)
    private String issueType;

    @Column(nullable = false)
    private String severity;

    @Column(nullable = false)
    private String status;

    @Column(name = "ai_root_cause_hypothesis", length = 4000)
    private String aiRootCauseHypothesis;

    @Column(name = "recommended_action", length = 4000)
    private String recommendedAction;

    /** Draft update the ops team can send to the customer (AI generated). */
    @Column(name = "customer_message", length = 4000)
    private String customerMessage;

    /** "RULES" (built-in analyser) or "CLAUDE (&lt;model&gt;)". */
    @Column(name = "ai_source")
    private String aiSource;

    @Column(name = "ai_analyzed_at")
    private LocalDateTime aiAnalyzedAt;

    /** Estimated compensation exposure for SLA breaches, refreshed by the SLA job. */
    @Column(name = "compensation_due")
    private BigDecimal compensationDue;

    @Lob
    @Column(name = "resolution_notes")
    private String resolutionNotes;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "resolved_at")
    private LocalDateTime resolvedAt;

    public Case() {}

    public static String clip(String s) {
        if (s == null || s.length() <= MAX_TEXT) return s;
        return s.substring(0, MAX_TEXT - 1) + "…";
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getRefundId() { return refundId; }
    public void setRefundId(Long refundId) { this.refundId = refundId; }
    public Long getSettlementRowId() { return settlementRowId; }
    public void setSettlementRowId(Long settlementRowId) { this.settlementRowId = settlementRowId; }
    public String getOrderId() { return orderId; }
    public void setOrderId(String orderId) { this.orderId = orderId; }
    public String getIssueType() { return issueType; }
    public void setIssueType(String issueType) { this.issueType = issueType; }
    public String getSeverity() { return severity; }
    public void setSeverity(String severity) { this.severity = severity; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getAiRootCauseHypothesis() { return aiRootCauseHypothesis; }
    public void setAiRootCauseHypothesis(String hypothesis) { this.aiRootCauseHypothesis = clip(hypothesis); }
    public String getRecommendedAction() { return recommendedAction; }
    public void setRecommendedAction(String recommendedAction) { this.recommendedAction = clip(recommendedAction); }
    public String getCustomerMessage() { return customerMessage; }
    public void setCustomerMessage(String customerMessage) { this.customerMessage = clip(customerMessage); }
    public String getAiSource() { return aiSource; }
    public void setAiSource(String aiSource) { this.aiSource = aiSource; }
    public LocalDateTime getAiAnalyzedAt() { return aiAnalyzedAt; }
    public void setAiAnalyzedAt(LocalDateTime aiAnalyzedAt) { this.aiAnalyzedAt = aiAnalyzedAt; }
    public BigDecimal getCompensationDue() { return compensationDue; }
    public void setCompensationDue(BigDecimal compensationDue) { this.compensationDue = compensationDue; }
    public String getResolutionNotes() { return resolutionNotes; }
    public void setResolutionNotes(String resolutionNotes) { this.resolutionNotes = resolutionNotes; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getResolvedAt() { return resolvedAt; }
    public void setResolvedAt(LocalDateTime resolvedAt) { this.resolvedAt = resolvedAt; }
}
