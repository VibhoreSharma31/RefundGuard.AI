package com.refundguard.model;

import jakarta.persistence.*;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

/**
 * One triggered rule of a persisted {@link RiskAssessment}: what fired, how many points it added, why, and the data
 * values it looked at. Immutable once written; ordered by {@code ordinal} (rule-evaluation order).
 */
@Entity
@Table(name = "rg_risk_factors", indexes = {
        @Index(name = "ix_rg_risk_factor_assess", columnList = "assessment_id"),
        @Index(name = "ix_rg_risk_factor_code", columnList = "rule_code")})
public class RiskFactor {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "assessment_id", nullable = false, foreignKey = @ForeignKey(name = "fk_rg_risk_factor_assess"))
    @OnDelete(action = OnDeleteAction.CASCADE) // factors live and die with their assessment
    private RiskAssessment assessment;

    @Column(name = "ordinal", nullable = false)
    private Integer ordinal;

    @Column(name = "rule_code", nullable = false, length = 50)
    private String ruleCode;

    /** Score contribution (0..100; rules only add points). */
    @Column(name = "points", nullable = false)
    private Integer points;

    @Column(name = "reason", nullable = false, length = 500)
    private String reason;

    /** JSON object of the data values the rule used, e.g. {"refundAmount":"₹1500.00","orderAmount":"₹1000.00"}. */
    @Column(name = "evidence_json", length = 1000)
    private String evidenceJson;

    /** True when the rule is a policy violation that forces a hold for reviewer approval. */
    @Column(name = "policy_violation", nullable = false)
    private Boolean policyViolation = Boolean.FALSE;

    public RiskFactor() {}

    public Long getId() { return id; }
    public RiskAssessment getAssessment() { return assessment; }
    public void setAssessment(RiskAssessment assessment) { this.assessment = assessment; }
    public Integer getOrdinal() { return ordinal; }
    public void setOrdinal(Integer ordinal) { this.ordinal = ordinal; }
    public String getRuleCode() { return ruleCode; }
    public void setRuleCode(String ruleCode) { this.ruleCode = ruleCode; }
    public Integer getPoints() { return points; }
    public void setPoints(Integer points) { this.points = points; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public String getEvidenceJson() { return evidenceJson; }
    public void setEvidenceJson(String evidenceJson) { this.evidenceJson = evidenceJson; }
    public boolean isPolicyViolation() { return Boolean.TRUE.equals(policyViolation); }
    public void setPolicyViolation(boolean policyViolation) { this.policyViolation = policyViolation; }
}
