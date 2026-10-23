package com.refundguard.controller;

import com.refundguard.ai.AiRootCauseService;
import com.refundguard.exception.ApiException;
import com.refundguard.review.RefundReviewService;
import com.refundguard.risk.RiskScoringService;
import com.refundguard.security.CurrentUser;
import com.refundguard.model.*;
import com.refundguard.sla.SlaService;
import com.refundguard.repository.*;
import com.refundguard.service.ReconciliationService;
import com.refundguard.simulator.SimulatorService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;

@RestController
@RequestMapping("/api")
@Tag(name = "RefundGuard Core API", description = "Endpoints for monitoring, reconciliation, and simulation")
public class ApiController {

    private final OrderRepository orderRepository;
    private final RefundRepository refundRepository;
    private final RefundEventRepository refundEventRepository;
    private final CaseRepository caseRepository;
    private final ReconciliationService reconciliationService;
    private final SimulatorService simulatorService;
    private final SlaService slaService;
    private final AiRootCauseService aiRootCauseService;
    private final RiskScoringService riskService;
    private final RefundReviewService reviewService;
    private final boolean simulatorEnabled;

    public ApiController(OrderRepository orderRepository, RefundRepository refundRepository,
                         RefundEventRepository refundEventRepository, CaseRepository caseRepository,
                         ReconciliationService reconciliationService, SimulatorService simulatorService,
                         SlaService slaService, AiRootCauseService aiRootCauseService,
                         RiskScoringService riskService, RefundReviewService reviewService,
                         @Value("${refundguard.simulator.enabled:true}") boolean simulatorEnabled) {
        this.orderRepository = orderRepository;
        this.refundRepository = refundRepository;
        this.refundEventRepository = refundEventRepository;
        this.caseRepository = caseRepository;
        this.reconciliationService = reconciliationService;
        this.simulatorService = simulatorService;
        this.slaService = slaService;
        this.aiRootCauseService = aiRootCauseService;
        this.riskService = riskService;
        this.reviewService = reviewService;
        this.simulatorEnabled = simulatorEnabled;
    }

    /** Simulator endpoints can be switched off entirely for production-like environments. */
    private void requireSimulator() {
        if (!simulatorEnabled) throw ApiException.notFound("Simulator endpoints are disabled in this environment.");
    }

    @GetMapping("/dashboard/summary")
    @Operation(summary = "Get high-level summary metrics")
    @PreAuthorize("hasAuthority('REFUND_VIEW')")
    public ResponseEntity<Map<String, Object>> getSummary() {
        long totalRefunds = refundRepository.count();
        long matched = refundRepository.findByDerivedStatus(RefundStatus.SETTLEMENT_MATCHED).size();
        long delayed = refundRepository.findByDerivedStatus(RefundStatus.POTENTIAL_DELAY).size();
        long mismatched = refundRepository.findByDerivedStatus(RefundStatus.AMOUNT_MISMATCH).size();
        long openCases = caseRepository.findByStatus("OPEN").size();

        List<Refund> all = refundRepository.findAll();
        long breached = all.stream().filter(r -> "BREACHED".equals(r.getSlaState())).count();
        long dueSoon = all.stream().filter(r -> "DUE_SOON".equals(r.getSlaState())).count();
        long settledLate = all.stream().filter(r -> "MET_LATE".equals(r.getSlaState())).count();
        long settledOnTime = all.stream().filter(r -> "MET".equals(r.getSlaState())).count();
        BigDecimal exposure = all.stream().map(Refund::getCompensationDue).reduce(BigDecimal.ZERO, BigDecimal::add);
        long settledTotal = settledLate + settledOnTime;

        Map<String, Object> map = new HashMap<>();
        map.put("totalRefunds", totalRefunds);
        map.put("settlementMatched", matched);
        map.put("potentialDelay", delayed);
        map.put("amountMismatch", mismatched);
        map.put("openCases", openCases);
        map.put("slaBreached", breached);
        map.put("slaDueSoon", dueSoon);
        map.put("settledLate", settledLate);
        map.put("tatCompliancePct", settledTotal == 0 ? null : Math.round(settledOnTime * 1000.0 / settledTotal) / 10.0);
        map.put("compensationExposure", exposure);
        map.put("aiMode", aiRootCauseService.status().get("mode"));
        map.put("pendingReviews", reviewService.pendingCount());
        map.put("onHold", all.stream().filter(r -> r.getDerivedStatus() == RefundStatus.ON_HOLD).count());
        Map<String, Long> dist = new LinkedHashMap<>();
        for (String lvl : List.of("LOW", "MEDIUM", "HIGH", "CRITICAL")) dist.put(lvl, 0L);
        List<Long> ids = all.stream().map(Refund::getId).toList();
        riskService.latestFor(ids).values().forEach(a -> dist.merge(a.getLevel().name(), 1L, Long::sum));
        map.put("riskDistribution", dist);
        map.put("riskMethod", "RULE_BASED");
        return ResponseEntity.ok(map);
    }

