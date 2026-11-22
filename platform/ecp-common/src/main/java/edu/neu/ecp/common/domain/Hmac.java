package edu.neu.ecp.common.domain;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Device request signing, shared by the simulator (and a future Raspberry Pi client) and the ingestion API.
 *
 * <pre>
 *   X-Device-Id: SIM-001
 *   X-Timestamp: 1761840000            (epoch seconds, must be within ±300 s of server time)
 *   X-Nonce: 3f9c0a6e51d2b7a8c4e1f0d9   (optional, 16-64 of [A-Za-z0-9_-], new for every request)
 *   X-Signature: hex( HMAC-SHA256( deviceSecret, timestamp + "." + nonce + "." + rawBody ) )   with a nonce
 *                hex( HMAC-SHA256( deviceSecret, timestamp + "." + rawBody ) )                 legacy, without one
 * </pre>
 * A nonce is covered by the signature, so it cannot be swapped; the ingestion API remembers each (device, nonce) for
 * the timestamp window and refuses it a second time. Requests without a nonce are protected by remembering their
 * signature instead (see the ingestion service).
 *
 * Signing the raw body bytes (not re-serialised JSON) avoids number-formatting mismatches between client and server.
 */
public final class Hmac {

    private static final String ALGORITHM = "HmacSHA256";
    private static final HexFormat HEX = HexFormat.of();
    private static final java.security.SecureRandom RANDOM = new java.security.SecureRandom();

    private Hmac() {
    }

    public static String hmacSha256Hex(String secret, String message) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM));
            return HEX.formatHex(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", e);
        }
    }

    public static String signRequest(String secret, long epochSeconds, String rawBody) {
        return hmacSha256Hex(secret, epochSeconds + "." + rawBody);
    }

    /** Signature with a nonce (protocol v2): {@code timestamp + "." + nonce + "." + rawBody}. */
    public static String signRequest(String secret, long epochSeconds, String nonce, String rawBody) {
        return nonce == null ? signRequest(secret, epochSeconds, rawBody)
                : hmacSha256Hex(secret, epochSeconds + "." + nonce + "." + rawBody);
    }

    /** Constant-time comparison so attackers cannot learn the signature byte by byte. */
    public static boolean verifyRequest(String secret, long epochSeconds, String rawBody, String providedHex) {
        return verifyRequest(secret, epochSeconds, null, rawBody, providedHex);
    }

    /** Verifies a v2 signature when {@code nonce} is given, the legacy one when it is null. Constant time. */
    public static boolean verifyRequest(String secret, long epochSeconds, String nonce, String rawBody, String providedHex) {
        if (providedHex == null) {
            return false;
        }
        byte[] expected = signRequest(secret, epochSeconds, nonce, rawBody).getBytes(StandardCharsets.US_ASCII);
        byte[] provided = providedHex.trim().toLowerCase().getBytes(StandardCharsets.US_ASCII);
        return MessageDigest.isEqual(expected, provided);
    }

    /** A fresh random nonce for one request: 128 bits, hex. */
    public static String newNonce() {
        byte[] b = new byte[16];
        RANDOM.nextBytes(b);
        return HEX.formatHex(b);
    }

    public static String sha256Hex(String message) {
        try {
            return HEX.formatHex(MessageDigest.getInstance("SHA-256").digest(message.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
