package com.refundguard.risk;

import java.time.LocalDateTime;
import java.util.List;

public record RiskResult(
        int score,
        RiskLevel level,
        LocalDateTime assessedAt,
        List<RiskIndicator> indicators,
        List<String> notEvaluated,
        String inputFingerprint,
        String engineVersion) {

    /** Reasons in rule order (deterministic). */
    public List<String> reasons() {
        return indicators.stream().map(i -> "+" + i.points() + " " + i.reason()).toList();
    }

    /** True when at least one triggered rule is a policy violation (forces a hold for reviewer approval). */
    public boolean policyViolation() {
        return indicators.stream().anyMatch(RiskIndicator::policyViolation);
    }
}
