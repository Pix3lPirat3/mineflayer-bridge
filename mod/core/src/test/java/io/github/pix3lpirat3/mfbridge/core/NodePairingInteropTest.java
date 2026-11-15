package io.github.pix3lpirat3.mfbridge.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.pix3lpirat3.mfbridge.core.remote.PairingSession;
import io.github.pix3lpirat3.mfbridge.protocol.core.BoxData;
import io.github.pix3lpirat3.mfbridge.protocol.core.WorldKey;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Pairs the real Node CLI with the real core over TLS, then connects a bot with the issued device
 * credential. Skipped when Node or the built plugin is unavailable.
 */
final class NodePairingInteropTest {

    @Test
    void cliPairsOverTlsAndBotReconnectsWithDeviceSecret(@TempDir Path dir) throws Exception {
        Path repo = Path.of(System.getProperty("bridge.fixtures")).getParent();
        Path cli = repo.resolve("node/packages/plugin/dist/bin.js");
        assumeTrue(Files.exists(cli), "plugin package not built");
        assumeTrue(nodeAvailable(), "node not on PATH");
        Path credentials = dir.resolve("credentials.json");

        try (Harness h = new Harness(dir.resolve("config"))) {
            // The bot reports localhost:25565; auto-attach needs the player in the same world.
            h.core.setPlayerContext(new WorldKey("localhost:25565", null, "minecraft:overworld"));
            PairingSession pairing = h.core.pairing().begin("127.0.0.1");
            assertTrue(pairing.uri().startsWith("mfb://127.0.0.1:"), pairing.uri());

            Process pair = new ProcessBuilder("node", cli.toString(), "pair", pairing.uri(), "--credentials",
                            credentials.toString(), "--name", "interop-remote")
                    .directory(repo.toFile())
                    .redirectErrorStream(true)
                    .start();
            BlockingQueue<String> pairOut = new LinkedBlockingQueue<>();
            Thread.ofVirtual().start(() -> pump(pair, pairOut));
            long deadline = System.currentTimeMillis() + 20_000;
            while (pair.isAlive() && System.currentTimeMillis() < deadline) {
                h.tick();
                Harness.sleep();
            }
            assertEquals(0, pair.waitFor(5, TimeUnit.SECONDS) ? pair.exitValue() : -1, "pair output: " + pairOut);
            assertTrue(h.core.devices().list().stream().anyMatch(d -> !d.local()),
                    "a paired device is registered");
            assertTrue(h.core.pairing().active() == null, "the code is single use");

            String endpoint = "wss://127.0.0.1:" + h.core.boundPort() + "/bridge/v1";
            Process bot = new ProcessBuilder("node", repo.resolve("tools/interop/remote.ts").toString(), endpoint,
                            credentials.toString())
                    .directory(repo.toFile())
                    .redirectError(ProcessBuilder.Redirect.INHERIT)
                    .start();
            BlockingQueue<String> lines = new LinkedBlockingQueue<>();
            Thread reader = Thread.ofVirtual().start(() -> pump(bot, lines));
            try (Writer stdin = new OutputStreamWriter(bot.getOutputStream(), StandardCharsets.UTF_8)) {
                awaitLine(h, lines, "READY");
                h.tickUntil("box from the remote bot", () -> h.core.renderSnapshot().entries().stream()
                        .anyMatch(e -> e.data() instanceof BoxData));
                stdin.write("EXIT\n");
                stdin.flush();
                long end = System.currentTimeMillis() + Harness.TIMEOUT_MS;
                while (bot.isAlive() && System.currentTimeMillis() < end) {
                    h.tick();
                    Harness.sleep();
                }
            } finally {
                bot.destroyForcibly();
                reader.join(1000);
            }
        }
    }

    private static void awaitLine(Harness h, BlockingQueue<String> lines, String expected) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline) {
            h.tick();
            @Nullable String line = lines.poll(2, TimeUnit.MILLISECONDS);
            if (expected.equals(line)) {
                return;
            }
        }
        throw new AssertionError("node never printed " + expected + "; got " + lines);
    }

    private static void pump(Process p, BlockingQueue<String> lines) {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            for (String line; (line = r.readLine()) != null; ) {
                lines.add(line.trim());
            }
        } catch (IOException ignored) {
            // The process ended; the test asserts on what arrived.
        }
    }

    private static boolean nodeAvailable() {
        try {
            return new ProcessBuilder("node", "--version").start().waitFor(10, TimeUnit.SECONDS);
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
