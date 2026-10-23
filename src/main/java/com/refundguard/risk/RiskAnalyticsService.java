package com.refundguard.risk;

import com.refundguard.exception.ApiException;
import com.refundguard.model.*;
import com.refundguard.repository.*;
import com.refundguard.review.ReviewStateMachine;
import com.refundguard.security.Actor;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/**
 * Read model of the Admin "Risk Analytics" section: portfolio summary, a filtered/paged list of refunds with their
 * LATEST assessment, and the full risk detail of one refund. Everything is computed by database queries over the
 * persisted refunds and assessments (no client-side loading of the whole table, no N+1: factors of a page are
 * fetched with one query). "Latest assessment" = the highest assessment id of a refund (ids are assigned in
 * assessment order).
 */
@Service
@Transactional(readOnly = true)
public class RiskAnalyticsService {

    public static final int MAX_PAGE_SIZE = 100;
    public static final int MAX_BACKFILL = 500;

    /** Latest assessment of each refund. */
    private static final String LATEST = "(select max(b.id) from RiskAssessment b where b.refundId = r.id)";

    public record Filter(String q, String level, RefundStatus status, LocalDate from, LocalDate to,
                         boolean mismatchOnly, boolean policyViolationOnly) {}

    private final EntityManager em;
    private final RiskScoringService risk;
    private final RefundRepository refunds;
    private final RefundReviewRepository reviews;
    private final OrderRepository orders;
    private final SettlementRowRepository settlements;

    public RiskAnalyticsService(EntityManager em, RiskScoringService risk, RefundRepository refunds,
                                RefundReviewRepository reviews, OrderRepository orders, SettlementRowRepository settlements) {
        this.em = em;
        this.risk = risk;
        this.refunds = refunds;
        this.reviews = reviews;
        this.orders = orders;
        this.settlements = settlements;
    }

    // ── summary ──

    public Map<String, Object> summary() {
        String latestOfExistingRefund = "a.id = (select max(b.id) from RiskAssessment b where b.refundId = a.refundId)"
                + " and exists (select 1 from Refund r where r.id = a.refundId)";
        Map<String, Long> dist = new LinkedHashMap<>();
        for (RiskLevel l : RiskLevel.values()) dist.put(l.name(), 0L);
        for (Object[] row : em.createQuery("select a.level, count(a) from RiskAssessment a where " + latestOfExistingRefund
                + " group by a.level", Object[].class).getResultList()) {
            dist.put(String.valueOf(row[0]), ((Number) row[1]).longValue());
        }
        long assessed = dist.values().stream().mapToLong(Long::longValue).sum();
        long total = refunds.count();
        long policy = em.createQuery("select count(a) from RiskAssessment a where " + latestOfExistingRefund
                + " and a.policyViolation = true", Long.class).getSingleResult();
        long outdated = em.createQuery("select count(a) from RiskAssessment a where " + latestOfExistingRefund
                        + " and (a.engineVersion <> :v or a.configFingerprint is null or a.configFingerprint <> :fp)", Long.class)
                .setParameter("v", RiskScoringEngine.ENGINE_VERSION).setParameter("fp", risk.config().fingerprint())
                .getSingleResult();
        long mismatch = em.createQuery("select count(r) from Refund r where r.derivedStatus = :mm"
                        + " or (r.settledAmount is not null and r.settledAmount <> r.amount)", Long.class)
                .setParameter("mm", RefundStatus.AMOUNT_MISMATCH).getSingleResult();

        RiskConfig c = risk.config();
        Map<String, String> bands = new LinkedHashMap<>();
        bands.put("LOW", "0-" + (c.getMediumMin() - 1));
        bands.put("MEDIUM", c.getMediumMin() + "-" + (c.getHighMin() - 1));
        bands.put("HIGH", c.getHighMin() + "-" + (c.getCriticalMin() - 1));
        bands.put("CRITICAL", c.getCriticalMin() + "-100");

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("totalRefunds", total);
        m.put("assessed", assessed);
        m.put("unassessed", Math.max(0, total - assessed));
        m.put("highOrCritical", dist.get("HIGH") + dist.get("CRITICAL"));
        m.put("policyViolations", policy);
        m.put("amountMismatches", mismatch);
        m.put("pendingReviews", reviews.countByStatusIn(ReviewStateMachine.awaitingReviewerStatuses()));
        m.put("outdatedAssessments", outdated);
        m.put("distribution", dist);
        m.put("bands", bands);
        m.put("ruleVersion", RiskScoringEngine.ENGINE_VERSION);
        m.put("holdMinScore", c.getHoldMinScore());
        m.put("holdOnPolicyViolation", c.isHoldOnPolicyViolation());
        m.put("method", "RULE_BASED");
        m.put("disclaimer", RiskExplainer.DISCLAIMER);
        m.put("generatedAt", LocalDateTime.now());
        return m;
    }

    // ── list ──

