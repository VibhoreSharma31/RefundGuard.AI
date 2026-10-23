package com.refundguard.service;

import com.refundguard.ai.RuleBasedRootCauseAnalyzer;
import com.refundguard.model.Case;
import com.refundguard.model.Refund;
import com.refundguard.model.SettlementRow;
import com.refundguard.repository.CaseRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Single place where discrepancy cases are opened and closed.
 *
 * - One OPEN case per (refund, issue type): a refund can now have, e.g., both an
 *   AMOUNT_MISMATCH and an SLA_BREACH case, but never two open SLA_BREACH cases.
 * - Settlement-side cases (no refund record) are keyed by the settlement row.
 * - Cases are auto-resolved when the underlying problem disappears.
 */
@Service
public class CaseService {

    private static final Logger log = LoggerFactory.getLogger(CaseService.class);

    public static final String OPEN = "OPEN";
    public static final String RESOLVED = "RESOLVED";

    private final CaseRepository caseRepository;

    public CaseService(CaseRepository caseRepository) {
        this.caseRepository = caseRepository;
    }

    /** Opens a case for a refund unless an OPEN case of the same type already exists. Returns the open case. */
    @Transactional
    public Case openForRefund(Refund refund, String issueType, String severity, SettlementRow row) {
        Optional<Case> existing = caseRepository.findFirstByRefundIdAndIssueTypeAndStatus(refund.getId(), issueType, OPEN);
        if (existing.isPresent()) return existing.get();

        RuleBasedRootCauseAnalyzer.Analysis a = RuleBasedRootCauseAnalyzer.analyze(issueType, refund, row);
        Case c = new Case();
        c.setRefundId(refund.getId());
        c.setOrderId(refund.getOrderId());
        c.setSettlementRowId(row != null ? row.getId() : null);
        c.setIssueType(issueType);
        c.setSeverity(severity);
        c.setStatus(OPEN);
        c.setAiRootCauseHypothesis(a.hypothesis());
        c.setRecommendedAction(a.recommendedAction());
        c.setCustomerMessage(a.customerMessage());
        c.setAiSource("RULES");
        c.setAiAnalyzedAt(LocalDateTime.now());
        if ("SLA_BREACH".equals(issueType)) c.setCompensationDue(refund.getCompensationDue());
        c.setCreatedAt(LocalDateTime.now());
        log.warn("Opened {} case for refundId={} order={}", issueType, refund.getId(), refund.getOrderId());
        return caseRepository.save(c);
    }

    /** Gap fix: bank credited money that matches no refund record. */
    @Transactional
    public void openForUnmatchedSettlement(SettlementRow row) {
        if (caseRepository.existsBySettlementRowIdAndIssueType(row.getId(), "UNMATCHED_SETTLEMENT")) return;
        RuleBasedRootCauseAnalyzer.Analysis a = RuleBasedRootCauseAnalyzer.analyze("UNMATCHED_SETTLEMENT", null, row);
        Case c = new Case();
        c.setRefundId(null);
        c.setSettlementRowId(row.getId());
        c.setOrderId(row.getRefundReference());
        c.setIssueType("UNMATCHED_SETTLEMENT");
        c.setSeverity("HIGH");
        c.setStatus(OPEN);
        c.setAiRootCauseHypothesis(a.hypothesis());
        c.setRecommendedAction(a.recommendedAction());
        c.setAiSource("RULES");
        c.setAiAnalyzedAt(LocalDateTime.now());
        c.setCreatedAt(LocalDateTime.now());
        caseRepository.save(c);
        log.warn("Opened UNMATCHED_SETTLEMENT case for settlement row {} ref={} utr={}", row.getId(), row.getRefundReference(), row.getUtrNumber());
    }

    /** Refreshes the hypothesis/compensation of an open SLA case as days pass (only while still rule-based). */
    @Transactional
    public void refreshSlaCase(Case c, Refund refund) {
        c.setCompensationDue(refund.getCompensationDue());
        if ("RULES".equals(c.getAiSource())) {
            RuleBasedRootCauseAnalyzer.Analysis a = RuleBasedRootCauseAnalyzer.analyze("SLA_BREACH", refund, null);
            c.setAiRootCauseHypothesis(a.hypothesis());
        }
        caseRepository.save(c);
    }

    @Transactional
    public int autoResolveForRefund(Long refundId, Collection<String> issueTypes, String note) {
        int n = 0;
        for (Case c : caseRepository.findByRefundIdAndStatus(refundId, OPEN)) {
            if (issueTypes.contains(c.getIssueType())) {
                resolve(c, "[AUTO] " + note);
                n++;
            }
        }
        return n;
    }

