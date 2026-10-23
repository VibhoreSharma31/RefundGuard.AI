package com.refundguard.risk;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.refundguard.model.*;
import com.refundguard.repository.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;

/**
 * Gathers REAL data for a refund (order, gateway events, same-customer refunds, open cases, bank settlement), runs the
 * rule-based engine and persists the result (rg_risk_assessments + one rg_risk_factors row per triggered rule).
 * Nothing here invents data: when something is missing the input field is null and the engine reports the rule as
 * "not evaluated". The refund's processing status is never used as a risk input.
 */
@Service
public class RiskScoringService {

    private static final Logger log = LoggerFactory.getLogger(RiskScoringService.class);

    public static final String TRIGGER_INGESTION = "INGESTION";
    public static final String TRIGGER_MANUAL = "MANUAL";
    public static final String TRIGGER_DECISION = "DECISION";
    /** Re-scored after bank settlement data changed (reconciliation). */
    public static final String TRIGGER_SETTLEMENT = "SETTLEMENT";
    /** Admin back-fill of refunds that were never assessed. */
    public static final String TRIGGER_BACKFILL = "BACKFILL";

    public static final String AUDIT_SOURCE = "RISK_ENGINE";

    private static final List<String> DISCREPANCY_CASES = List.of("AMOUNT_MISMATCH", "DUPLICATE_SETTLEMENT", "REFUND_FAILED");

    /** Outcome of an assessment request: the stored assessment and whether an identical existing one was reused. */
    public record Outcome(RiskAssessment assessment, boolean reused) {}

    private final RiskSettings settings;
    private final RiskScoringEngine engine;
    private final RiskAssessmentRepository assessmentRepository;
    private final RiskFactorRepository factorRepository;
    private final OrderRepository orderRepository;
    private final RefundRepository refundRepository;
    private final RefundEventRepository eventRepository;
    private final CaseRepository caseRepository;
    private final SettlementRowRepository settlementRowRepository;
    private final ObjectMapper mapper;

    public RiskScoringService(RiskSettings settings, RiskAssessmentRepository assessmentRepository,
                              RiskFactorRepository factorRepository, OrderRepository orderRepository,
                              RefundRepository refundRepository, RefundEventRepository eventRepository,
                              CaseRepository caseRepository, SettlementRowRepository settlementRowRepository,
                              ObjectMapper mapper) {
        settings.validate(); // fail fast on inconsistent thresholds
        this.settings = settings;
        this.engine = new RiskScoringEngine(settings);
        this.assessmentRepository = assessmentRepository;
        this.factorRepository = factorRepository;
        this.orderRepository = orderRepository;
        this.refundRepository = refundRepository;
        this.eventRepository = eventRepository;
        this.caseRepository = caseRepository;
        this.settlementRowRepository = settlementRowRepository;
        this.mapper = mapper;
    }

    public RiskConfig config() { return settings; }

