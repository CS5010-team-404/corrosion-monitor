package edu.neu.ecp.ingestion;

import java.security.SecureRandom;
import java.util.Base64;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.encrypt.Encryptors;
import org.springframework.security.crypto.encrypt.TextEncryptor;
import org.springframework.stereotype.Component;

/**
 * Device HMAC keys must be readable by the server (to verify signatures), so they are encrypted at rest with
 * AES-256-GCM (Spring Security {@code Encryptors.delux}) using ECP_SECRET_KEY / ECP_SECRET_SALT from the environment.
 */
@Component
public class SensorSecretCipher {

    private static final SecureRandom RANDOM = new SecureRandom();
    private final TextEncryptor encryptor;

    public SensorSecretCipher(@Value("${ecp.crypto.secret-key}") String password,
                              @Value("${ecp.crypto.salt}") String hexSalt) {
        this.encryptor = Encryptors.delux(password, hexSalt);
    }

    public String encrypt(String plain) {
        return encryptor.encrypt(plain);
    }

    public String decrypt(String encrypted) {
        return encryptor.decrypt(encrypted);
    }

    /** A new random device key (256 bits, URL-safe). Show it to the admin once; store only the encrypted form. */
    public static String newSecret() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
