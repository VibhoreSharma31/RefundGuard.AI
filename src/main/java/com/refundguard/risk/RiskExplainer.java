package com.refundguard.risk;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Plain-language explanation and recommended next actions for a rule-based assessment. Pure functions over the
 * stored factors (code, points, reason, policyViolation), so the text is reproducible and never invents facts.
 */
public final class RiskExplainer {

    public static final String DISCLAIMER = "Rule-based risk indicator, not a machine-learning model, not a fraud probability "
            + "and not proof of fraud. Release decisions stay with the Hold-and-Release review workflow.";

    private RiskExplainer() {}

    /** @param factors maps with at least "code", "points", "reason" and optionally "policyViolation". */
    public static String description(int score, RiskLevel level, List<Map<String, Object>> factors, List<String> notEvaluated) {
        StringBuilder sb = new StringBuilder();
        sb.append("Rule-based risk score ").append(score).append("/100 (").append(level).append("). ");
        if (factors == null || factors.isEmpty()) {
            sb.append("No risk rule was triggered by the data available.");
        } else {
            List<Map<String, Object>> top = new ArrayList<>(factors);
            top.sort(Comparator.comparingInt((Map<String, Object> f) -> points(f)).reversed());
            sb.append(factors.size() == 1 ? "1 rule contributed: " : factors.size() + " rules contributed; main factors: ");
            for (int i = 0; i < Math.min(2, top.size()); i++) {
                if (i > 0) sb.append("; ");
                sb.append(top.get(i).get("reason")).append(" (+").append(points(top.get(i))).append(")");
            }
            sb.append(".");
            int raw = factors.stream().mapToInt(RiskExplainer::points).sum();
            if (raw > 100) sb.append(" The factors add up to ").append(raw).append("; the score is capped at 100.");
        }
        if (notEvaluated != null && !notEvaluated.isEmpty()) {
            sb.append(" ").append(notEvaluated.size()).append(" rule(s) could not be evaluated because data was unavailable; "
                    + "missing data is not counted as risk.");
        }
        return sb.toString();
    }

    /** Ordered list of next actions; the first one is the most important. */
    public static List<String> recommendedActions(RiskLevel level, List<Map<String, Object>> factors, boolean policyViolation,
                                                  String refundStatus) {
        List<String> out = new ArrayList<>();
        boolean settlement = has(factors, RiskScoringEngine.SETTLEMENT_AMOUNT_MISMATCH) || has(factors, RiskScoringEngine.DUPLICATE_SETTLEMENT);
        if (policyViolation) {
            out.add("Policy violation: keep the refund on hold and have a Reviewer verify it against the original payment before any release.");
        }
        if (settlement) {
            out.add("Reconcile with the bank settlement (UTR, credited amount) and resolve the related discrepancy case.");
        }
        if ("ON_HOLD".equals(refundStatus)) {
            out.add("A review case is open: the Reviewer decides through the Hold-and-Release workflow.");
        }
        if (out.isEmpty()) {
            switch (level) {
                case CRITICAL, HIGH -> out.add("Submit for Reviewer review and verify the customer, order and payment before release.");
                case MEDIUM -> out.add("Check the listed factors; escalate for review only if they are confirmed.");
                default -> out.add("No action required beyond normal processing.");
            }
        }
        return out;
    }

    private static boolean has(List<Map<String, Object>> factors, String code) {
        return factors != null && factors.stream().anyMatch(f -> code.equals(f.get("code")));
    }

    private static int points(Map<String, Object> f) {
        Object p = f.get("points");
        return p instanceof Number n ? n.intValue() : 0;
    }
}
