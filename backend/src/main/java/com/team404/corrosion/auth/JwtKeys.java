package com.team404.corrosion.auth;

import java.nio.charset.StandardCharsets;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

/** The HS256 key shared with the platform. Same rule as the platform's SecurityConfig: at least 32 bytes. */
public final class JwtKeys {

    public static final int MIN_BYTES = 32;

    private JwtKeys() {
    }

    public static SecretKey fromSecret(String secret) {
        byte[] bytes = secret == null ? new byte[0] : secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < MIN_BYTES) {
            throw new IllegalStateException(
                    "ECP_JWT_SECRET must be set and be at least 32 bytes; it must equal the platform's value");
        }
        return new SecretKeySpec(bytes, "HmacSHA256");
    }
}
