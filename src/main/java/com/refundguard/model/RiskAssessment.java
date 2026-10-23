package com.refundguard.model;

import com.refundguard.risk.RiskLevel;
import jakarta.persistence.*;

import java.time.LocalDateTime;

/** A persisted, immutable result of the rule-based risk engine for one refund at one point in time. */
@Entity
@Table(name = "rg_risk_assessments", indexes = {
        @Index(name = "ix_rg_risk_refund", columnList = "refund_id"),
        @Index(name = "ix_rg_risk_refund_id", columnList = "refund_id, id")}) // latest assessment per refund
public class RiskAssessment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "refund_id", nullable = false)
    private Long refundId;

    @Column(name = "risk_score", nullable = false)
    private Integer score;

    @Enumerated(EnumType.STRING)
    @Column(name = "risk_level", nullable = false, length = 20)
    private RiskLevel level;

    @Column(name = "assessed_at", nullable = false)
    private LocalDateTime assessedAt;

    /** e.g. RULE_BASED_V1 - this is a rule engine, not a machine-learning model. */
    @Column(name = "engine_version", nullable = false, length = 40)
    private String engineVersion;

    @Column(name = "input_fingerprint", length = 64)
    private String inputFingerprint;

    @Column(name = "config_fingerprint", length = 64)
    private String configFingerprint;

    /** INGESTION / HOLD_CHECK / MANUAL / DECISION */
    @Column(name = "assessment_trigger", length = 30)
    private String trigger;

    /**
     * Legacy (RULE_BASED_V1) JSON array of {code, points, reason}. Assessments written since V2 store their factors in
     * rg_risk_factors instead and leave this NULL; readers fall back to it only for old rows.
     */
    @Column(name = "indicators_json", length = 4000)
    private String indicatorsJson;

    /** At least one triggered rule is a policy violation (forces a hold for reviewer approval). NULL on legacy rows = false. */
    @Column(name = "policy_violation")
    private Boolean policyViolation;

    /** JSON array of strings: rules that could not be evaluated because data was missing. */
    @Column(name = "not_evaluated_json", length = 2000)
    private String notEvaluatedJson;

    public RiskAssessment() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getRefundId() { return refundId; }
    public void setRefundId(Long refundId) { this.refundId = refundId; }
    public Integer getScore() { return score; }
    public void setScore(Integer score) { this.score = score; }
    public RiskLevel getLevel() { return level; }
    public void setLevel(RiskLevel level) { this.level = level; }
    public LocalDateTime getAssessedAt() { return assessedAt; }
    public void setAssessedAt(LocalDateTime assessedAt) { this.assessedAt = assessedAt; }
    public String getEngineVersion() { return engineVersion; }
    public void setEngineVersion(String engineVersion) { this.engineVersion = engineVersion; }
    public String getInputFingerprint() { return inputFingerprint; }
    public void setInputFingerprint(String inputFingerprint) { this.inputFingerprint = inputFingerprint; }
    public String getConfigFingerprint() { return configFingerprint; }
    public void setConfigFingerprint(String configFingerprint) { this.configFingerprint = configFingerprint; }
    public String getTrigger() { return trigger; }
    public void setTrigger(String trigger) { this.trigger = trigger; }
    public String getIndicatorsJson() { return indicatorsJson; }
    public void setIndicatorsJson(String indicatorsJson) { this.indicatorsJson = indicatorsJson; }
    public boolean isPolicyViolation() { return Boolean.TRUE.equals(policyViolation); }
    public void setPolicyViolation(boolean policyViolation) { this.policyViolation = policyViolation; }
    public String getNotEvaluatedJson() { return notEvaluatedJson; }
    public void setNotEvaluatedJson(String notEvaluatedJson) { this.notEvaluatedJson = notEvaluatedJson; }
}
