package com.refundguard.webhook;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RazorpaySignatureVerifierTest {

    private static final String SECRET = "test_secret";
    private static final String BODY = "{\"event\":\"refund.processed\",\"payload\":{}}";

    @Test
    void knownHmacVector() {
        // HMAC-SHA256("The quick brown fox jumps over the lazy dog", key="key")
        assertThat(RazorpaySignatureVerifier.sign("The quick brown fox jumps over the lazy dog", "key"))
                .isEqualTo("f7bc83f430538424b13298e6aa6fb143ef4d59a14946175997479dbc2d1a3cd8");
    }

    @Test
    void acceptsGenuineSignature() {
        String sig = RazorpaySignatureVerifier.sign(BODY, SECRET);
        assertThat(RazorpaySignatureVerifier.isValid(BODY, sig, SECRET)).isTrue();
        assertThat(RazorpaySignatureVerifier.isValid(BODY, sig.toUpperCase(), SECRET)).isTrue();
    }

    @Test
    void rejectsTamperedBodyWrongSecretAndMissingValues() {
        String sig = RazorpaySignatureVerifier.sign(BODY, SECRET);
        assertThat(RazorpaySignatureVerifier.isValid(BODY.replace("processed", "failed"), sig, SECRET)).isFalse();
        assertThat(RazorpaySignatureVerifier.isValid(BODY, sig, "other_secret")).isFalse();
        assertThat(RazorpaySignatureVerifier.isValid(BODY, null, SECRET)).isFalse();
        assertThat(RazorpaySignatureVerifier.isValid(BODY, sig, "")).isFalse();
    }
}
