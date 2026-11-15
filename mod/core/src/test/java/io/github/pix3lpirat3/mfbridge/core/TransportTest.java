package io.github.pix3lpirat3.mfbridge.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import io.github.pix3lpirat3.mfbridge.protocol.core.CoreMessages;
import io.github.pix3lpirat3.mfbridge.protocol.core.Ping;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TransportTest {
    @TempDir
    Path dir;

    private static String upgrade(int port, String path, boolean origin) throws IOException {
        try (Socket s = new Socket("127.0.0.1", port)) {
            s.setSoTimeout(5000);
            OutputStream out = s.getOutputStream();
            String req = "GET " + path + " HTTP/1.1\r\n"
                    + "Host: 127.0.0.1:" + port + "\r\n"
                    + "Upgrade: websocket\r\n"
                    + "Connection: Upgrade\r\n"
                    + "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n"
                    + "Sec-WebSocket-Version: 13\r\n"
                    + (origin ? "Origin: http://evil.example\r\n" : "")
                    + "\r\n";
            out.write(req.getBytes(StandardCharsets.US_ASCII));
            out.flush();
            BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.US_ASCII));
            return in.readLine();
        }
    }

    @Test
    void originHeaderIsRejectedWith403() throws Exception {
        try (Harness h = new Harness(dir)) {
            assertTrue(upgrade(h.port(), "/bridge/v1", true).contains(" 403 "));
            assertTrue(upgrade(h.port(), "/bridge/v1", false).contains(" 101 "));
        }
    }

    @Test
    void otherPathsGet404() throws Exception {
        try (Harness h = new Harness(dir)) {
            assertTrue(upgrade(h.port(), "/", false).contains(" 404 "));
            assertTrue(upgrade(h.port(), "/bridge/v2", false).contains(" 404 "));
        }
    }

    @Test
    void textFrameClosesWith1003() throws Exception {
        try (Harness h = new Harness(dir); FakePlugin p = FakePlugin.connect(h, "dev")) {
            p.ws.sendText("hello", true).get(5, TimeUnit.SECONDS);
            assertEquals(1003, p.closed.get(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void aPeerThatDoesNotAuthenticateIsCutOffAfterAFewFrames() throws Exception {
        try (Harness h = new Harness(dir); FakePlugin p = FakePlugin.connect(h, "dev")) {
            // Pings are answered before hello, but an unknown peer only gets eight frames.
            for (int i = 0; i < 9; i++) {
                p.control(0, CoreMessages.PING, new Ping(i));
            }
            p.closed.get(5, TimeUnit.SECONDS);
            assertTrue(h.core.queuedInboundFrames() <= 8, "the ninth frame was never queued");
        }
    }

    @Test
    void aPeerThatDoesNotAuthenticateMaySendLittle() throws Exception {
        try (Harness h = new Harness(dir); FakePlugin p = FakePlugin.connect(h, "dev")) {
            p.ws.sendBinary(ByteBuffer.wrap(new byte[70_000]), true).get(5, TimeUnit.SECONDS);
            p.closed.get(5, TimeUnit.SECONDS);
            assertEquals(0, h.core.queuedInboundFrames(), "64 KiB before authentication, and this was more");
        }
    }

    @Test
    void anAuthenticatedPeerIsNotHeldToThatAllowance() throws Exception {
        try (Harness h = new Harness(dir); FakePlugin p = FakePlugin.ready(h, "dev")) {
            for (int i = 0; i < 40; i++) {
                p.control(0, CoreMessages.PING, new Ping(i));
            }
            p.ws.sendBinary(ByteBuffer.wrap(new byte[70_000]), true).get(5, TimeUnit.SECONDS);
            p.sync();
        }
    }

    @Test
    void onlySoManyPeersMayWaitToAuthenticate() throws Exception {
        try (Harness h = new Harness(dir, b -> b.preAuth(8, 64 << 10, 2), DeviceApprover.ALLOW_ONCE);
                FakePlugin a = FakePlugin.connect(h, "a"); FakePlugin b = FakePlugin.connect(h, "b")) {
            assertThrows(CompletionException.class, () -> FakePlugin.connect(h, "c"), "two are waiting already");

            // One authenticates and no longer counts.
            a.handshake(h.localToken(), List.of(), List.of());
            try (FakePlugin c = FakePlugin.connect(h, "c")) {
                assertThrows(CompletionException.class, () -> FakePlugin.connect(h, "d"));
                c.handshake(h.localToken(), List.of(), List.of());
            }
            // One leaves without authenticating and its place is free again.
            b.abort();
            FakePlugin late = null;
            long deadline = System.currentTimeMillis() + Harness.TIMEOUT_MS;
            while (late == null) {
                try {
                    late = FakePlugin.connect(h, "e");
                } catch (CompletionException e) {
                    assertTrue(System.currentTimeMillis() < deadline, "the place of a peer that left is never freed");
                    Harness.sleep();
                }
            }
            late.close();
        }
    }

    @Test
    void oversizedFrameClosesWith1009() throws Exception {
        try (Harness h = new Harness(dir, b -> b.maxFrameBytes(1024), DeviceApprover.ALLOW_ONCE);
                FakePlugin p = FakePlugin.connect(h, "dev")) {
            p.ws.sendBinary(ByteBuffer.wrap(new byte[4096]), true).get(5, TimeUnit.SECONDS);
            assertEquals(1009, p.closed.get(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void endpointFileHasUrlAndFreshToken() throws Exception {
        byte[] first;
        String fingerprint;
        try (Harness h = new Harness(dir)) {
            Path file = dir.resolve("mineflayer_bridge").resolve("endpoint.json");
            JsonObject o = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            assertEquals(1, o.get("v").getAsInt());
            assertEquals("ws://127.0.0.1:" + h.port() + "/bridge/v1", o.get("url").getAsString());
            fingerprint = h.core.identity().fingerprint();
            assertEquals(fingerprint, o.get("fingerprint").getAsString());
            assertTrue(fingerprint.matches("sha256:[0-9a-f]{64}"), fingerprint);
            first = h.localToken();
            assertEquals(32, first.length);
        }
        try (Harness h = new Harness(dir)) {
            byte[] second = h.localToken();
            assertFalse(Arrays.equals(first, second), "token regenerated on every start");
            assertEquals(fingerprint, h.core.identity().fingerprint(), "the instance identity survives restarts");
        }
    }

    @Test
    void portInUseFailsFast() throws Exception {
        try (Harness h = new Harness(dir)) {
            int port = h.port();
            assertThrows(IOException.class, () -> new Harness(dir.resolve("other"), b -> b.port(port),
                    DeviceApprover.ALLOW_ONCE));
        }
    }
}
