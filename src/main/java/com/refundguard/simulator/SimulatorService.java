package com.refundguard.simulator;

import com.refundguard.model.*;
import com.refundguard.repository.*;
import com.refundguard.service.IngestionService;
import com.refundguard.sla.SlaService;
import com.refundguard.webhook.RazorpayWebhookService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;

@Service
public class SimulatorService {

    private static final Logger log = LoggerFactory.getLogger(SimulatorService.class);

    private final OrderRepository orderRepository;
    private final IngestionService ingestionService;
    private final RefundRepository refundRepository;
    private final CaseRepository caseRepository;
    private final SettlementRowRepository settlementRowRepository;
    private final RefundEventRepository refundEventRepository;
    private final SlaService slaService;
    private final RazorpayWebhookService webhookService;
    private final RefundReviewRepository reviewRepository;
    private final RiskAssessmentRepository riskAssessmentRepository;
    private final RiskFactorRepository riskFactorRepository;

    public SimulatorService(OrderRepository orderRepository, IngestionService ingestionService,
                            RefundRepository refundRepository, CaseRepository caseRepository,
                            SettlementRowRepository settlementRowRepository, RefundEventRepository refundEventRepository,
                            SlaService slaService, RazorpayWebhookService webhookService,
                            RefundReviewRepository reviewRepository, RiskAssessmentRepository riskAssessmentRepository,
                            RiskFactorRepository riskFactorRepository) {
        this.orderRepository = orderRepository;
        this.ingestionService = ingestionService;
        this.refundRepository = refundRepository;
        this.caseRepository = caseRepository;
        this.settlementRowRepository = settlementRowRepository;
        this.refundEventRepository = refundEventRepository;
        this.slaService = slaService;
        this.webhookService = webhookService;
        this.reviewRepository = reviewRepository;
        this.riskAssessmentRepository = riskAssessmentRepository;
        this.riskFactorRepository = riskFactorRepository;
    }

    @Transactional
    public Order createAndPayOrder(String customerName, String customerEmail, BigDecimal amount, String paymentMethod) {
        String orderId = "ORD-" + (1000 + new Random().nextInt(9000));
        Order order = new Order();
        order.setExternalOrderId(orderId);
        order.setCustomerName(customerName != null && !customerName.isBlank() ? customerName : "Aditi Rao");
        order.setCustomerEmail(customerEmail != null && !customerEmail.isBlank() ? customerEmail : "aditi@example.com");
        order.setAmount(amount != null ? amount : new BigDecimal("1999.00"));
        order.setCurrency("INR");
        order.setStatus("PAID");
        order.setPaymentMethod(paymentMethod != null && !paymentMethod.isBlank() ? paymentMethod : "UPI");
        order.setCreatedAt(LocalDateTime.now());
        return orderRepository.save(order);
    }

    @Transactional
    public Map<String, Object> resetAndSeedData() {
        caseRepository.deleteAll();
        reviewRepository.deleteAll();          // review history belongs to the refunds being wiped
        riskFactorRepository.deleteAllInBatch(); // child rows of the assessments below
        riskAssessmentRepository.deleteAll();  // (user accounts in rg_users are intentionally NOT touched)
        settlementRowRepository.deleteAll();
        refundEventRepository.deleteAll();
        refundRepository.deleteAll();
        orderRepository.deleteAll();

        List<Order> orders = new ArrayList<>();

        Order o1 = new Order();
        o1.setExternalOrderId("ORD-9001");
        o1.setCustomerName("Rahul Sharma");
        o1.setCustomerEmail("rahul.sharma@example.com");
        o1.setAmount(new BigDecimal("5000.00"));
        o1.setCurrency("INR");
        o1.setStatus("PAID");
        o1.setPaymentMethod("UPI");
        o1.setCreatedAt(LocalDateTime.now().minusDays(5));
        orders.add(o1);

        Order o2 = new Order();
        o2.setExternalOrderId("ORD-9002");
        o2.setCustomerName("Pooja Patel");
        o2.setCustomerEmail("pooja.patel@example.com");
        o2.setAmount(new BigDecimal("2499.00"));
        o2.setCurrency("INR");
        o2.setStatus("PAID");
        o2.setPaymentMethod("CARD");
        o2.setCreatedAt(LocalDateTime.now().minusDays(3));
        orders.add(o2);

        Order o3 = new Order();
        o3.setExternalOrderId("ORD-9003");
        o3.setCustomerName("Aman Verma");
        o3.setCustomerEmail("aman.verma@example.com");
        o3.setAmount(new BigDecimal("1200.00"));
        o3.setCurrency("INR");
        o3.setStatus("PAID");
        o3.setPaymentMethod("UPI");
        o3.setCreatedAt(LocalDateTime.now().minusDays(2));
        orders.add(o3);

        orderRepository.saveAll(orders);

        Map<String, Object> resp = new HashMap<>();
        resp.put("message", "Database reset and seeded with 3 demo orders.");
        resp.put("orders", orders);
        return resp;
    }

