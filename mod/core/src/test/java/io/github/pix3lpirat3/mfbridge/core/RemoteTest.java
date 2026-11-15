package io.github.pix3lpirat3.mfbridge.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.pix3lpirat3.mfbridge.core.chat.ChatPrint;
import io.github.pix3lpirat3.mfbridge.core.remote.DeviceInfo;
import io.github.pix3lpirat3.mfbridge.core.remote.DevicePaired;
import io.github.pix3lpirat3.mfbridge.core.remote.PairingControls;
import io.github.pix3lpirat3.mfbridge.core.remote.PairingSession;
import io.github.pix3lpirat3.mfbridge.core.remote.Permission;
import io.github.pix3lpirat3.mfbridge.core.remote.DeviceControls;
import io.github.pix3lpirat3.mfbridge.core.remote.PermissionBatch;
import io.github.pix3lpirat3.mfbridge.core.remote.PermissionSet;
import io.github.pix3lpirat3.mfbridge.core.remote.Quota;
import io.github.pix3lpirat3.mfbridge.protocol.core.AuthChallenge;
import io.github.pix3lpirat3.mfbridge.protocol.core.AuthMethod;
import io.github.pix3lpirat3.mfbridge.protocol.core.AuthResponse;
import io.github.pix3lpirat3.mfbridge.protocol.core.ChatSpan;
import io.github.pix3lpirat3.mfbridge.protocol.core.CoreKinds;
import io.github.pix3lpirat3.mfbridge.protocol.core.CoreMessages;
import io.github.pix3lpirat3.mfbridge.protocol.core.ErrorMessage;
import io.github.pix3lpirat3.mfbridge.protocol.core.Goodbye;
import io.github.pix3lpirat3.mfbridge.protocol.core.PairResult;
import io.github.pix3lpirat3.mfbridge.protocol.core.Permissions;
import io.github.pix3lpirat3.mfbridge.protocol.core.SessionKind;
import io.github.pix3lpirat3.mfbridge.protocol.core.SessionOpen;
import io.github.pix3lpirat3.mfbridge.protocol.core.Welcome;
import java.net.Socket;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509ExtendedTrustManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** TLS, pairing, devices, permissions and quotas. */
class RemoteTest {
    @TempDir
    Path dir;

    /** Trusts exactly one certificate by its SHA-256, as a paired plugin pins the fingerprint. */
    static final class PinnedTrust extends X509ExtendedTrustManager {
        private final byte[] pin;

        PinnedTrust(byte[] pin) {
            this.pin = pin.clone();
        }

        private void check(X509Certificate[] chain) throws CertificateException {
            try {
                byte[] sha = MessageDigest.getInstance("SHA-256").digest(chain[0].getEncoded());
                if (!MessageDigest.isEqual(sha, pin)) {
                    throw new CertificateException("fingerprint mismatch");
                }
            } catch (java.security.NoSuchAlgorithmException e) {
                throw new CertificateException(e);
            }
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            check(chain);
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket)
                throws CertificateException {
            check(chain);
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine)
                throws CertificateException {
            check(chain);
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            throw new CertificateException("client certificates are not used");
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket)
                throws CertificateException {
            throw new CertificateException("client certificates are not used");
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine)
                throws CertificateException {
            throw new CertificateException("client certificates are not used");
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    }

    static HttpClient pinned(byte[] sha256) throws Exception {
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, new TrustManager[] {new PinnedTrust(sha256)}, null);
        return HttpClient.newBuilder().sslContext(ctx).build();
    }

    static FakePlugin tls(Harness h, String deviceId) throws Exception {
        return FakePlugin.connect(h, deviceId, pinned(h.core.identity().sha256()),
                "wss://127.0.0.1:" + h.port() + "/bridge/v1");
    }

    /** A paired device: the credential from {@code pair.result} and the welcome after it. */
    record Paired(PairResult result, Welcome welcome) {}

