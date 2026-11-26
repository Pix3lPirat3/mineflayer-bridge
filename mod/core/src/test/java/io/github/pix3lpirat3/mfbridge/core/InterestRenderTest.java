package io.github.pix3lpirat3.mfbridge.core;

import static io.github.pix3lpirat3.mfbridge.core.ObjectSyncTest.box;
import static io.github.pix3lpirat3.mfbridge.core.ObjectSyncTest.keys;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.pix3lpirat3.mfbridge.protocol.core.BoxData;
import io.github.pix3lpirat3.mfbridge.protocol.core.CoreKinds;
import io.github.pix3lpirat3.mfbridge.protocol.core.CoreMessages;
import io.github.pix3lpirat3.mfbridge.protocol.core.DepthMode;
import io.github.pix3lpirat3.mfbridge.protocol.core.Interest;
import io.github.pix3lpirat3.mfbridge.protocol.core.LayerData;
import io.github.pix3lpirat3.mfbridge.protocol.core.LayerVisibility;
import io.github.pix3lpirat3.mfbridge.protocol.core.Lifetime;
import io.github.pix3lpirat3.mfbridge.protocol.core.ObjectFull;
import io.github.pix3lpirat3.mfbridge.protocol.core.PlayerContext;
import io.github.pix3lpirat3.mfbridge.protocol.core.Singleplayer;
import io.github.pix3lpirat3.mfbridge.protocol.core.PresenceStatus;
import io.github.pix3lpirat3.mfbridge.protocol.core.Scope;
import io.github.pix3lpirat3.mfbridge.protocol.core.StyleData;
import io.github.pix3lpirat3.mfbridge.protocol.core.Vec3d;
import io.github.pix3lpirat3.mfbridge.protocol.core.WorldKey;
import io.github.pix3lpirat3.mfbridge.protocol.core.WorldReport;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InterestRenderTest {
    static final String OWNER = "bot:miner@example.com";
    static final String P = OWNER + "/";
    static final WorldKey BOT_WORLD = new WorldKey("example.com:25565", null, "minecraft:overworld");
    static final WorldKey PLAYER_WORLD = new WorldKey("Example.COM", null, "minecraft:overworld");
    static final WorldKey NETHER = new WorldKey("example.com", null, "minecraft:the_nether");

    @TempDir
    Path dir;

    static ObjectFull layered(String key, String layer, byte[] data) {
        return new ObjectFull(key, CoreKinds.BOX.id(), 1, layer, Lifetime.SESSION, Scope.GLOBAL, null, null, data);
    }

    @Test
    void interestFollowsPlayerContextAndLayerToggles() throws Exception {
        try (Harness h = new Harness(dir); FakePlugin p = FakePlugin.ready(h, "dev")) {
            int s = p.openBot("Miner", "example.com").sessionId();
            assertFalse(p.await(CoreMessages.PLAYER_CONTEXT, s).inWorld());
            Interest first = p.await(CoreMessages.INTEREST, s);
            assertFalse(first.attached());
            assertFalse(first.worldMatched());
            p.control(s, CoreMessages.WORLD_REPORT, new WorldReport(BOT_WORLD, null));
            p.upsert(s, FakePlugin.object(P + "viz/route", CoreKinds.LAYER, 1,
                    CoreKinds.LAYER.encode(new LayerData("paths", false))));
            Interest layered = p.await(CoreMessages.INTEREST, s);
            assertEquals(List.of(new LayerVisibility(OWNER, "viz", "route", false)), layered.layers());

            h.core.setPlayerContext(PLAYER_WORLD);
            PlayerContext pc = p.await(CoreMessages.PLAYER_CONTEXT, s);
            assertTrue(pc.inWorld());
            assertEquals(PLAYER_WORLD, pc.world());
            assertTrue(pc.attached());
            assertNull(pc.name());
            h.core.setPlayer("Steve", "8667ba71-b85a-4004-af54-457a9734eed7");
            PlayerContext named = p.await(CoreMessages.PLAYER_CONTEXT, s);
            assertEquals("Steve", named.name(), "the name goes to every session");
            assertEquals("8667ba71-b85a-4004-af54-457a9734eed7", named.uuid(), "and so does the profile id");
            h.core.setPlayerState(false, true, false);
            PlayerContext state = p.await(CoreMessages.PLAYER_CONTEXT, s);
            assertFalse(state.alive());
            assertTrue(state.away());
            assertFalse(state.windowFocused());
            h.core.setPlayerState(true, false, true);
            PlayerContext back = p.await(CoreMessages.PLAYER_CONTEXT, s);
            assertTrue(back.alive());
            assertNull(back.singleplayer(), "a server is no singleplayer world");
            h.core.setPlayerSingleplayer(true, 0);
            assertEquals(new Singleplayer(null), p.await(CoreMessages.PLAYER_CONTEXT, s).singleplayer());
            h.core.setPlayerSingleplayer(true, 52468);
            assertEquals(52468, p.await(CoreMessages.PLAYER_CONTEXT, s).singleplayer().lanPort(), "opened to LAN");
            h.core.setPlayerSingleplayer(false, 0);
            assertNull(p.await(CoreMessages.PLAYER_CONTEXT, s).singleplayer());
            Interest matched = p.await(CoreMessages.INTEREST, s);
            assertTrue(matched.attached());
            assertTrue(matched.worldMatched());

            h.core.layers().setVisible(OWNER, "viz", "route", true);
            Interest toggled = p.await(CoreMessages.INTEREST, s);
            assertEquals(List.of(new LayerVisibility(OWNER, "viz", "route", true)), toggled.layers());
            assertTrue(h.core.layers().isVisible(OWNER, "viz", "route"));
            assertEquals(1, h.core.layers().layers().size());
            assertEquals("paths", h.core.layers().layers().get(0).category());

            h.core.setPlayerContext(null);
            Interest away = p.await(CoreMessages.INTEREST, s);
            assertFalse(away.attached());
            assertFalse(away.worldMatched());
            assertFalse(p.await(CoreMessages.PLAYER_CONTEXT, s).inWorld());
        }
        // Layer overrides live in the player store and survive restarts.
        try (Harness h = new Harness(dir)) {
            assertTrue(Files.readString(dir.resolve("mineflayer_bridge/player/prefs.json")).contains("viz/route"));
            assertTrue(h.core.layers().isVisible(OWNER, "viz", "route"));
        }
    }

    @Test
    void renderSnapshotAppliesVisibilityRulesAndIsReused() throws Exception {
        try (Harness h = new Harness(dir); FakePlugin p = FakePlugin.ready(h, "dev")) {
            int s = p.openBot("Miner", "example.com").sessionId();
            p.control(s, CoreMessages.WORLD_REPORT, new WorldReport(BOT_WORLD, null));
            p.upsert(s,
                    FakePlugin.object(P + "viz/route", CoreKinds.LAYER, 1, CoreKinds.LAYER.encode(new LayerData(null, false))),
                    FakePlugin.object(P + "viz/~style/red", CoreKinds.STYLE, 1, CoreKinds.STYLE.encode(
                            new StyleData(0xFFFF0000, 0.5f, 3, DepthMode.XRAY, 0.35f, false))),
                    FakePlugin.object(P + "viz/plain", CoreKinds.BOX, 1, CoreKinds.BOX.encode(
                            new BoxData(new Vec3d(0, 0, 0), new Vec3d(1, 1, 1), "red"))),
                    layered(P + "viz/hidden", "route", box(2)),
                    FakePlugin.worldObject(P + "viz/here", CoreKinds.BOX, 1, BOT_WORLD, box(3)),
                    FakePlugin.worldObject(P + "viz/nether", CoreKinds.BOX, 1, NETHER, box(4)));
            p.sync();
            assertTrue(keys(h).isEmpty(), "detached under AUTO while the player is on the title screen");

            h.core.setPlayerContext(PLAYER_WORLD);
            h.tick();
            assertEquals(Set.of(P + "viz/plain", P + "viz/here"), keys(h));
            RenderEntry plain = ObjectSyncTest.entry(h, P + "viz/plain");
            assertEquals(0xFFFF0000, plain.style().color());
            assertEquals(OWNER, plain.owner());
            assertEquals("viz", plain.extension());
            assertEquals("plain", plain.localId());
            assertSame(CoreKinds.BOX, plain.kind());
            assertNull(ObjectSyncTest.entry(h, P + "viz/here").style());

            RenderSnapshot a = h.core.renderSnapshot();
            h.tick();
            h.tick();
            assertSame(a, h.core.renderSnapshot(), "unchanged store reuses the snapshot");

            h.core.layers().setVisible(OWNER, "viz", "route", true);
            h.tick();
            assertNotSame(a, h.core.renderSnapshot());
            assertTrue(h.core.renderSnapshot().version() > a.version());
            assertEquals(Set.of(P + "viz/plain", P + "viz/here", P + "viz/hidden"), keys(h));

            h.core.attach().setChoice("miner@example.com", false);
            h.tick();
            assertTrue(keys(h).isEmpty(), "detached bots render nothing");
            assertFalse(h.core.attach().isAttached("miner@example.com"));
            h.core.attach().setChoice("miner@example.com", null);
            h.tick();
            assertEquals(3, keys(h).size());

            h.core.attach().setPolicy(AttachPolicy.MANUAL);
            h.tick();
            assertTrue(keys(h).isEmpty());
        }
    }

    @Test
    void presenceTracksIdentityWorldAndStatus() throws Exception {
        try (Harness h = new Harness(dir)) {
            try (FakePlugin p = FakePlugin.ready(h, "dev")) {
                int s = p.openBot("Miner", "example.com").sessionId();
                p.control(s, CoreMessages.WORLD_REPORT, new WorldReport(BOT_WORLD, null));
                p.control(s, CoreMessages.PRESENCE_STATUS,
                        new PresenceStatus(Map.of("task", "mining")));
                p.sync();
                Presence pr = h.core.presence().get("miner@example.com");
                assertTrue(pr.connected());
                assertEquals(BOT_WORLD, pr.lastWorld());
                assertEquals(Map.of("task", "mining"), pr.status());
                assertFalse(h.core.presence().forget("miner@example.com"));
            }
            h.tickUntil("disconnected", () -> !h.core.presence().get("miner@example.com").connected());
            assertEquals(1, h.core.presence().all().size());
            assertTrue(h.core.presence().forget("miner@example.com"));
        }
    }
}