    /** Facts about the refund as of {@code now}. */
    public RiskInput buildInput(Refund r, LocalDateTime now) {
        Optional<Order> order = r.getOrderId() == null ? Optional.empty() : orderRepository.findByExternalOrderId(r.getOrderId());

        Integer refundsForOrder = null;
        BigDecimal refundsTotalForOrder = null;
        if (r.getOrderId() != null) {
            List<Refund> sameOrder = refundRepository.findByOrderIdIn(Set.of(r.getOrderId()));
            refundsForOrder = sameOrder.size();
            BigDecimal total = BigDecimal.ZERO;
            for (Refund x : sameOrder) {
                if (x.getDerivedStatus() == RefundStatus.REJECTED || x.getAmount() == null) continue; // rejected = never paid out
                total = total.add(x.getAmount());
            }
            refundsTotalForOrder = total;
        }
        Integer created = r.getId() == null ? null : (int) eventRepository.countByRefundIdAndEventTypeIgnoreCase(r.getId(), "refund.created");
        Integer openCases = null, openMismatch = null, openDuplicate = null;
        if (r.getId() != null) {
            openCases = (int) caseRepository.countByRefundIdAndStatusAndIssueTypeIn(r.getId(), "OPEN", DISCREPANCY_CASES);
            openMismatch = (int) caseRepository.countByRefundIdAndStatusAndIssueTypeIn(r.getId(), "OPEN", List.of("AMOUNT_MISMATCH"));
            openDuplicate = (int) caseRepository.countByRefundIdAndStatusAndIssueTypeIn(r.getId(), "OPEN", List.of("DUPLICATE_SETTLEMENT"));
        }

        // Bank settlement rows matched to this refund (reconciliation matches on gateway refund id, then order id).
        Integer distinctUtrs = null;
        List<String> refs = new ArrayList<>();
        if (r.getProviderRefundId() != null) refs.add(r.getProviderRefundId());
        if (r.getOrderId() != null) refs.add(r.getOrderId());
        if (!refs.isEmpty()) {
            Set<String> utrs = new HashSet<>();
            for (SettlementRow row : settlementRowRepository.findByRefundReferenceIn(refs)) {
                if (Boolean.TRUE.equals(row.getIsMatched()) && row.getUtrNumber() != null) utrs.add(row.getUtrNumber().trim());
            }
            distinctUtrs = utrs.size();
        }

        Integer customerCount = null;
        BigDecimal customerTotal = null;
        String email = order.map(Order::getCustomerEmail).map(String::trim).filter(s -> !s.isEmpty()).orElse(null);
        if (email != null) {
            Set<String> orderIds = new HashSet<>();
            for (Order o : orderRepository.findByCustomerEmailIgnoreCase(email)) orderIds.add(o.getExternalOrderId());
            LocalDateTime from = now.minusDays(settings.getCustomerWindowDays());
            int count = 0;
            BigDecimal total = BigDecimal.ZERO;
            for (Refund x : refundRepository.findByOrderIdIn(orderIds)) {
                LocalDateTime at = x.getRequestedAt();
                if (at == null || at.isBefore(from) || at.isAfter(now)) continue;
                count++;
                if (x.getAmount() != null) total = total.add(x.getAmount());
            }
            customerCount = count;
            customerTotal = total;
        }

        return new RiskInput(r.getId(), r.getAmount(), r.getCurrency(), order.isPresent(),
                order.map(Order::getAmount).orElse(null), order.map(Order::getCurrency).orElse(null),
                refundsForOrder, created, customerCount, customerTotal, openCases,
                refundsTotalForOrder, r.getSettledAmount(), distinctUtrs, openMismatch, openDuplicate);
    }

    /** Pure scoring, nothing persisted. */
    public RiskResult score(Refund r, LocalDateTime now) {
        return engine.assess(buildInput(r, now), now);
    }

    /** Scores and stores (or reuses) an assessment; see {@link #assess}. */
    @Transactional
    public RiskAssessment assessAndPersist(Refund r, String trigger) {
        return assess(r, trigger).assessment();
    }

    /**
     * Scores the refund and stores a new assessment, unless an identical one can be reused:
     *  - automatic triggers (INGESTION, SETTLEMENT, BACKFILL): reuse the latest assessment when the inputs and
     *    configuration are unchanged (same fingerprint);
     *  - MANUAL: reuse it only if it is also younger than reassess-dedupe-seconds (protects against double clicks /
     *    repeated requests while still allowing an auditable re-run later);
     *  - DECISION: always stored, so every reviewer decision is linked to its own assessment.
     */
    @Transactional
    public Outcome assess(Refund r, String trigger) {
        LocalDateTime now = LocalDateTime.now();
        RiskResult res = score(r, now);
        Optional<RiskAssessment> latest = r.getId() == null ? Optional.empty()
                : assessmentRepository.findFirstByRefundIdOrderByAssessedAtDescIdDesc(r.getId());
        if (latest.isPresent() && res.inputFingerprint().equals(latest.get().getInputFingerprint())) {
            boolean automatic = TRIGGER_INGESTION.equals(trigger) || TRIGGER_SETTLEMENT.equals(trigger) || TRIGGER_BACKFILL.equals(trigger);
            boolean recentManual = TRIGGER_MANUAL.equals(trigger)
                    && Duration.between(latest.get().getAssessedAt(), now).getSeconds() < settings.getReassessDedupeSeconds();
            if (automatic || recentManual) return new Outcome(latest.get(), true);
        }
        RiskAssessment a = new RiskAssessment();
        a.setRefundId(r.getId());
        a.setScore(res.score());
        a.setLevel(res.level());
        a.setAssessedAt(res.assessedAt());
        a.setEngineVersion(res.engineVersion());
        a.setInputFingerprint(res.inputFingerprint());
        a.setConfigFingerprint(settings.fingerprint());
        a.setTrigger(trigger);
        a.setPolicyViolation(res.policyViolation());
        a.setNotEvaluatedJson(clip(toJson(res.notEvaluated()), 2000, "[]"));
        a = assessmentRepository.save(a);

        int ordinal = 0;
        List<RiskFactor> rows = new ArrayList<>();
        for (RiskIndicator i : res.indicators()) {
            RiskFactor f = new RiskFactor();
            f.setAssessment(a);
            f.setOrdinal(ordinal++);
            f.setRuleCode(i.code());
            f.setPoints(i.points());
            f.setReason(cut(i.reason(), 500));
            f.setEvidenceJson(evidenceJson(i.evidence()));
            f.setPolicyViolation(i.policyViolation());
            rows.add(f);
        }
        factorRepository.saveAll(rows);
        return new Outcome(a, false);
    }