    static Paired pair(Harness h, FakePlugin p, String code) {
        AuthChallenge ch = p.hello();
        assertTrue(ch.methods().contains(AuthMethod.PAIR), "pair is offered while a code is active");
        p.control(0, CoreMessages.AUTH_RESPONSE,
                new AuthResponse(AuthMethod.PAIR, p.deviceId, code.getBytes(StandardCharsets.UTF_8)));
        PairResult r = p.await(CoreMessages.PAIR_RESULT);
        Welcome w = p.await(CoreMessages.WELCOME);
        p.welcome = w;
        return new Paired(r, w);
    }

    static Goodbye refusedPair(FakePlugin p, String code) {
        p.hello();
        p.control(0, CoreMessages.AUTH_RESPONSE,
                new AuthResponse(AuthMethod.PAIR, p.deviceId, code.getBytes(StandardCharsets.UTF_8)));
        return p.await(CoreMessages.GOODBYE);
    }

    @Test
    void localPluginSpeaksWssWithPinnedCertificate() throws Exception {
        try (Harness h = new Harness(dir); FakePlugin p = tls(h, "dev")) {
            Welcome w = p.handshake(h.localToken(), List.of(), List.of());
            assertEquals(PermissionSet.defaults(true).keys(), w.permissions());
            p.openBot("Miner", "example.com");
        }
    }

    @Test
    void wrongPinFailsTheTlsHandshake() throws Exception {
        try (Harness h = new Harness(dir)) {
            byte[] wrong = h.core.identity().sha256();
            wrong[0] ^= 1;
            HttpClient client = pinned(wrong);
            CompletableFuture<?> ws = client.newWebSocketBuilder()
                    .buildAsync(java.net.URI.create("wss://127.0.0.1:" + h.port() + "/bridge/v1"),
                            new java.net.http.WebSocket.Listener() {});
            assertThrows(CompletionException.class, ws::join);
        }
    }

    @Test
    void plainLoopbackCanBeTurnedOff() throws Exception {
        try (Harness h = new Harness(dir, b -> b.allowPlainLoopback(false), DeviceApprover.ALLOW_ONCE)) {
            assertThrows(CompletionException.class, () -> FakePlugin.connect(h, "dev"));
            try (FakePlugin p = tls(h, "dev")) {
                assertNotNull(p.handshake(h.localToken(), List.of(), List.of()));
            }
            String endpoint = Files.readString(h.core.endpointFile());
            assertTrue(endpoint.contains("\"wss://127.0.0.1:" + h.port() + "/bridge/v1\""), endpoint);
        }
    }

    @Test
    void pairingIssuesACredentialThatAuthenticatesLater() throws Exception {
        try (Harness h = new Harness(dir)) {
            List<DevicePaired> paired = new ArrayList<>();
            h.core.events().subscribe(DevicePaired.TYPE, paired::add);
            PairingSession session = h.core.pairing().begin("bots.example");
            assertEquals("mfb://bots.example:" + h.port() + "?fp=" + h.core.identity().pairingPrefix() + "&code="
                    + session.code(), session.uri());
            assertEquals(h.clock.currentTimeMillis() + PairingControls.VALIDITY_MS, session.expiresAtMs());
            Paired result;
            try (FakePlugin p = tls(h, "remote-1")) {
                result = pair(h, p, session.code().toLowerCase(java.util.Locale.ROOT));
            }
            assertEquals("remote-1", result.result().deviceId());
            assertEquals(32, result.result().secret().length);
            assertEquals(h.core.identity().fingerprint(), result.result().fingerprint());
            assertEquals(PermissionSet.defaults(false).keys(), result.welcome().permissions());
            assertFalse(result.welcome().permissions().contains("chat"));
            assertNull(h.core.pairing().active(), "a code is single use");
            assertEquals(1, paired.size());

            DeviceInfo info = h.core.devices().get("remote-1");
            assertNotNull(info);
            assertFalse(info.local());
            assertTrue(info.remembered());

            try (FakePlugin p = tls(h, "remote-1")) {
                AuthChallenge ch = p.hello();
                assertEquals(List.of(AuthMethod.LOCAL, AuthMethod.DEVICE), ch.methods(), "no pair without a code");
            }
            try (FakePlugin p = tls(h, "remote-1")) {
                Welcome w = p.deviceHandshake(result.result().secret());
                assertEquals(result.welcome().permissions(), w.permissions());
                p.openBot("Farmer", "example.com");
                h.tickUntil("connected", () -> h.core.devices().get("remote-1").connections() == 1);
                assertEquals(List.of("Farmer"), h.core.devices().get("remote-1").sessions());
            }
            try (FakePlugin p = tls(h, "remote-1")) {
                byte[] wrong = result.result().secret().clone();
                wrong[0] ^= 1;
                p.hello();
                p.control(0, CoreMessages.AUTH_RESPONSE, new AuthResponse(AuthMethod.DEVICE, "remote-1",
                        io.github.pix3lpirat3.mfbridge.core.auth.Proofs.proof(wrong, new byte[32], 0, "remote-1")));
                assertEquals("unauthorized", p.await(CoreMessages.GOODBYE).reason());
            }
        }
        String json = Files.readString(dir.resolve("mineflayer_bridge").resolve("devices.json"));
        assertTrue(json.contains("\"remote-1\"") && json.contains("\"secret\""), json);
    }

