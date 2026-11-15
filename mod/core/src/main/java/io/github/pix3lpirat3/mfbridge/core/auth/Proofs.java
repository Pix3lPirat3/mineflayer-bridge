package io.github.pix3lpirat3.mfbridge.core.auth;

import io.github.pix3lpirat3.mfbridge.api.thread.AnyThread;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Challenge-response proofs. */
@AnyThread
public final class Proofs {
    private Proofs() {}

    /**
     * Computes {@code HMAC-SHA256(key, nonce || u64be(connectionId) || utf8(deviceId))}.
     *
     * @param key HMAC key: the decoded local token or a device secret
     * @param nonce the 32-byte challenge nonce
     * @param connectionId the challenge connection id
     * @param deviceId the device id from {@code auth.response}
     * @return the 32-byte proof
     */
    public static byte[] proof(byte[] key, byte[] nonce, long connectionId, String deviceId) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            mac.update(nonce);
            mac.update(ByteBuffer.allocate(Long.BYTES).putLong(0, connectionId));
            mac.update(deviceId.getBytes(StandardCharsets.UTF_8));
            return mac.doFinal();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }

    /** Verifies a proof in constant time. */
    public static boolean verify(byte[] key, byte[] nonce, long connectionId, String deviceId, byte[] proof) {
        return MessageDigest.isEqual(proof(key, nonce, connectionId, deviceId), proof);
    }
}
