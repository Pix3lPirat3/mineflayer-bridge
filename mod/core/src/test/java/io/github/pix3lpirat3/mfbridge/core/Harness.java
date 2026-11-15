package io.github.pix3lpirat3.mfbridge.core;

import static org.junit.jupiter.api.Assertions.fail;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.pix3lpirat3.mfbridge.core.util.Encoding;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.slf4j.LoggerFactory;

/** Starts a {@link BridgeCore} on an ephemeral port and ticks it from the test thread. */
final class Harness implements AutoCloseable {
    static final long BUDGET = 2_000_000L;
    static final long TIMEOUT_MS = 5_000;

    final Path configDir;
    final TestClock clock = new TestClock();
    final BridgeCore core;
    DeviceApprover approver;
    /** Notifications bots asked for, in order. */
    final java.util.List<Notifier.Notification> notifications = new java.util.concurrent.CopyOnWriteArrayList<>();

    Harness(Path configDir) throws IOException {
        this(configDir, b -> {}, DeviceApprover.ALLOW_ONCE);
    }

    Harness(Path configDir, Consumer<BridgeConfig.Builder> customize, DeviceApprover approver) throws IOException {
        this.configDir = configDir;
        this.approver = approver;
        // Most tests drive the approval prompt; the auto-approval tests turn it off.
        BridgeConfig.Builder b = BridgeConfig.builder(configDir).port(0).clock(clock).askLocalApproval(true);
        customize.accept(b);
        HostEnvironment host = new HostEnvironment("0.1.0-test", "26.3", "test",
                req -> this.approver.requestApproval(req), notifications::add, LoggerFactory.getLogger("bridge-test"));
        core = BridgeCore.start(b.build(), host);
    }

    int port() {
        return core.boundPort();
    }

    byte[] localToken() throws IOException {
        String text = Files.readString(core.endpointFile(), StandardCharsets.UTF_8);
        JsonObject o = JsonParser.parseString(text).getAsJsonObject();
        return Encoding.fromBase64Url(o.get("localToken").getAsString());
    }

    void tick() {
        core.tick(BUDGET);
    }

    /** Ticks until the condition holds, failing after a real-time timeout. */
    void tickUntil(String what, BooleanSupplier condition) {
        long deadline = System.currentTimeMillis() + TIMEOUT_MS;
        while (true) {
            tick();
            if (condition.getAsBoolean()) {
                return;
            }
            if (System.currentTimeMillis() > deadline) {
                fail("timed out waiting for " + what);
            }
            sleep();
        }
    }

    /** Ticks for a short real-time period, for asserting that nothing happens. */
    void tickFor(long ms) {
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end) {
            tick();
            sleep();
        }
    }

    static void sleep() {
        try {
            Thread.sleep(2);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void close() {
        core.close();
    }
}