    @Test
    void pairingIsRefusedOverPlainWebSocket() throws Exception {
        try (Harness h = new Harness(dir); FakePlugin p = FakePlugin.connect(h, "remote-1")) {
            PairingSession session = h.core.pairing().begin(null);
            AuthChallenge ch = p.hello();
            assertFalse(ch.methods().contains(AuthMethod.PAIR));
            p.control(0, CoreMessages.AUTH_RESPONSE,
                    new AuthResponse(AuthMethod.PAIR, "remote-1", session.code().getBytes(StandardCharsets.UTF_8)));
            assertEquals("unauthorized", p.await(CoreMessages.GOODBYE).reason());
            assertNotNull(h.core.pairing().active(), "a refused method is not an attempt");
        }
    }

    @Test
    void expiredCodeIsRefused() throws Exception {
        try (Harness h = new Harness(dir)) {
            PairingSession session = h.core.pairing().begin(null);
            h.clock.advanceMs(PairingControls.VALIDITY_MS);
            assertNull(h.core.pairing().active());
            try (FakePlugin p = tls(h, "late")) {
                Goodbye g = refusedPair(p, session.code());
                assertEquals("unauthorized", g.reason());
                assertEquals("code expired", g.message());
                assertFalse(g.retry());
            }
            assertNull(h.core.devices().get("late"));
        }
    }

    @Test
    void secondUseOfACodeIsRefused() throws Exception {
        try (Harness h = new Harness(dir)) {
            PairingSession session = h.core.pairing().begin(null);
            try (FakePlugin p = tls(h, "first")) {
                pair(h, p, session.code());
            }
            try (FakePlugin p = tls(h, "second")) {
                assertEquals("no active code", refusedPair(p, session.code()).message());
            }
            assertNull(h.core.devices().get("second"));
        }
    }

    @Test
    void fiveWrongCodesCancelPairing() throws Exception {
        try (Harness h = new Harness(dir)) {
            PairingSession session = h.core.pairing().begin(null);
            for (int i = 0; i < PairingControls.MAX_FAILED_ATTEMPTS; i++) {
                assertNotNull(h.core.pairing().active(), "still active after " + i + " failures");
                try (FakePlugin p = tls(h, "guess-" + i)) {
                    Goodbye g = refusedPair(p, "WRONG" + i);
                    assertEquals("unauthorized", g.reason());
                    assertEquals("code refused", g.message());
                }
            }
            assertNull(h.core.pairing().active());
            try (FakePlugin p = tls(h, "late")) {
                assertEquals("unauthorized", refusedPair(p, session.code()).reason());
            }
        }
    }

    @Test
    void pairedIdThatIsTakenGetsAFreshOne() throws Exception {
        try (Harness h = new Harness(dir, b -> {}, req -> CompletableFuture.completedFuture(ApprovalDecision.ALLOW))) {
            try (FakePlugin local = FakePlugin.ready(h, "taken")) {
                assertNotNull(local.welcome);
            }
            PairingSession session = h.core.pairing().begin(null);
            try (FakePlugin p = tls(h, "taken")) {
                Paired r = pair(h, p, session.code());
                assertFalse(r.result().deviceId().equals("taken"));
                assertTrue(h.core.devices().get("taken").local(), "the local device keeps its id");
            }
        }
    }

