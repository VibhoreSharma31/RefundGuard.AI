package com.refundguard.webhook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.refundguard.service.IngestionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Real (signed) Razorpay webhook handling. Before this existed, refund events
 * could only be injected through unauthenticated simulator endpoints, so anyone
 * able to reach the API could mark a refund "processed".
 */
@Service
public class RazorpayWebhookService {

    private static final Logger log = LoggerFactory.getLogger(RazorpayWebhookService.class);
    private static final Set<String> SUPPORTED = Set.of("refund.created", "refund.processed", "refund.failed");

    public enum Outcome { ACCEPTED, DUPLICATE, IGNORED, INVALID_SIGNATURE, NOT_CONFIGURED, BAD_PAYLOAD }

    public record Result(Outcome outcome, String message) {
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("outcome", outcome);
            m.put("message", message);
            return m;
        }
    }

    private final IngestionService ingestionService;
    private final ObjectMapper objectMapper;
    private final String webhookSecret;

    public RazorpayWebhookService(IngestionService ingestionService, ObjectMapper objectMapper,
                                  @Value("${refundguard.razorpay.webhook-secret:}") String webhookSecret) {
        this.ingestionService = ingestionService;
        this.objectMapper = objectMapper;
        this.webhookSecret = webhookSecret;
    }

    public boolean isConfigured() {
        return webhookSecret != null && !webhookSecret.isBlank();
    }

    /** Used only by the simulator to produce a correctly signed test payload. */
    public String signForTest(String rawBody) {
        return RazorpaySignatureVerifier.sign(rawBody, webhookSecret);
    }

    public Result handle(String rawBody, String signature, String eventIdHeader) {
        if (!isConfigured()) {
            // Fail closed: without a secret we cannot tell real webhooks from forged ones.
            return new Result(Outcome.NOT_CONFIGURED, "Webhook secret not configured (set RAZORPAY_WEBHOOK_SECRET). Event rejected.");
        }
        if (!RazorpaySignatureVerifier.isValid(rawBody, signature, webhookSecret)) {
            log.warn("Rejected Razorpay webhook with invalid signature (eventId={})", eventIdHeader);
            return new Result(Outcome.INVALID_SIGNATURE, "Signature verification failed. Event rejected.");
        }

        try {
            JsonNode root = objectMapper.readTree(rawBody);
            String event = root.path("event").asText("");
            if (!SUPPORTED.contains(event)) {
                return new Result(Outcome.IGNORED, "Event '" + event + "' is not a refund event; ignored.");
            }
            JsonNode refund = root.path("payload").path("refund").path("entity");
            JsonNode payment = root.path("payload").path("payment").path("entity");

            String refundId = text(refund, "id");
            String paymentId = text(refund, "payment_id");
            if (paymentId == null) paymentId = text(payment, "id");
            String orderId = text(refund.path("notes"), "order_id");
            if (orderId == null) orderId = text(payment.path("notes"), "order_id");
            if (orderId == null) orderId = paymentId;
            if (refundId == null || orderId == null) {
                return new Result(Outcome.BAD_PAYLOAD, "Payload has no refund id / order reference.");
            }

            // Razorpay amounts are in paise.
            BigDecimal amount = refund.has("amount")
                    ? BigDecimal.valueOf(refund.path("amount").asLong()).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP)
                    : null;
            String currency = text(refund, "currency");
            String method = text(payment, "method");
            if (method != null) method = method.toUpperCase(Locale.ROOT);

            // Razorpay sends a unique id per event in X-Razorpay-Event-Id; it is the idempotency key.
            String eventId = eventIdHeader != null && !eventIdHeader.isBlank() ? eventIdHeader : "sig_" + signature;

            boolean accepted = ingestionService.ingestEvent("RAZORPAY", eventId, event, orderId, paymentId, refundId,
                    amount, currency, method, rawBody);
            return accepted
                    ? new Result(Outcome.ACCEPTED, "Verified and ingested " + event + " for " + orderId + ".")
                    : new Result(Outcome.DUPLICATE, "Duplicate event " + eventId + " ignored (idempotent).");
        } catch (Exception e) {
            log.error("Failed to process Razorpay webhook", e);
            return new Result(Outcome.BAD_PAYLOAD, "Could not parse webhook payload: " + e.getMessage());
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.path(field);
        return v.isMissingNode() || v.isNull() || v.asText().isBlank() ? null : v.asText();
    }
}