    @GetMapping("/sla/policy")
    @Operation(summary = "RBI TAT policy per payment method (deadline and compensation per day)")
    @PreAuthorize("hasAuthority('REFUND_VIEW')")
    public ResponseEntity<Map<String, Map<String, Object>>> slaPolicy() {
        return ResponseEntity.ok(slaService.describePolicy());
    }

    @PostMapping("/sla/check")
    @Operation(summary = "Run the RBI TAT SLA check now (also runs automatically on a schedule)")
    @PreAuthorize("hasAuthority('SLA_RUN')")
    public ResponseEntity<Map<String, Object>> slaCheck() {
        return ResponseEntity.ok(slaService.evaluateAll());
    }

    @GetMapping("/ai/status")
    @Operation(summary = "Whether Claude AI analysis is configured")
    @PreAuthorize("hasAuthority('REFUND_VIEW')")
    public ResponseEntity<Map<String, Object>> aiStatus() {
        return ResponseEntity.ok(aiRootCauseService.status());
    }

    @PostMapping("/cases/{id}/analyze")
    @Operation(summary = "Run AI root-cause analysis on a case (Claude, falls back to rules)")
    @PreAuthorize("hasAuthority('CASE_INVESTIGATE')")
    public ResponseEntity<Map<String, Object>> analyzeCase(@PathVariable Long id) {
        return ResponseEntity.ok(aiRootCauseService.analyzeCase(id));
    }

    @GetMapping("/orders")
    @Operation(summary = "List all customer orders")
    @PreAuthorize("hasAuthority('ORDER_VIEW')")
    public ResponseEntity<List<Order>> getOrders() {
        return ResponseEntity.ok(orderRepository.findAll());
    }

    @PostMapping("/orders/create-and-pay")
    @Operation(summary = "Create and pay a new order (customer checkout)")
    @PreAuthorize("hasAuthority('ORDER_CREATE')")
    public ResponseEntity<Order> createAndPay(@RequestBody Map<String, Object> req) {
        String name = String.valueOf(req.getOrDefault("customerName", "Aditi Rao"));
        String email = String.valueOf(req.getOrDefault("customerEmail", "aditi@example.com"));
        String method = String.valueOf(req.getOrDefault("paymentMethod", "UPI"));
        BigDecimal amount;
        try {
            amount = new BigDecimal(String.valueOf(req.getOrDefault("amount", 1999.00)));
        } catch (NumberFormatException e) {
            throw ApiException.badRequest("amount must be a number.");
        }
        if (amount.signum() <= 0 || amount.compareTo(new BigDecimal("10000000")) > 0) {
            throw ApiException.badRequest("amount must be greater than 0 and at most 10,000,000.");
        }
        if (name.length() > 100 || email.length() > 150 || method.length() > 30) {
            throw ApiException.badRequest("customerName / customerEmail / paymentMethod is too long.");
        }
        return ResponseEntity.ok(simulatorService.createAndPayOrder(name, email, amount, method));
    }

    @GetMapping("/refunds")
    @Operation(summary = "List all refund records")
    @PreAuthorize("hasAuthority('REFUND_VIEW')")
    public ResponseEntity<List<Refund>> getRefunds() {
        return ResponseEntity.ok(refundRepository.findAll());
    }

    @GetMapping("/refunds/{id}/timeline")
    @Operation(summary = "Get chronological event timeline for a refund")
    @PreAuthorize("hasAuthority('REFUND_VIEW')")
    public ResponseEntity<List<RefundEvent>> getTimeline(@PathVariable Long id) {
        return ResponseEntity.ok(refundEventRepository.findByRefundIdOrderByEventTimeAsc(id));
    }

    @PostMapping(value = "/settlements/upload", consumes = "multipart/form-data")
    @Operation(summary = "Upload Bank Settlement CSV and run reconciliation")
    @PreAuthorize("hasAuthority('SETTLEMENT_UPLOAD')")
    public ResponseEntity<Map<String, Object>> uploadSettlement(@RequestParam("file") MultipartFile file) throws Exception {
        return ResponseEntity.ok(reconciliationService.uploadSettlementCsv(file));
    }