    public Map<String, Object> list(Filter f, int page, int size, String sort, String dir) {
        if (page < 0) throw ApiException.badRequest("page must be >= 0.");
        if (size < 1 || size > MAX_PAGE_SIZE) throw ApiException.badRequest("size must be between 1 and " + MAX_PAGE_SIZE + ".");
        boolean asc = "asc".equalsIgnoreCase(dir);

        StringBuilder where = new StringBuilder(" where (a.id is null or a.id = " + LATEST + ")");
        Map<String, Object> params = new HashMap<>();
        if (f.level() != null) {
            if ("UNASSESSED".equals(f.level())) {
                where.append(" and a.id is null");
            } else {
                where.append(" and a.level = :level");
                params.put("level", RiskLevel.valueOf(f.level()));
            }
        }
        if (f.status() != null) { where.append(" and r.derivedStatus = :status"); params.put("status", f.status()); }
        if (f.from() != null) { where.append(" and r.requestedAt >= :from"); params.put("from", f.from().atStartOfDay()); }
        if (f.to() != null) { where.append(" and r.requestedAt < :to"); params.put("to", f.to().plusDays(1).atStartOfDay()); }
        if (f.mismatchOnly()) {
            where.append(" and (r.derivedStatus = :mm or (r.settledAmount is not null and r.settledAmount <> r.amount))");
            params.put("mm", RefundStatus.AMOUNT_MISMATCH);
        }
        if (f.policyViolationOnly()) where.append(" and a.policyViolation = true");
        if (f.q() != null && !f.q().isBlank()) {
            String q = f.q().trim().toLowerCase(Locale.ROOT);
            // Explicit escape character: without it Hibernate renders "escape ''", which Oracle (and H2 in Oracle mode)
            // reads as ESCAPE NULL, so LIKE never matches. User wildcards are escaped, not interpreted.
            where.append(" and (lower(r.orderId) like :q escape '\\' or lower(r.providerRefundId) like :q escape '\\'"
                    + " or lower(r.providerPaymentId) like :q escape '\\'");
            params.put("q", "%" + q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%");
            String digits = q.startsWith("#") ? q.substring(1) : q;
            if (digits.matches("\\d{1,18}")) { where.append(" or r.id = :qid"); params.put("qid", Long.valueOf(digits)); }
            where.append(")");
        }
        String from = " from Refund r left join RiskAssessment a on a.refundId = r.id";
        String order = switch (sort == null ? "score" : sort) {
            case "requestedAt" -> " order by r.requestedAt " + (asc ? "asc" : "desc") + " nulls last, r.id desc";
            case "amount" -> " order by r.amount " + (asc ? "asc" : "desc") + ", r.id desc";
            case "refundId" -> " order by r.id " + (asc ? "asc" : "desc");
            case "score" -> " order by a.score " + (asc ? "asc" : "desc") + " nulls last, r.id desc";
            default -> throw ApiException.badRequest("sort must be score, requestedAt, amount or refundId.");
        };

        TypedQuery<Long> countQ = em.createQuery("select count(r)" + from + where, Long.class);
        TypedQuery<Object[]> dataQ = em.createQuery("select r, a" + from + where + order, Object[].class);
        params.forEach((k, v) -> { countQ.setParameter(k, v); dataQ.setParameter(k, v); });
        long totalElements = countQ.getSingleResult();
        List<Object[]> rows = dataQ.setFirstResult(page * size).setMaxResults(size).getResultList();

        List<RiskAssessment> pageAssessments = new ArrayList<>();
        for (Object[] row : rows) if (row[1] != null) pageAssessments.add((RiskAssessment) row[1]);
        Map<Long, List<Map<String, Object>>> factors = risk.factorsFor(pageAssessments); // one query for the page

        List<Map<String, Object>> content = new ArrayList<>();
        for (Object[] row : rows) {
            Refund r = (Refund) row[0];
            RiskAssessment a = (RiskAssessment) row[1];
            Map<String, Object> m = refundSummary(r);
            if (a == null) {
                m.put("assessed", false);
                m.put("riskScore", null);
                m.put("riskLevel", null);
                m.put("explanation", "Not assessed yet.");
            } else {
                List<Map<String, Object>> fs = factors.getOrDefault(a.getId(), List.of());
                m.put("assessed", true);
                m.put("assessmentId", a.getId());
                m.put("riskScore", a.getScore());
                m.put("riskLevel", a.getLevel());
                m.put("assessedAt", a.getAssessedAt());
                m.put("ruleVersion", a.getEngineVersion());
                m.put("policyViolation", a.isPolicyViolation());
                m.put("outdated", !RiskScoringEngine.ENGINE_VERSION.equals(a.getEngineVersion())
                        || !risk.config().fingerprint().equals(a.getConfigFingerprint()));
                m.put("factorCount", fs.size());
                m.put("explanation", shortExplanation(fs));
            }
            content.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("content", content);
        out.put("page", page);
        out.put("size", size);
        out.put("totalElements", totalElements);
        out.put("totalPages", (totalElements + size - 1) / size);
        return out;
    }

    // ── detail ──

    public Map<String, Object> detail(Long refundId) {
        Refund r = refunds.findById(refundId).orElseThrow(() -> ApiException.notFound("Refund " + refundId + " not found."));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("refund", r);
        out.put("order", r.getOrderId() == null ? null : orders.findByExternalOrderId(r.getOrderId()).orElse(null));
        List<String> refs = new ArrayList<>();
        if (r.getProviderRefundId() != null) refs.add(r.getProviderRefundId());
        if (r.getOrderId() != null) refs.add(r.getOrderId());
        out.put("settlements", refs.isEmpty() ? List.of() : settlements.findByRefundReferenceIn(refs));

        Optional<RiskAssessment> latest = risk.latest(refundId);
        out.put("assessed", latest.isPresent());
        latest.ifPresent(a -> out.put("assessment", risk.view(a, risk.staleness(a, r, true), r)));
        List<Map<String, Object>> hist = new ArrayList<>();
        for (RiskAssessment a : risk.history(refundId)) {
            Map<String, Object> h = new LinkedHashMap<>();
            h.put("assessmentId", a.getId());
            h.put("riskScore", a.getScore());
            h.put("riskLevel", a.getLevel());
            h.put("assessedAt", a.getAssessedAt());
            h.put("trigger", a.getTrigger());
            h.put("ruleVersion", a.getEngineVersion());
            h.put("policyViolation", a.isPolicyViolation());
            hist.add(h);
        }
        out.put("history", hist);
        reviews.findFirstByRefundIdAndStatusIn(refundId, ReviewStateMachine.openStatuses()).ifPresent(rv -> {
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("reviewId", rv.getId());
            o.put("status", rv.getStatus());
            out.put("openReview", o);
        });
        out.put("disclaimer", RiskExplainer.DISCLAIMER);
        return out;
    }

    // ── back-fill ──

    /**
     * Assesses refunds that have never been assessed (e.g. created before the risk engine existed). Assessment only:
     * this never places a refund on hold or changes its status - holds stay with the ingestion / review workflow.
     */
    @Transactional
    public Map<String, Object> assessUnassessed(Actor actor) {
        List<Refund> todo = em.createQuery("select r from Refund r where not exists"
                        + " (select 1 from RiskAssessment a where a.refundId = r.id) order by r.id", Refund.class)
                .setMaxResults(MAX_BACKFILL).getResultList();
        int stored = 0;
        for (Refund r : todo) {
            RiskScoringService.Outcome o = risk.assess(r, RiskScoringService.TRIGGER_BACKFILL);
            if (o.reused()) continue;
            stored++;
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("summary", "Initial risk assessment requested by " + actor.username() + " (" + actor.role() + "): "
                    + o.assessment().getScore() + "/100 " + o.assessment().getLevel() + ".");
            p.put("actor", actor.username());
            p.put("actorRole", actor.role().name());
            p.put("riskAssessmentId", o.assessment().getId());
            p.put("score", o.assessment().getScore());
            p.put("level", o.assessment().getLevel().name());
            risk.audit(r.getId(), "risk.assessed", p);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("assessed", stored);
        out.put("limit", MAX_BACKFILL);
        out.put("message", stored == 0 ? "Every refund already has an assessment."
                : stored + " refund(s) assessed. Assessment only: no refund was held or released.");
        return out;
    }

    // ── helpers ──

    private static Map<String, Object> refundSummary(Refund r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("refundId", r.getId());
        m.put("orderId", r.getOrderId());
        m.put("providerRefundId", r.getProviderRefundId());
        m.put("amount", r.getAmount());
        m.put("currency", r.getCurrency());
        m.put("status", r.getDerivedStatus());
        m.put("paymentMethod", r.getPaymentMethod());
        m.put("requestedAt", r.getRequestedAt());
        m.put("settledAmount", r.getSettledAmount());
        m.put("amountMismatch", r.getDerivedStatus() == RefundStatus.AMOUNT_MISMATCH
                || (r.getSettledAmount() != null && r.getAmount() != null && r.getSettledAmount().compareTo(r.getAmount()) != 0));
        return m;
    }

    private static String shortExplanation(List<Map<String, Object>> factors) {
        if (factors.isEmpty()) return "No risk rule triggered by the available data.";
        List<Map<String, Object>> top = new ArrayList<>(factors);
        top.sort(Comparator.comparingInt((Map<String, Object> f) -> f.get("points") instanceof Number n ? n.intValue() : 0).reversed());
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(2, top.size()); i++) {
            if (i > 0) sb.append("; ");
            sb.append(top.get(i).get("reason")).append(" (+").append(top.get(i).get("points")).append(")");
        }
        if (top.size() > 2) sb.append("; +").append(top.size() - 2).append(" more");
        return sb.toString();
    }
}