    @Test
    void revokingClosesConnectionsAndDeletesTheCredential() throws Exception {
        try (Harness h = new Harness(dir)) {
            PairingSession session = h.core.pairing().begin(null);
            byte[] secret;
            try (FakePlugin p = tls(h, "remote-1")) {
                secret = pair(h, p, session.code()).result().secret();
                p.openBot("Farmer", "example.com");
                assertTrue(h.core.devices().revoke("remote-1"));
                Goodbye g = p.await(CoreMessages.GOODBYE);
                assertEquals("revoked", g.reason());
                assertFalse(g.retry());
                h.tickUntil("close", p.closed::isDone);
            }
            assertNull(h.core.devices().get("remote-1"));
            assertTrue(h.core.sessionStats().isEmpty(), "sessions of a revoked device end at once");
            try (FakePlugin p = tls(h, "remote-1")) {
                p.hello();
                p.control(0, CoreMessages.AUTH_RESPONSE, new AuthResponse(AuthMethod.DEVICE, "remote-1", secret));
                assertEquals("unauthorized", p.await(CoreMessages.GOODBYE).reason());
            }
        }
    }

    @Test
    void remoteDenialsAreBatchedIntoOnePromptThenGrantIsPushed() throws Exception {
        try (Harness h = ResumeTest.always(dir, b -> {})) {
            List<ChatPrint> printed = new ArrayList<>();
            h.core.events().subscribe(ChatPrint.TYPE, printed::add);
            PairingSession session = h.core.pairing().begin(null);
            try (FakePlugin p = tls(h, "remote-1")) {
                pair(h, p, session.code());
                int s = p.openBot("Farmer", "example.com").sessionId();
                List<ChatSpan> spans = List.of(new ChatSpan("hi", null, false, false, null, null));
                p.reliable(s, CoreMessages.CHAT_PRINT, new io.github.pix3lpirat3.mfbridge.protocol.core.ChatPrint(spans));
                ErrorMessage e = p.await(CoreMessages.ERROR, s);
                assertEquals("permission_denied", e.error().code());
                assertEquals(java.util.Map.of("permission", "chat"), e.error().details(), "the details name the permission");
                assertEquals(CoreMessages.CHAT_PRINT.id(), e.relatesTo());

                long req = p.request(0, CoreMessages.SESSION_OPEN,
                        new SessionOpen(SessionKind.CONTROLLER, "dash", null, List.of()));
                FakePlugin.Frame f = p.await("controller denial", x -> x.is(CoreMessages.ERROR) && x.env().req() == req);
                ErrorMessage denied = f.decode(CoreMessages.ERROR);
                assertEquals("permission_denied", denied.error().code());
                assertEquals(java.util.Map.of("permission", "controller"), denied.error().details());
                assertEquals(CoreMessages.SESSION_OPEN.id(), denied.relatesTo());

                assertNull(h.core.devices().permissionBatch(), "denials are collected before asking");
                h.clock.advanceMs(DeviceControls.PROMPT_COLLECT_MS);
                PermissionBatch batch = h.core.devices().permissionBatch();
                assertNotNull(batch);
                assertEquals("remote-1", batch.deviceId());
                assertEquals(List.of(new PermissionBatch.Grant(Permission.CHAT, "Farmer"),
                        new PermissionBatch.Grant(Permission.CONTROLLER, "dash")), batch.items(), "one prompt for both");
                assertEquals(batch, h.core.devices().permissionBatch(), "the same batch until answered");

                h.core.devices().answerPermissions(batch, List.of(batch.items().getFirst()));
                assertNull(h.core.devices().permissionBatch(), "asked once");
                h.tick();
                Permissions pushed = p.await(CoreMessages.PERMISSIONS, 0);
                assertTrue(pushed.granted().contains("chat"), pushed.granted().toString());

                p.reliable(s, CoreMessages.CHAT_PRINT, new io.github.pix3lpirat3.mfbridge.protocol.core.ChatPrint(spans));
                p.sync();
                assertEquals(1, printed.size(), "granted chat prints");
                assertFalse(p.poll(x -> x.is(CoreMessages.ERROR)));
                assertTrue(h.core.devices().get("remote-1").permissions().has(Permission.CHAT));
                assertFalse(h.core.devices().get("remote-1").permissions().has(Permission.CONTROLLER),
                        "an unchecked item is denied");

                long again = p.request(0, CoreMessages.SESSION_OPEN,
                        new SessionOpen(SessionKind.CONTROLLER, "dash", null, List.of()));
                p.await("second denial", x -> x.is(CoreMessages.ERROR) && x.env().req() == again);
            }
            h.clock.advanceMs(DeviceControls.PROMPT_COLLECT_MS);
            assertNull(h.core.devices().permissionBatch(), "a denied permission is never asked again");
        }
    }