    public List<RiskAssessment> history(Long refundId) {
        return assessmentRepository.findTop10ByRefundIdOrderByAssessedAtDescIdDesc(refundId);
    }

    public Optional<RiskAssessment> latest(Long refundId) {
        return assessmentRepository.findFirstByRefundIdOrderByAssessedAtDescIdDesc(refundId);
    }

    /** Latest assessment per refund id (single query). */
    public Map<Long, RiskAssessment> latestFor(Collection<Long> refundIds) {
        Map<Long, RiskAssessment> out = new HashMap<>();
        if (refundIds.isEmpty()) return out;
        for (RiskAssessment a : assessmentRepository.findByRefundIdIn(refundIds)) {
            RiskAssessment cur = out.get(a.getRefundId());
            if (cur == null || newer(a, cur)) out.put(a.getRefundId(), a);
        }
        return out;
    }

    private static boolean newer(RiskAssessment a, RiskAssessment b) {
        int c = a.getAssessedAt().compareTo(b.getAssessedAt());
        return c > 0 || (c == 0 && a.getId() != null && b.getId() != null && a.getId() > b.getId());
    }

    // ── factors ──

    /** Factors of one assessment, in rule order. */
    public List<Map<String, Object>> factors(RiskAssessment a) {
        return factorsFor(List.of(a)).getOrDefault(a.getId(), List.of());
    }

