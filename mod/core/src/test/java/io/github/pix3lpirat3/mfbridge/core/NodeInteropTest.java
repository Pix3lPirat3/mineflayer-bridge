package io.github.pix3lpirat3.mfbridge.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.pix3lpirat3.mfbridge.protocol.core.BoxData;
import io.github.pix3lpirat3.mfbridge.protocol.core.DepthMode;
import io.github.pix3lpirat3.mfbridge.protocol.core.LabelData;
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
 * Runs the real Node plugin against the real core. Skipped when Node or
 * the built protocol package is unavailable, so the pure Java build never depends on Node.
 */
final class NodeInteropTest {
    private static final WorldKey WORLD = new WorldKey("localhost:25565", null, "minecraft:overworld");

    @Test
    void pluginDeclaresAndUpdatesShapes(@TempDir Path dir) throws Exception {
        Path repo = Path.of(System.getProperty("bridge.fixtures")).getParent();
        Path script = repo.resolve("tools/interop/declare.ts");
        assumeTrue(Files.exists(repo.resolve("node/packages/plugin/dist/index.js")), "plugin package not built");
        assumeTrue(nodeAvailable(), "node not on PATH");

        try (Harness h = new Harness(dir.resolve("config"))) {
            h.core.setPlayerContext(WORLD);
            Process node = new ProcessBuilder("node", script.toString(), h.core.endpointFile().toString(),
                            dir.resolve("credentials.json").toString())
                    .directory(repo.toFile())
                    .redirectError(ProcessBuilder.Redirect.INHERIT)
                    .start();
            BlockingQueue<String> lines = new LinkedBlockingQueue<>();
            Thread reader = Thread.ofVirtual().start(() -> pump(node, lines));
            try (Writer stdin = new OutputStreamWriter(node.getOutputStream(), StandardCharsets.UTF_8)) {
                awaitLine(h, lines, "READY");
                h.tickUntil("box and label rendered", () -> h.core.renderSnapshot().size() == 2);

                RenderEntry box = entry(h, BoxData.class);
                BoxData data = (BoxData) box.data();
                assertEquals(1.0, data.min().x());
                assertNotNull(box.style(), "named style resolved under ~style/");
                assertEquals(0xFFFF0000, box.style().color(), "colours are ARGB on the wire");
                assertEquals(0.25f, box.style().fill());
                assertEquals(DepthMode.XRAY, box.style().depth());
                assertEquals("Hello from Node", ((LabelData) entry(h, LabelData.class).data()).text());

                stdin.write("MOVE\n");
                stdin.flush();
                awaitLine(h, lines, "MOVED");
                h.tickUntil("box moved", () -> ((BoxData) entry(h, BoxData.class).data()).min().x() == 5.0);

                stdin.write("EXIT\n");
                stdin.flush();
                long deadline = System.currentTimeMillis() + Harness.TIMEOUT_MS;
                while (node.isAlive() && System.currentTimeMillis() < deadline) {
                    h.tick();
                    Harness.sleep();
                }
                assertEquals(0, node.waitFor(5, TimeUnit.SECONDS) ? node.exitValue() : -1, "node exited cleanly");
            } finally {
                node.destroyForcibly();
                reader.join(1000);
            }
        }
    }

    private static RenderEntry entry(Harness h, Class<?> kind) {
        for (RenderEntry e : h.core.renderSnapshot().entries()) {
            if (kind.isInstance(e.data())) {
                return e;
            }
        }
        throw new AssertionError("no " + kind.getSimpleName() + " in snapshot");
    }

    /** The plugin needs ticks to complete its handshake, so wait while ticking. */
    private static void awaitLine(Harness h, BlockingQueue<String> lines, String expected) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            h.tick();
            @Nullable String line = lines.poll(2, TimeUnit.MILLISECONDS);
            if (expected.equals(line)) {
                return;
            }
        }
        throw new AssertionError("node never printed " + expected);
    }

    private static void pump(Process node, BlockingQueue<String> lines) {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(node.getInputStream(), StandardCharsets.UTF_8))) {
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