    @Test
    void localDevicesHoldEveryPermissionAndAreNeverPrompted() throws Exception {
        try (Harness h = new Harness(dir); FakePlugin p = FakePlugin.ready(h, "dev")) {
            assertEquals(Permission.values().length, h.core.devices().get("dev").permissions().granted().size());
            h.core.devices().setPermission("dev", Permission.CHAT, false);
            int s = p.openBot("Farmer", "example.com").sessionId();
            List<ChatSpan> spans = List.of(new ChatSpan("hi", null, false, false, null, null));
            p.reliable(s, CoreMessages.CHAT_PRINT, new io.github.pix3lpirat3.mfbridge.protocol.core.ChatPrint(spans));
            assertEquals("permission_denied", p.await(CoreMessages.ERROR, s).error().code(), "the player's toggle still applies");
            h.clock.advanceMs(DeviceControls.PROMPT_COLLECT_MS);
            assertNull(h.core.devices().permissionBatch(), "local devices are never prompted");
        }
    }

    @Test
    void revokedPermissionRemovesGatedObjectsAndBotOverridesWin() throws Exception {
        try (Harness h = ObjectSyncTest.always(dir); FakePlugin p = FakePlugin.ready(h, "dev")) {
            int s = p.openBot("Miner", "example.com").sessionId();
            String key = "bot:miner@example.com/core/box/a";
            p.upsert(s, FakePlugin.object(key, CoreKinds.BOX, 1, ObjectSyncTest.box(1)));
            p.sync();
            h.tick();
            assertTrue(ObjectSyncTest.keys(h).contains(key));
            assertTrue(h.core.devices().get("dev").bots().contains("miner@example.com"));

            h.core.devices().setBotPermission("dev", "miner@example.com", Permission.RENDER_WORLD, false);
            h.tick();
            Permissions pushed = p.await(CoreMessages.PERMISSIONS, 0);
            assertTrue(pushed.granted().contains("render.world"), "the device-level set is unchanged");

            p.upsert(s, FakePlugin.object(key, CoreKinds.BOX, 2, ObjectSyncTest.box(2)));
            ErrorMessage e = p.await(CoreMessages.ERROR, s);
            assertEquals("permission_denied", e.error().code());
            assertEquals(CoreMessages.OBJ_UPSERT.id(), e.relatesTo());
            p.upsert(s, FakePlugin.object(key, CoreKinds.BOX, 3, ObjectSyncTest.box(3)));
            p.sync();
            assertFalse(p.poll(f -> f.is(CoreMessages.ERROR)), "a denial is reported once per session");

            h.core.devices().setBotPermission("dev", "miner@example.com", Permission.RENDER_WORLD, null);
            p.upsert(s, FakePlugin.object(key, CoreKinds.BOX, 4, ObjectSyncTest.box(4)));
            p.sync();
            assertFalse(p.poll(f -> f.is(CoreMessages.ERROR)));
            h.tick();
            assertTrue(ObjectSyncTest.keys(h).contains(key));

            h.core.devices().setPermission("dev", Permission.RENDER_WORLD, false);
            h.tick();
            assertFalse(ObjectSyncTest.keys(h).contains(key), "revoking removes the objects it gated");
            p.await("permissions without render.world", f -> f.is(CoreMessages.PERMISSIONS)
                    && !f.decode(CoreMessages.PERMISSIONS).granted().contains("render.world"));
        }
    }

