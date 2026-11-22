package edu.neu.ecp.common.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class HmacTest {

    @Test
    void matchesTheRfcStyleKnownVector() {
        assertEquals("f7bc83f430538424b13298e6aa6fb143ef4d59a14946175997479dbc2d1a3cd8",
                Hmac.hmacSha256Hex("key", "The quick brown fox jumps over the lazy dog"));
    }

    @Test
    void verifiesItsOwnSignaturesAndRejectsTampering() {
        String body = "{\"readings\":[{\"seq\":1,\"temperatureC\":22.4,\"humidityPct\":48.1}]}";
        String sig = Hmac.signRequest("s3cret", 1761840000L, body);
        assertTrue(Hmac.verifyRequest("s3cret", 1761840000L, body, sig));
        assertTrue(Hmac.verifyRequest("s3cret", 1761840000L, body, sig.toUpperCase()));
        assertFalse(Hmac.verifyRequest("s3cret", 1761840001L, body, sig), "timestamp is part of the signature");
        assertFalse(Hmac.verifyRequest("s3cret", 1761840000L, body.replace("22.4", "12.4"), sig));
        assertFalse(Hmac.verifyRequest("other", 1761840000L, body, sig));
        assertFalse(Hmac.verifyRequest("s3cret", 1761840000L, body, null));
    }

    @Test
    void theNonceIsCoveredByTheSignatureAndLegacyRequestsStillVerify() {
        String body = "{\"readings\":[]}";
        String v2 = Hmac.signRequest("s3cret", 1761840000L, "abcdefabcdefabcd", body);
        assertEquals(Hmac.hmacSha256Hex("s3cret", "1761840000.abcdefabcdefabcd." + body), v2);
        assertTrue(Hmac.verifyRequest("s3cret", 1761840000L, "abcdefabcdefabcd", body, v2));
        assertFalse(Hmac.verifyRequest("s3cret", 1761840000L, "0000000000000000", body, v2), "nonce is signed");
        assertFalse(Hmac.verifyRequest("s3cret", 1761840000L, null, body, v2), "a v2 signature is not a legacy one");
        String v1 = Hmac.signRequest("s3cret", 1761840000L, body);
        assertEquals(v1, Hmac.signRequest("s3cret", 1761840000L, null, body));
        assertTrue(Hmac.verifyRequest("s3cret", 1761840000L, null, body, v1));
    }

    @Test
    void noncesAreFreshAndWellFormed() {
        String a = Hmac.newNonce();
        assertEquals(32, a.length());
        assertTrue(a.matches("[0-9a-f]{32}"));
        assertFalse(a.equals(Hmac.newNonce()));
    }
}
