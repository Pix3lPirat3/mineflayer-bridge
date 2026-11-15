package io.github.pix3lpirat3.mfbridge.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.pix3lpirat3.mfbridge.core.auth.Proofs;
import io.github.pix3lpirat3.mfbridge.core.remote.PairingSession;
import io.github.pix3lpirat3.mfbridge.protocol.core.AuthChallenge;
import io.github.pix3lpirat3.mfbridge.protocol.core.AuthMethod;
import io.github.pix3lpirat3.mfbridge.protocol.core.AuthResponse;
import io.github.pix3lpirat3.mfbridge.protocol.core.CoreMessages;
import io.github.pix3lpirat3.mfbridge.protocol.core.PairResult;
import io.github.pix3lpirat3.mfbridge.protocol.core.Welcome;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The mod proves to the plugin that it knows the secret the plugin authenticated with. */
class MutualProofTest {
    static final byte[] NONCE = new byte[32];

    static {
        for (int i = 0; i < NONCE.length; i++) {
            NONCE[i] = (byte) (i * 7 + 1);
        }
    }

    @TempDir
    Path dir;

    @Test
    void welcomeAnswersThePluginsNonceWithTheLocalToken() throws Exception {
        try (Harness h = new Harness(dir); FakePlugin p = FakePlugin.connect(h, "dev")) {
            byte[] token = h.localToken();
            Welcome w = p.handshakeWithNonce(token, NONCE);
            assertArrayEquals(Proofs.proof(token, NONCE, p.connectionId, "mod:dev"), w.proof());
            assertFalse(java.util.Arrays.equals(Proofs.proof(token, NONCE, p.connectionId, "dev"), w.proof()),
                    "not the proof the plugin itself could have been tricked into making");
        }
    }

    @Test
    void noNonceNoProof() throws Exception {
        try (Harness h = new Harness(dir); FakePlugin p = FakePlugin.connect(h, "dev")) {
            assertNull(p.handshake(h.localToken(), List.of(), List.of()).proof());
        }
    }

    @Test
    void pairedDevicesGetAProofWithTheirSecretFromTheFirstWelcomeOn() throws Exception {
        try (Harness h = new Harness(dir)) {
            PairingSession session = h.core.pairing().begin("bots.example");
            byte[] secret;
            try (FakePlugin p = RemoteTest.tls(h, "remote-1")) {
                AuthChallenge ch = p.hello();
                p.control(0, CoreMessages.AUTH_RESPONSE, new AuthResponse(AuthMethod.PAIR, p.deviceId,
                        session.code().getBytes(StandardCharsets.UTF_8), NONCE));
                PairResult r = p.await(CoreMessages.PAIR_RESULT);
                Welcome w = p.await(CoreMessages.WELCOME);
                secret = r.secret();
                assertArrayEquals(Proofs.proof(secret, NONCE, ch.connectionId(), "mod:" + r.deviceId()), w.proof(),
                        "a fresh pairing is proven with the secret just issued");
            }
            try (FakePlugin p = RemoteTest.tls(h, "remote-1")) {
                AuthChallenge ch = p.hello();
                byte[] proof = Proofs.proof(secret, ch.nonce(), ch.connectionId(), "remote-1");
                p.control(0, CoreMessages.AUTH_RESPONSE, new AuthResponse(AuthMethod.DEVICE, "remote-1", proof, NONCE));
                Welcome w = p.await(CoreMessages.WELCOME);
                assertArrayEquals(Proofs.proof(secret, NONCE, ch.connectionId(), "mod:remote-1"), w.proof());
            }
        }
    }

    @Test
    void closingDeletesTheEndpointFile() throws Exception {
        Path endpoint;
        try (Harness h = new Harness(dir)) {
            endpoint = h.core.endpointFile();
            assertTrue(Files.exists(endpoint));
            assertNotNull(h.localToken());
        }
        assertFalse(Files.exists(endpoint), "the token in it is dead, so the file goes");
    }
}
