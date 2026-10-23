package com.refundguard.risk;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One triggered rule: a stable code, the points it added, a human-readable reason, the evidence (the actual
 * data values the rule looked at) and whether it is a policy violation that forces a hold for reviewer approval.
 */
public record RiskIndicator(String code, int points, String reason, Map<String, String> evidence, boolean policyViolation) {

    public RiskIndicator {
        evidence = evidence == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(evidence)); // keeps display order
    }

    public RiskIndicator(String code, int points, String reason) {
        this(code, points, reason, Map.of(), false);
    }
}