    @Transactional
    public void autoResolveForSettlementRow(Long settlementRowId, String note) {
        for (Case c : caseRepository.findBySettlementRowIdAndStatus(settlementRowId, OPEN)) {
            if ("UNMATCHED_SETTLEMENT".equals(c.getIssueType())) resolve(c, "[AUTO] " + note);
        }
    }

    /** Case raised when the risk engine places a refund on hold; resolved only through the review workflow. */
    @Transactional
    public Case openHighRiskReviewCase(Refund refund, int score, String level, String reasons) {
        Optional<Case> existing = caseRepository.findFirstByRefundIdAndIssueTypeAndStatus(refund.getId(), "HIGH_RISK_REVIEW", OPEN);
        if (existing.isPresent()) return existing.get();
        Case c = new Case();
        c.setRefundId(refund.getId());
        c.setOrderId(refund.getOrderId());
        c.setIssueType("HIGH_RISK_REVIEW");
        c.setSeverity("CRITICAL".equals(level) ? "CRITICAL" : "HIGH");
        c.setStatus(OPEN);
        c.setAiRootCauseHypothesis("Rule-based risk assessment scored " + score + "/100 (" + level + "). " + reasons);
        c.setRecommendedAction("A REVIEWER or ADMIN must approve or reject this held refund and record a reason. "
                + "Approval only triggers a SIMULATED release; no real payment is initiated.");
        c.setAiSource("RULES");
        c.setAiAnalyzedAt(LocalDateTime.now());
        c.setCreatedAt(LocalDateTime.now());
        log.warn("Opened HIGH_RISK_REVIEW case for refundId={} order={}", refund.getId(), refund.getOrderId());
        return caseRepository.save(c);
    }

    /**
     * Case raised when an operator/admin submits a refund for review manually. Same issue type as the
     * risk-engine hold, so the generic "resolve case" endpoint cannot bypass the review either.
     */
    @Transactional
    public Case openManualReviewCase(Refund refund, String submittedBy, String reason, int score, String level) {
        Optional<Case> existing = caseRepository.findFirstByRefundIdAndIssueTypeAndStatus(refund.getId(), "HIGH_RISK_REVIEW", OPEN);
        if (existing.isPresent()) return existing.get();
        Case c = new Case();
        c.setRefundId(refund.getId());
        c.setOrderId(refund.getOrderId());
        c.setIssueType("HIGH_RISK_REVIEW");
        c.setSeverity("CRITICAL".equals(level) ? "CRITICAL" : "HIGH");
        c.setStatus(OPEN);
        c.setAiRootCauseHypothesis("Flagged for review by " + submittedBy + ": " + reason
                + " (current rule-based risk score " + score + "/100, " + level + ").");
        c.setRecommendedAction("A REVIEWER or ADMIN must review this held refund and record a decision with a reason. "
                + "Approval only triggers a SIMULATED release; no real payment is initiated.");
        c.setAiSource("RULES");
        c.setAiAnalyzedAt(LocalDateTime.now());
        c.setCreatedAt(LocalDateTime.now());
        log.warn("Opened HIGH_RISK_REVIEW case (manual submission by {}) for refundId={}", submittedBy, refund.getId());
        return caseRepository.save(c);
    }

    /** Keeps the open review case in step with the review state (more info requested, escalated, resubmitted). */
    @Transactional
    public void updateHighRiskReviewCase(Long refundId, String recommendedAction, String severity) {
        caseRepository.findFirstByRefundIdAndIssueTypeAndStatus(refundId, "HIGH_RISK_REVIEW", OPEN).ifPresent(c -> {
            c.setRecommendedAction(recommendedAction);
            if (severity != null) c.setSeverity(severity);
            caseRepository.save(c);
        });
    }

    /** Closes the open review case of a refund (called by the review workflow after a decision). */
    @Transactional
    public void resolveHighRiskReviewCase(Long refundId, String note) {
        for (Case c : caseRepository.findByRefundIdAndStatus(refundId, OPEN)) {
            if ("HIGH_RISK_REVIEW".equals(c.getIssueType())) resolve(c, note);
        }
    }

    public List<Case> openCasesFor(Long refundId) {
        return caseRepository.findByRefundIdAndStatus(refundId, OPEN);
    }

    public BigDecimal totalOpenCompensation() {
        return caseRepository.findByStatus(OPEN).stream()
                .map(Case::getCompensationDue)
                .filter(v -> v != null)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private void resolve(Case c, String note) {
        c.setStatus(RESOLVED);
        c.setResolutionNotes(note);
        c.setResolvedAt(LocalDateTime.now());
        caseRepository.save(c);
        log.info("Auto-resolved case {} ({})", c.getId(), c.getIssueType());
    }
}
