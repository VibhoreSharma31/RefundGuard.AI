package com.refundguard.webhook;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;

/**
 * Razorpay signs every webhook with HMAC-SHA256(rawBody, webhookSecret) and sends
 * the hex digest in the {@code X-Razorpay-Signature} header. The signature must be
 * computed over the exact raw bytes received - never over re-serialised JSON.
 */
public final class RazorpaySignatureVerifier {

    private RazorpaySignatureVerifier() {}

    public static String sign(String rawBody, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(rawBody.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("Unable to compute HMAC-SHA256", e);
        }
    }

    /** Constant-time comparison so the check does not leak how many characters matched. */
    public static boolean isValid(String rawBody, String signatureHeader, String secret) {
        if (rawBody == null || signatureHeader == null || secret == null || secret.isBlank()) return false;
        byte[] expected = sign(rawBody, secret).getBytes(StandardCharsets.US_ASCII);
        byte[] provided = signatureHeader.trim().toLowerCase(Locale.ROOT).getBytes(StandardCharsets.US_ASCII);
        return MessageDigest.isEqual(expected, provided);
    }
}
