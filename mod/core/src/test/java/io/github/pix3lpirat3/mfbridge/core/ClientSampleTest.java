package io.github.pix3lpirat3.mfbridge.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.pix3lpirat3.mfbridge.core.remote.Permission;
import io.github.pix3lpirat3.mfbridge.core.remote.DeviceControls;
import io.github.pix3lpirat3.mfbridge.protocol.core.ClientSample;
import io.github.pix3lpirat3.mfbridge.protocol.core.CoreMessages;
import io.github.pix3lpirat3.mfbridge.protocol.core.ErrorMessage;
import io.github.pix3lpirat3.mfbridge.protocol.core.SampleRequest;
import io.github.pix3lpirat3.mfbridge.protocol.core.SlotItem;
import io.github.pix3lpirat3.mfbridge.protocol.core.Target;
import io.github.pix3lpirat3.mfbridge.protocol.core.Vec3d;
import io.github.pix3lpirat3.mfbridge.protocol.core.Vec3i;
import io.github.pix3lpirat3.mfbridge.protocol.core.WorldReport;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ClientSampleTest {
    @TempDir
    Path dir;

    private static final Target BLOCK = new Target("block", new Vec3i(1, 64, 2), "up", null, new Vec3d(1.5, 65, 2.5), null, null);
    private static final SlotItem PICK = new SlotItem("minecraft:diamond_pickaxe");

    private static void feed(Harness h, double x) {
        h.core.samples().feed(new Vec3d(x, 64, 0), 90f, 10f, BLOCK, PICK);
    }

    /** Feeds and ticks until a client.sample arrives, advancing the test clock by a tick each round. */
    private static ClientSample nextSample(Harness h, FakePlugin p, int s, double x) {
        long deadline = System.currentTimeMillis() + Harness.TIMEOUT_MS;
        while (true) {
            feed(h, x);
            h.clock.advanceMs(50);
            h.tick();
            if (p.poll(f -> f.is(CoreMessages.CLIENT_SAMPLE) && f.env().session() == s)) {
                List<FakePlugin.Frame> all = p.all();
                for (int i = all.size() - 1; i >= 0; i--) {
                    if (all.get(i).is(CoreMessages.CLIENT_SAMPLE)) {
                        return all.get(i).decode(CoreMessages.CLIENT_SAMPLE);
                    }
                }
            }
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("no client.sample; received " + p.describe());
            }
            Harness.sleep();
        }
    }

    @Test
    void unpermittedFieldsAreDeniedAndOmitted() throws Exception {
        try (Harness h = new Harness(dir); FakePlugin p = FakePlugin.ready(h, "dev")) {
            for (Permission x : List.of(Permission.CLIENT_SAMPLE_POSITION, Permission.CLIENT_SAMPLE_TARGET,
                    Permission.CLIENT_SAMPLE_INVENTORY)) {
                assertTrue(h.core.devices().get("dev").permissions().has(x), "local devices share samples");
                h.core.devices().setPermission("dev", x, false);
            }
            int s = p.openBot("Miner", "example.com").sessionId();
            p.control(s, CoreMessages.WORLD_REPORT, new WorldReport(InterestRenderTest.BOT_WORLD, null));
            h.core.setPlayerContext(InterestRenderTest.PLAYER_WORLD);
            p.control(s, CoreMessages.SAMPLE_REQUEST, new SampleRequest(20f, true, true, true));

            ErrorMessage e = p.await(CoreMessages.ERROR, s);
            assertEquals("permission_denied", e.error().code());
            assertEquals(CoreMessages.SAMPLE_REQUEST.id(), e.relatesTo());
            h.clock.advanceMs(DeviceControls.PROMPT_COLLECT_MS);
            assertNull(h.core.devices().permissionBatch(), "local devices are never prompted");

            for (int i = 0; i < 10; i++) {
                feed(h, 0);
                h.clock.advanceMs(100);
                h.tick();
            }
            p.sync();
            assertFalse(p.poll(f -> f.is(CoreMessages.CLIENT_SAMPLE)), "nothing is shared without a grant");
            assertFalse(h.core.samples().wantsPosition());

            h.core.devices().setPermission("dev", Permission.CLIENT_SAMPLE_POSITION, true);
            ClientSample first = nextSample(h, p, s, 1);
            assertEquals(new Vec3d(1, 64, 0), first.position());
            assertEquals(90f, first.yaw());
            assertEquals(10f, first.pitch());
            assertNull(first.target(), "target needs client.sample.target");
            assertNull(first.heldItem(), "held item needs client.sample.inventory");
            assertTrue(h.core.samples().wantsPosition());
            assertFalse(h.core.samples().wantsTarget());

            h.core.devices().setPermission("dev", Permission.CLIENT_SAMPLE_TARGET, true);
            h.core.devices().setPermission("dev", Permission.CLIENT_SAMPLE_INVENTORY, true);
            ClientSample full = nextSample(h, p, s, 2);
            assertEquals(BLOCK, full.target());
            assertEquals(PICK, full.heldItem());
        }
    }

    @Test
    void sentOnlyWhileAttachedAndWorldMatched() throws Exception {
        try (Harness h = new Harness(dir); FakePlugin p = FakePlugin.ready(h, "dev")) {
            h.core.devices().setPermission("dev", Permission.CLIENT_SAMPLE_POSITION, true);
            int s = p.openBot("Miner", "example.com").sessionId();
            p.control(s, CoreMessages.WORLD_REPORT, new WorldReport(InterestRenderTest.NETHER, null));
            h.core.setPlayerContext(InterestRenderTest.PLAYER_WORLD);
            p.control(s, CoreMessages.SAMPLE_REQUEST, new SampleRequest(20f, true, false, false));
            p.sync();
            for (int i = 0; i < 10; i++) {
                feed(h, i);
                h.clock.advanceMs(100);
                h.tick();
            }
            p.sync();
            assertFalse(p.poll(f -> f.is(CoreMessages.CLIENT_SAMPLE)), "world mismatch");

            p.control(s, CoreMessages.WORLD_REPORT, new WorldReport(InterestRenderTest.BOT_WORLD, null));
            assertNotNull(nextSample(h, p, s, 3).position());

            h.core.attach().setChoice("miner@example.com", false);
            h.tick();
            p.sync();
            p.poll(f -> f.is(CoreMessages.CLIENT_SAMPLE));
            for (int i = 0; i < 10; i++) {
                feed(h, 10 + i);
                h.clock.advanceMs(100);
                h.tick();
            }
            p.sync();
            assertFalse(p.poll(f -> f.is(CoreMessages.CLIENT_SAMPLE)), "detached bots get no samples");
        }
    }

    @Test
    void rateIsCappedByThePlayerSetting() throws Exception {
        try (Harness h = new Harness(dir); FakePlugin p = FakePlugin.ready(h, "dev")) {
            h.core.devices().setPermission("dev", Permission.CLIENT_SAMPLE_POSITION, true);
            assertEquals(4f, h.core.samples().maxRateHz());
            h.core.samples().setMaxRateHz(100f);
            assertEquals(20f, h.core.samples().maxRateHz());
            h.core.samples().setMaxRateHz(2f);
            int s = p.openBot("Miner", "example.com").sessionId();
            p.control(s, CoreMessages.WORLD_REPORT, new WorldReport(InterestRenderTest.BOT_WORLD, null));
            h.core.setPlayerContext(InterestRenderTest.PLAYER_WORLD);
            p.control(s, CoreMessages.SAMPLE_REQUEST, new SampleRequest(20f, true, false, false));
            nextSample(h, p, s, 0);
            int sent = 0;
            // Five simulated seconds of ticks at 20 Hz with a moving player: at 2 Hz that is about 10 samples.
            for (int i = 1; i <= 100; i++) {
                feed(h, i);
                h.clock.advanceMs(50);
                h.tick();
                if (i % 20 == 0) {
                    p.sync();
                    while (p.poll(f -> f.is(CoreMessages.CLIENT_SAMPLE))) {
                        sent++;
                    }
                }
            }
            assertTrue(sent >= 8 && sent <= 11, "samples sent at 2 Hz over 5 s: " + sent);

            p.control(s, CoreMessages.SAMPLE_REQUEST, new SampleRequest(0f, false, false, false));
            p.sync();
            p.poll(f -> f.is(CoreMessages.CLIENT_SAMPLE));
            for (int i = 0; i < 20; i++) {
                feed(h, 200 + i);
                h.clock.advanceMs(100);
                h.tick();
            }
            p.sync();
            assertFalse(p.poll(f -> f.is(CoreMessages.CLIENT_SAMPLE)), "an empty request stops samples");
        }
    }
}
