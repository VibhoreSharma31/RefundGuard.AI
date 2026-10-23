package com.refundguard.controller;

import com.refundguard.webhook.RazorpayWebhookService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/webhooks")
@Tag(name = "Webhooks", description = "Signed inbound webhooks from payment gateways")
public class WebhookController {

    private final RazorpayWebhookService razorpayWebhookService;

    public WebhookController(RazorpayWebhookService razorpayWebhookService) {
        this.razorpayWebhookService = razorpayWebhookService;
    }

    /**
     * Configure this URL in the Razorpay dashboard (Settings → Webhooks) with the same
     * secret as RAZORPAY_WEBHOOK_SECRET, subscribed to refund.created / refund.processed / refund.failed.
     * The body is taken as a raw String so the signature is checked over the exact bytes received.
     */
    @PostMapping(value = "/razorpay", consumes = "application/json")
    @Operation(summary = "Receive a Razorpay refund webhook (HMAC-SHA256 signature verified)")
    public ResponseEntity<Map<String, Object>> razorpay(@RequestBody String rawBody,
                                                        @RequestHeader(value = "X-Razorpay-Signature", required = false) String signature,
                                                        @RequestHeader(value = "X-Razorpay-Event-Id", required = false) String eventId) {
        RazorpayWebhookService.Result r = razorpayWebhookService.handle(rawBody, signature, eventId);
        HttpStatus status = switch (r.outcome()) {
            case ACCEPTED, DUPLICATE, IGNORED -> HttpStatus.OK;      // 2xx so Razorpay does not retry
            case INVALID_SIGNATURE -> HttpStatus.UNAUTHORIZED;
            case NOT_CONFIGURED -> HttpStatus.SERVICE_UNAVAILABLE;
            case BAD_PAYLOAD -> HttpStatus.BAD_REQUEST;
        };
        return ResponseEntity.status(status).body(r.toMap());
    }
}