    @Transactional
    public Map<String, Object> simulateOrderCancellation(String orderId) {
        Order order = orderRepository.findByExternalOrderId(orderId)
                .orElseThrow(() -> new RuntimeException("Order not found: " + orderId));

        order.setStatus("CANCELLED");
        orderRepository.save(order);

        String paymentId = "pay_" + orderId.replace("-", "").toLowerCase();
        String refundId = "rfnd_" + orderId.replace("-", "").toLowerCase();

        ingestionService.ingestEvent(
                "MERCHANT_STORE",
                "evt_merchant_cancel_" + System.currentTimeMillis(),
                "order.cancelled",
                order.getExternalOrderId(),
                paymentId,
                refundId,
                order.getAmount(),
                order.getCurrency(),
                order.getPaymentMethod(),
                "{\"orderId\":\"" + orderId + "\",\"customer\":\"" + order.getCustomerName() + "\",\"action\":\"CUSTOMER_REQUESTED_CANCELLATION\"}"
        );

        ingestionService.ingestEvent(
                "RAZORPAY",
                "evt_rzp_create_" + System.currentTimeMillis(),
                "refund.created",
                order.getExternalOrderId(),
                paymentId,
                refundId,
                order.getAmount(),
                order.getCurrency(),
                order.getPaymentMethod(),
                "{\"status\":\"pending\",\"speed\":\"optimum\"}"
        );

        ingestionService.ingestEvent(
                "RAZORPAY",
                "evt_rzp_proc_" + System.currentTimeMillis(),
                "refund.processed",
                order.getExternalOrderId(),
                paymentId,
                refundId,
                order.getAmount(),
                order.getCurrency(),
                order.getPaymentMethod(),
                "{\"status\":\"processed\",\"acquirerReference\":\"UTR" + System.currentTimeMillis() + "\"}"
        );

        Map<String, Object> resp = new HashMap<>();
        resp.put("status", "SUCCESS");
        resp.put("orderId", orderId);
        resp.put("refundId", refundId);
        resp.put("message", "Order " + orderId + " cancelled and Razorpay webhooks ingested seamlessly.");
        return resp;
    }

    /** Simulates the gateway reporting that a refund could not be credited (e.g. closed account). */
    @Transactional
    public Map<String, Object> simulateRefundFailure(String orderId) {
        Order order = orderRepository.findByExternalOrderId(orderId)
                .orElseThrow(() -> new RuntimeException("Order not found: " + orderId));
        order.setStatus("CANCELLED");
        orderRepository.save(order);

        String paymentId = "pay_" + orderId.replace("-", "").toLowerCase();
        String refundId = "rfnd_" + orderId.replace("-", "").toLowerCase();
        ingestionService.ingestEvent("RAZORPAY", "evt_rzp_create_" + System.currentTimeMillis(), "refund.created",
                orderId, paymentId, refundId, order.getAmount(), order.getCurrency(), order.getPaymentMethod(),
                "{\"status\":\"pending\"}");
        ingestionService.ingestEvent("RAZORPAY", "evt_rzp_fail_" + System.currentTimeMillis(), "refund.failed",
                orderId, paymentId, refundId, order.getAmount(), order.getCurrency(), order.getPaymentMethod(),
                "{\"status\":\"failed\",\"error_reason\":\"BENEFICIARY_ACCOUNT_CLOSED\"}");

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("orderId", orderId);
        resp.put("refundId", refundId);
        resp.put("message", "Refund for " + orderId + " reported FAILED by gateway. A CRITICAL REFUND_FAILED case was opened.");
        return resp;
    }