    /**
     * Factors of many assessments with ONE query (keyed by assessment id). Assessments written before
     * RULE_BASED_V2 have no factor rows; their legacy indicators_json is used instead.
     */
    public Map<Long, List<Map<String, Object>>> factorsFor(Collection<RiskAssessment> assessments) {
        Map<Long, List<Map<String, Object>>> out = new HashMap<>();
        List<Long> ids = assessments.stream().map(RiskAssessment::getId).filter(Objects::nonNull).distinct().toList();
        if (!ids.isEmpty()) {
            for (RiskFactor f : factorRepository.findByAssessmentIds(ids)) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("code", f.getRuleCode());
                m.put("points", f.getPoints());
                m.put("reason", f.getReason());
                m.put("evidence", fromJson(f.getEvidenceJson(), new TypeReference<Map<String, Object>>() {}, Map.of()));
                m.put("policyViolation", f.isPolicyViolation());
                out.computeIfAbsent(f.getAssessment().getId(), k -> new ArrayList<>()).add(m);
            }
        }
        for (RiskAssessment a : assessments) {
            if (a.getId() == null || out.containsKey(a.getId())) continue;
            List<Map<String, Object>> legacy = new ArrayList<>();
            for (Map<String, Object> i : fromJson(a.getIndicatorsJson(), new TypeReference<List<Map<String, Object>>>() {}, List.of())) {
                Map<String, Object> m = new LinkedHashMap<>(i);
                m.putIfAbsent("evidence", Map.of());
                m.putIfAbsent("policyViolation", false);
                legacy.add(m);
            }
            out.put(a.getId(), legacy);
        }
        return out;
    }

    public record Staleness(boolean stale, String reason) {}

    /**
     * Is this assessment outdated?  Cheap check (lists): too old, or the scoring rules/configuration changed.
     * Full check (detail/decision views): additionally recomputes the inputs from current data and compares fingerprints.
     */
    public Staleness staleness(RiskAssessment a, Refund refund, boolean full) {
        LocalDateTime now = LocalDateTime.now();
        if (!RiskScoringEngine.ENGINE_VERSION.equals(a.getEngineVersion())) {
            return new Staleness(true, "Scored by an older rule version (" + a.getEngineVersion() + ") - reassess for the current rules.");
        }
        if (Duration.between(a.getAssessedAt(), now).toMinutes() > settings.getMaxAgeMinutes()) {
            return new Staleness(true, "Older than " + settings.getMaxAgeMinutes() + " minutes - reassess before relying on it.");
        }
        if (!settings.fingerprint().equals(a.getConfigFingerprint())) {
            return new Staleness(true, "Risk-scoring configuration changed since this assessment.");
        }
        if (full && refund != null) {
            String current = engine.fingerprint(buildInput(refund, now));
            if (!current.equals(a.getInputFingerprint())) {
                return new Staleness(true, "Refund, order, settlement or customer data changed since this assessment.");
            }
        }
        return new Staleness(false, null);
    }

    /** API/UI representation (loads the factors with one query). */
    public Map<String, Object> view(RiskAssessment a, Staleness st) {
        return view(a, st, null, factors(a));
    }

    /** API/UI representation with the refund status (for the recommended action). */
    public Map<String, Object> view(RiskAssessment a, Staleness st, Refund refund) {
        return view(a, st, refund == null || refund.getDerivedStatus() == null ? null : refund.getDerivedStatus().name(), factors(a));
    }

    /** API/UI representation with pre-loaded factors (use with {@link #factorsFor} in lists). */
    public Map<String, Object> view(RiskAssessment a, Staleness st, String refundStatus, List<Map<String, Object>> factors) {
        List<Map<String, Object>> f = factors == null ? List.of() : factors;
        List<String> notEvaluated = fromJson(a.getNotEvaluatedJson(), new TypeReference<List<String>>() {}, List.of());
        boolean violation = a.isPolicyViolation();
        List<String> actions = RiskExplainer.recommendedActions(a.getLevel(), f, violation, refundStatus);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("assessmentId", a.getId());
        m.put("refundId", a.getRefundId());
        m.put("score", a.getScore());
        m.put("level", a.getLevel());
        m.put("riskScore", a.getScore());
        m.put("riskLevel", a.getLevel());
        m.put("riskDescription", RiskExplainer.description(a.getScore(), a.getLevel(), f, notEvaluated));
        m.put("assessedAt", a.getAssessedAt());
        m.put("method", "RULE_BASED");
        m.put("engineVersion", a.getEngineVersion());
        m.put("ruleVersion", a.getEngineVersion());
        m.put("trigger", a.getTrigger());
        m.put("indicators", f);
        m.put("riskFactors", f);
        m.put("notEvaluated", notEvaluated);
        m.put("policyViolation", violation);
        m.put("wouldHold", settings.wouldHold(a.getScore(), violation));
        m.put("recommendedAction", actions.get(0));
        m.put("recommendedActions", actions);
        m.put("stale", st.stale());
        m.put("staleReason", st.reason());
        m.put("disclaimer", RiskExplainer.DISCLAIMER);
        return m;
    }

    /** One-line summary of triggered rules for case text / audit payloads. */
    public String reasonsSummary(RiskAssessment a) {
        return reasonsSummary(factors(a));
    }

    public static String reasonsSummary(List<Map<String, Object>> factors) {
        if (factors == null || factors.isEmpty()) return "No individual rule triggered.";
        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> i : factors) {
            if (sb.length() > 0) sb.append("; ");
            sb.append("+").append(i.get("points")).append(" ").append(i.get("reason"));
        }
        return sb.toString();
    }

    /** Writes an entry to the refund's audit ledger (rg_refund_events, source RISK_ENGINE). */
    @Transactional
    public void audit(Long refundId, String eventType, Map<String, Object> payload) {
        RefundEvent ev = new RefundEvent();
        ev.setRefundId(refundId);
        ev.setSource(AUDIT_SOURCE);
        ev.setExternalEventId("RISK-" + UUID.randomUUID());
        ev.setEventType(eventType);
        ev.setRawPayload(toJson(payload));
        ev.setEventTime(LocalDateTime.now());
        ev.setReceivedAt(LocalDateTime.now());
        eventRepository.save(ev);
    }

    // ── JSON helpers ──

    private String evidenceJson(Map<String, String> evidence) {
        Map<String, String> clipped = new LinkedHashMap<>();
        evidence.forEach((k, v) -> clipped.put(k, cut(v, 150)));
        return clip(toJson(clipped), 1000, "{}");
    }

    private String toJson(Object o) {
        try {
            return mapper.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            log.error("Could not serialise risk data", e);
            return "[]";
        }
    }

    private <T> T fromJson(String json, TypeReference<T> type, T fallback) {
        if (json == null || json.isBlank()) return fallback;
        try {
            return mapper.readValue(json, type);
        } catch (Exception e) {
            log.warn("Could not read stored risk data: {}", e.getMessage());
            return fallback;
        }
    }

    /** Keeps JSON within the column width without producing invalid JSON. */
    private static String clip(String json, int max, String fallback) {
        return json.length() <= max ? json : fallback;
    }

    private static String cut(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 3) + "...";
    }
}