    @PostMapping("/reconciliation/run")
    @Operation(summary = "Manually trigger reconciliation check")
    @PreAuthorize("hasAuthority('RECONCILIATION_RUN')")
    public ResponseEntity<Map<String, Object>> triggerRecon() {
        return ResponseEntity.ok(reconciliationService.runReconciliation(null));
    }

    @GetMapping("/cases")
    @Operation(summary = "Get all discrepancy investigation cases")
    @PreAuthorize("hasAuthority('CASE_VIEW')")
    public ResponseEntity<List<Case>> getCases() {
        return ResponseEntity.ok(caseRepository.findAll());
    }

    @PostMapping("/cases/{id}/resolve")
    @Operation(summary = "Resolve an investigation case with operator notes")
    @PreAuthorize("hasAuthority('CASE_RESOLVE')")
    public ResponseEntity<Case> resolveCase(@PathVariable Long id, @RequestBody Map<String, String> body) {
        Case c = caseRepository.findById(id).orElseThrow(() -> ApiException.notFound("Case " + id + " not found."));
        if (RefundReviewService.CASE_TYPE.equals(c.getIssueType())) {
            throw ApiException.conflict("High-risk review cases can only be closed by approving or rejecting the held refund.");
        }
        String notes = body.getOrDefault("notes", "Resolved by operator after banking confirmation");
        c.setStatus("RESOLVED");
        c.setResolutionNotes(notes + " [resolved by " + CurrentUser.require().username() + "]");
        c.setResolvedAt(LocalDateTime.now());
        return ResponseEntity.ok(caseRepository.save(c));
    }

    @PostMapping("/simulator/reset")
    @Operation(summary = "Reset DB and seed orders for clean demo")
    @PreAuthorize("hasAuthority('SIMULATOR_RESET')")
    public ResponseEntity<Map<String, Object>> resetData() {
        requireSimulator();
        return ResponseEntity.ok(simulatorService.resetAndSeedData());
    }

    @PostMapping("/simulator/cancel-order")
    @Operation(summary = "Simulate customer cancellation and Razorpay webhooks")
    @PreAuthorize("hasAuthority('SIMULATOR_USE')")
    public ResponseEntity<Map<String, Object>> cancelOrder(@RequestParam String orderId) {
        requireSimulator();
        return ResponseEntity.ok(simulatorService.simulateOrderCancellation(orderId));
    }

    @PostMapping("/simulator/duplicate-test")
    @Operation(summary = "Test idempotency with duplicate webhook")
    @PreAuthorize("hasAuthority('SIMULATOR_USE')")
    public ResponseEntity<Map<String, Object>> duplicateTest(@RequestParam String orderId) {
        requireSimulator();
        return ResponseEntity.ok(simulatorService.simulateDuplicateWebhook(orderId));
    }

    @PostMapping("/simulator/check-overdue")
    @Operation(summary = "Check for refunds past their RBI TAT deadline (time- and payment-method-aware)")
    @PreAuthorize("hasAuthority('SIMULATOR_USE')")
    public ResponseEntity<Map<String, Object>> checkOverdue() {
        requireSimulator();
        Map<String, Object> result = reconciliationService.checkOverdueRefunds();
        result.put("overdueCount", result.get("breached"));
        return ResponseEntity.ok(result);
    }

    @PostMapping("/simulator/fail-refund")
    @Operation(summary = "Simulate the gateway reporting a refund as FAILED")
    @PreAuthorize("hasAuthority('SIMULATOR_USE')")
    public ResponseEntity<Map<String, Object>> failRefund(@RequestParam String orderId) {
        requireSimulator();
        return ResponseEntity.ok(simulatorService.simulateRefundFailure(orderId));
    }

    @PostMapping("/simulator/age-refund")
    @Operation(summary = "Demo time-travel: move a refund N days into the past to demonstrate SLA breaches")
    @PreAuthorize("hasAuthority('SIMULATOR_USE')")
    public ResponseEntity<Map<String, Object>> ageRefund(@RequestParam String orderId, @RequestParam(defaultValue = "3") int days) {
        requireSimulator();
        return ResponseEntity.ok(simulatorService.ageRefund(orderId, days));
    }

    @PostMapping("/simulator/signed-webhook")
    @Operation(summary = "Send a genuine and a tampered signed webhook through signature verification")
    @PreAuthorize("hasAuthority('SIMULATOR_USE')")
    public ResponseEntity<Map<String, Object>> signedWebhook(@RequestParam String orderId) {
        requireSimulator();
        return ResponseEntity.ok(simulatorService.simulateSignedWebhook(orderId));
    }
}