    /**
     * Demo "time travel": moves a refund's initiation date back by N days so the
     * SLA engine can be demonstrated without waiting for real days to pass.
     */
    @Transactional
    public Map<String, Object> ageRefund(String orderId, int days) {
        Refund refund = refundRepository.findByOrderId(orderId)
                .orElseThrow(() -> new RuntimeException("No refund for order: " + orderId));
        refund.setRequestedAt(refund.getRequestedAt().minusDays(days));
        if (refund.getSlaDueAt() != null) refund.setSlaDueAt(refund.getSlaDueAt().minusDays(days));
        refundRepository.save(refund);

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("orderId", orderId);
        resp.put("message", "Refund for " + orderId + " moved " + days + " day(s) into the past. New deadline: " + refund.getSlaDueAt() + ".");
        resp.put("sla", slaService.evaluateAll());
        return resp;
    }

    /** Sends a correctly signed and a tampered webhook through the real verification path. */
    public Map<String, Object> simulateSignedWebhook(String orderId) {
        Order order = orderRepository.findByExternalOrderId(orderId)
                .orElseThrow(() -> new RuntimeException("Order not found: " + orderId));
        Map<String, Object> resp = new LinkedHashMap<>();
        if (!webhookService.isConfigured()) {
            resp.put("message", "RAZORPAY_WEBHOOK_SECRET is not set, so every webhook is rejected (fail-closed).");
            resp.put("configured", false);
            return resp;
        }
        long paise = order.getAmount().movePointRight(2).longValue();
        String refundId = "rfnd_" + orderId.replace("-", "").toLowerCase();
        String paymentId = "pay_" + orderId.replace("-", "").toLowerCase();
        String body = "{\"entity\":\"event\",\"event\":\"refund.processed\",\"payload\":{"
                + "\"refund\":{\"entity\":{\"id\":\"" + refundId + "\",\"payment_id\":\"" + paymentId + "\",\"amount\":" + paise
                + ",\"currency\":\"INR\",\"status\":\"processed\",\"notes\":{\"order_id\":\"" + orderId + "\"}}},"
                + "\"payment\":{\"entity\":{\"id\":\"" + paymentId + "\",\"method\":\"" + order.getPaymentMethod().toLowerCase() + "\"}}}}";
        String signature = webhookService.signForTest(body);
        String eventId = "evt_signed_" + System.currentTimeMillis();

        String tampered = body.replace("\"amount\":" + paise, "\"amount\":" + (paise / 10));
        RazorpayWebhookService.Result forged = webhookService.handle(tampered, signature, eventId + "_forged");
        RazorpayWebhookService.Result genuine = webhookService.handle(body, signature, eventId);

        resp.put("configured", true);
        resp.put("tamperedPayload", forged.toMap());
        resp.put("genuinePayload", genuine.toMap());
        resp.put("message", "Tampered webhook (amount changed): " + forged.outcome() + ". Genuine signed webhook: " + genuine.outcome() + ".");
        return resp;
    }

    public Map<String, Object> simulateDuplicateWebhook(String orderId) {
        Order order = orderRepository.findByExternalOrderId(orderId)
                .orElseThrow(() -> new RuntimeException("Order not found: " + orderId));

        String paymentId = "pay_" + orderId.replace("-", "").toLowerCase();
        String refundId = "rfnd_" + orderId.replace("-", "").toLowerCase();
        String fixedEventId = "evt_rzp_duplicate_test_101";

        boolean firstTime = ingestionService.ingestEvent(
                "RAZORPAY", fixedEventId, "refund.processed",
                order.getExternalOrderId(), paymentId, refundId,
                order.getAmount(), order.getCurrency(), order.getPaymentMethod(), "{}"
        );

        boolean secondTime = ingestionService.ingestEvent(
                "RAZORPAY", fixedEventId, "refund.processed",
                order.getExternalOrderId(), paymentId, refundId,
                order.getAmount(), order.getCurrency(), order.getPaymentMethod(), "{}"
        );

        Map<String, Object> resp = new HashMap<>();
        resp.put("firstAttemptAccepted", firstTime);
        resp.put("secondAttemptAccepted", secondTime);
        resp.put("idempotencyProtected", !secondTime);
        resp.put("message", "Idempotency test: Second identical webhook safely dropped!");
        return resp;
    }
}