    @Test
    void quotasAreAdvertisedAndEnforced() throws Exception {
        DeviceApprover allow = req -> CompletableFuture.completedFuture(ApprovalDecision.ALLOW);
        try (Harness h = new Harness(dir, b -> {}, allow)) {
            try (FakePlugin p = FakePlugin.ready(h, "dev")) {
                Welcome w = p.welcome;
                assertNotNull(w);
                assertEquals(20_000, w.limits().maxObjects());
                assertEquals(4_096, w.limits().maxDisplays());
                assertEquals(256, w.limits().maxEntityDisplays());
                assertEquals(32, w.limits().maxEmitters());
                assertEquals(1_024, w.limits().maxInteractive());
                assertEquals(40, w.limits().effectsPerSecond());
                assertEquals(128 << 20, w.limits().blobInFlightBytes());
            }
            h.core.devices().setQuota("dev", Quota.OBJECTS, 2);
            h.core.devices().setQuota("dev", Quota.DISPLAYS, 10);
            assertThrows(IllegalArgumentException.class, () -> h.core.devices().setQuota("dev", Quota.EMITTERS, -1));
            try (FakePlugin p = FakePlugin.ready(h, "dev")) {
                assertEquals(2, p.welcome.limits().maxObjects());
                assertEquals(10, p.welcome.limits().maxDisplays());
                int s = p.openBot("Miner", "example.com").sessionId();
                p.upsert(s,
                        FakePlugin.object("bot:miner@example.com/core/box/a", CoreKinds.BOX, 1, ObjectSyncTest.box(1)),
                        FakePlugin.object("bot:miner@example.com/core/box/b", CoreKinds.BOX, 1, ObjectSyncTest.box(2)),
                        FakePlugin.object("bot:miner@example.com/core/box/c", CoreKinds.BOX, 1, ObjectSyncTest.box(3)));
                ErrorMessage e = p.await(CoreMessages.ERROR, s);
                assertEquals("quota_exceeded", e.error().code());
            }
            h.core.devices().resetQuotas("dev");
            assertEquals(20_000, h.core.devices().get("dev").quotas().get(Quota.OBJECTS));
        }
        String json = Files.readString(dir.resolve("mineflayer_bridge").resolve("devices.json"));
        assertFalse(json.contains("\"objects\""), "defaults are not written");
    }

    @Test
    void renameAndListShowDevices() throws Exception {
        DeviceApprover allow = req -> CompletableFuture.completedFuture(ApprovalDecision.ALLOW);
        try (Harness h = new Harness(dir, b -> {}, allow)) {
            try (FakePlugin p = FakePlugin.ready(h, "dev")) {
                assertNotNull(p.welcome);
            }
            h.core.devices().rename("dev", "  Laptop ");
            assertEquals("Laptop", h.core.devices().get("dev").name());
            assertEquals("test-device", h.core.devices().get("dev").reportedName());
            assertEquals(List.of("dev"), h.core.devices().list().stream().map(DeviceInfo::id).toList());
            assertTrue(h.core.devices().get("dev").lastAddress().startsWith("127.0.0.1:"));
            assertEquals(h.clock.currentTimeMillis(), h.core.devices().get("dev").lastSeenMs());
            h.core.devices().rename("dev", " ");
            assertEquals("test-device", h.core.devices().get("dev").name());
        }
        try (Harness h = new Harness(dir)) {
            assertNotNull(h.core.devices().get("dev"), "remembered across restarts");
            assertArrayEquals(new String[0], h.core.devices().get("dev").sessions().toArray(new String[0]));
        }
        assertTrue(Arrays.asList(Permission.values()).stream().allMatch(x -> Permission.byKey(x.key()) == x));
    }
}
