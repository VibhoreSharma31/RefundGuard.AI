package com.refundguard.controller;

import com.refundguard.exception.ApiException;
import com.refundguard.model.RefundStatus;
import com.refundguard.risk.RiskAnalyticsService;
import com.refundguard.risk.RiskLevel;
import com.refundguard.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;

/**
 * Admin portal: risk analytics. Read-only analytics require RISK_ANALYTICS_VIEW (ADMIN only); the back-fill also
 * requires RISK_ASSESS. Reassessing a single refund uses the existing POST /api/refunds/{id}/risk/reassess.
 * Nothing here holds or releases a refund - that remains the Hold-and-Release review workflow.
 */
@RestController
@RequestMapping("/api/admin/risk")
@Tag(name = "Admin - Risk Analytics", description = "Rule-based refund risk: portfolio summary, scored refunds, explanations")
public class RiskAnalyticsController {

    private final RiskAnalyticsService analytics;

    public RiskAnalyticsController(RiskAnalyticsService analytics) {
        this.analytics = analytics;
    }

    @GetMapping("/summary")
    @PreAuthorize("hasAuthority('RISK_ANALYTICS_VIEW')")
    @Operation(summary = "Portfolio risk summary from persisted assessments (latest per refund)")
    public ResponseEntity<Map<String, Object>> summary() {
        return ResponseEntity.ok(analytics.summary());
    }

    @GetMapping("/refunds")
    @PreAuthorize("hasAuthority('RISK_ANALYTICS_VIEW')")
    @Operation(summary = "Scored refunds, filtered and paged on the server. level: LOW|MEDIUM|HIGH|CRITICAL|UNASSESSED; "
            + "status: a RefundStatus; from/to: yyyy-MM-dd (requested date, inclusive); sort: score|requestedAt|amount|refundId")
    public ResponseEntity<Map<String, Object>> refunds(@RequestParam(required = false) String q,
                                                       @RequestParam(required = false) String level,
                                                       @RequestParam(required = false) String status,
                                                       @RequestParam(required = false) String from,
                                                       @RequestParam(required = false) String to,
                                                       @RequestParam(defaultValue = "false") boolean mismatch,
                                                       @RequestParam(defaultValue = "false") boolean policyViolation,
                                                       @RequestParam(defaultValue = "0") int page,
                                                       @RequestParam(defaultValue = "20") int size,
                                                       @RequestParam(defaultValue = "score") String sort,
                                                       @RequestParam(defaultValue = "desc") String dir) {
        LocalDate fromDate = date(from, "from"), toDate = date(to, "to");
        if (fromDate != null && toDate != null && toDate.isBefore(fromDate)) throw ApiException.badRequest("'to' must not be before 'from'.");
        RiskAnalyticsService.Filter f = new RiskAnalyticsService.Filter(q, level(level), status(status), fromDate, toDate,
                mismatch, policyViolation);
        return ResponseEntity.ok(analytics.list(f, page, size, sort, dir));
    }

    @GetMapping("/refunds/{id}")
    @PreAuthorize("hasAuthority('RISK_ANALYTICS_VIEW')")
    @Operation(summary = "Risk detail of one refund: score, level, factors with points and evidence, description, next action, history")
    public ResponseEntity<Map<String, Object>> refund(@PathVariable Long id) {
        return ResponseEntity.ok(analytics.detail(id));
    }

    @PostMapping("/assess-unassessed")
    @PreAuthorize("hasAuthority('RISK_ANALYTICS_VIEW') and hasAuthority('RISK_ASSESS')")
    @Operation(summary = "Assess refunds that have never been assessed (max 500 per call). Assessment only; never holds or releases.")
    public ResponseEntity<Map<String, Object>> assessUnassessed() {
        return ResponseEntity.ok(analytics.assessUnassessed(CurrentUser.require()));
    }

    private static String level(String v) {
        if (v == null || v.isBlank()) return null;
        String s = v.trim().toUpperCase(Locale.ROOT);
        if ("UNASSESSED".equals(s)) return s;
        try {
            return RiskLevel.valueOf(s).name();
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("level must be one of " + Arrays.toString(RiskLevel.values()) + " or UNASSESSED.");
        }
    }

    private static RefundStatus status(String v) {
        if (v == null || v.isBlank()) return null;
        try {
            return RefundStatus.valueOf(v.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("status must be one of " + Arrays.toString(RefundStatus.values()) + ".");
        }
    }

    private static LocalDate date(String v, String name) {
        if (v == null || v.isBlank()) return null;
        try {
            return LocalDate.parse(v.trim());
        } catch (DateTimeParseException e) {
            throw ApiException.badRequest("'" + name + "' must be a date in yyyy-MM-dd format.");
        }
    }
}
