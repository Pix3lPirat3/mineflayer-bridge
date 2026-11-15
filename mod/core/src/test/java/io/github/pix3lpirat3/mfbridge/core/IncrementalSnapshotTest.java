package io.github.pix3lpirat3.mfbridge.core;

import static io.github.pix3lpirat3.mfbridge.core.ObjectSyncTest.box;
import static io.github.pix3lpirat3.mfbridge.core.ObjectSyncTest.entry;
import static io.github.pix3lpirat3.mfbridge.core.ObjectSyncTest.keys;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.pix3lpirat3.mfbridge.core.decl.DeclarationSnapshot;
import io.github.pix3lpirat3.mfbridge.protocol.core.BoxData;
import io.github.pix3lpirat3.mfbridge.protocol.core.CoreKinds;
import io.github.pix3lpirat3.mfbridge.protocol.core.CoreMessages;
import io.github.pix3lpirat3.mfbridge.protocol.core.DepthMode;
import io.github.pix3lpirat3.mfbridge.protocol.core.HudAnchor;
import io.github.pix3lpirat3.mfbridge.protocol.core.HudPanelData;
import io.github.pix3lpirat3.mfbridge.protocol.core.HudTextData;
import io.github.pix3lpirat3.mfbridge.protocol.core.ObjRemove;
import io.github.pix3lpirat3.mfbridge.protocol.core.StyleData;
import io.github.pix3lpirat3.mfbridge.protocol.core.VarsData;
import io.github.pix3lpirat3.mfbridge.protocol.core.Vec3d;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A write touches only the snapshot its kind feeds, and only its own entry in it. */
class IncrementalSnapshotTest {
    static final String P = "bot:miner@example.com/";
    static final String Q = "bot:smith@example.com/";

    @TempDir
    Path dir;

    private static List<String> keysOf(List<RenderEntry> entries) {
        return entries.stream().map(RenderEntry::key).toList();
    }

    @Test
    void aWriteReplacesOnlyItsOwnEntry() throws Exception {
        try (Harness h = ObjectSyncTest.always(dir); FakePlugin p = FakePlugin.ready(h, "dev")) {
            int s = p.openBot("Miner", "example.com").sessionId();
            p.upsert(s, FakePlugin.object(P + "e/a", CoreKinds.BOX, 1, box(1)),
                    FakePlugin.object(P + "e/b", CoreKinds.BOX, 1, box(2)),
                    FakePlugin.object(P + "e/c", CoreKinds.BOX, 1, box(3)));
            h.tickUntil("three boxes", () -> keys(h).size() == 3);
            RenderSnapshot first = h.core.renderSnapshot();
            RenderEntry a = entry(h, P + "e/a");
            RenderEntry c = entry(h, P + "e/c");

            p.upsert(s, FakePlugin.object(P + "e/b", CoreKinds.BOX, 2, box(9)));
            h.tickUntil("b changed", () -> entry(h, P + "e/b").rev() == 2);
            RenderSnapshot second = h.core.renderSnapshot();
            assertSame(a, entry(h, P + "e/a"), "untouched entries keep their instance");
            assertSame(c, entry(h, P + "e/c"));
            RenderSnapshot.Change change = second.changesSince(first.version());
            assertNotNull(change, "the snapshot right before is known");
            assertEquals(List.of(P + "e/b"), keysOf(change.upserted()));
            assertTrue(change.removed().isEmpty());
            assertNull(second.changesSince(first.version() - 1), "older followers start over");
            assertSame(entry(h, P + "e/b"), second.find(P + "e/b"));
            assertNull(second.find(P + "e/zzz"));

            p.reliable(s, CoreMessages.OBJ_REMOVE, new ObjRemove(List.of(P + "e/a"), List.of()));
            h.tickUntil("a removed", () -> keys(h).equals(Set.of(P + "e/b", P + "e/c")));
            RenderSnapshot.Change gone = h.core.renderSnapshot().changesSince(second.version());
            assertNotNull(gone);
            assertEquals(List.of(P + "e/a"), keysOf(gone.removed()));
            assertTrue(gone.upserted().isEmpty());
        }
    }

    @Test
    void variablesAndDeclarationsLeaveTheRenderSnapshotAlone() throws Exception {
        try (Harness h = ObjectSyncTest.always(dir); FakePlugin p = FakePlugin.ready(h, "dev")) {
            int s = p.openBot("Miner", "example.com").sessionId();
            p.upsert(s, FakePlugin.object(P + "e/a", CoreKinds.BOX, 1, box(1)),
                    FakePlugin.object(P + "e/~hud/main", CoreKinds.HUD_PANEL, 1,
                            CoreKinds.HUD_PANEL.encode(new HudPanelData("Miner", HudAnchor.TOP_RIGHT, 1, false))),
                    FakePlugin.object(P + "e/~hud/main/t", CoreKinds.HUD_TEXT, 1,
                            CoreKinds.HUD_TEXT.encode(new HudTextData("main", 0, "one", null, null))));
            h.tickUntil("box and panel", () -> keys(h).size() == 1 && !h.core.declarations().hud().isEmpty());
            assertEquals(Set.of(P + "e/a"), keys(h), "declarations are not render entries");
            RenderSnapshot render = h.core.renderSnapshot();
            DeclarationSnapshot decl = h.core.declarations();

            p.upsert(s, FakePlugin.object(P + "e/~vars", CoreKinds.VARS, 1,
                    CoreKinds.VARS.encode(new VarsData(Map.of("done", 3L)))));
            h.tickUntil("variables", () -> h.core.variables().version() > 0);
            h.tick();
            assertSame(render, h.core.renderSnapshot(), "a variable write publishes no render snapshot");
            assertSame(decl, h.core.declarations(), "nor a declaration snapshot");

            p.upsert(s, FakePlugin.object(P + "e/~hud/main/t", CoreKinds.HUD_TEXT, 2,
                    CoreKinds.HUD_TEXT.encode(new HudTextData("main", 0, "two", null, null))));
            h.tickUntil("hud text", () -> h.core.declarations() != decl);
            assertSame(render, h.core.renderSnapshot(), "a HUD write publishes no render snapshot");

            DeclarationSnapshot after = h.core.declarations();
            p.upsert(s, FakePlugin.object(P + "e/a", CoreKinds.BOX, 2, box(5)));
            h.tickUntil("box moved", () -> entry(h, P + "e/a").rev() == 2);
            assertSame(after, h.core.declarations(), "a box write publishes no declaration snapshot");
        }
    }

    @Test
    void aStyleWriteReachesTheObjectsThatUseIt() throws Exception {
        try (Harness h = ObjectSyncTest.always(dir); FakePlugin p = FakePlugin.ready(h, "dev")) {
            int s = p.openBot("Miner", "example.com").sessionId();
            p.upsert(s, FakePlugin.object(P + "e/styled", CoreKinds.BOX, 1, CoreKinds.BOX.encode(
                            new BoxData(new Vec3d(0, 0, 0), new Vec3d(1, 1, 1), "red"))),
                    FakePlugin.object(P + "e/plain", CoreKinds.BOX, 1, box(2)));
            h.tickUntil("two boxes", () -> keys(h).size() == 2);
            assertNull(entry(h, P + "e/styled").style());
            RenderEntry plain = entry(h, P + "e/plain");

            p.upsert(s, FakePlugin.object(P + "e/~style/red", CoreKinds.STYLE, 1, CoreKinds.STYLE.encode(
                    new StyleData(0xFFFF0000, 0.5f, 3, DepthMode.XRAY, 0.35f, false))));
            h.tickUntil("styled", () -> entry(h, P + "e/styled").style() != null);
            assertEquals(0xFFFF0000, entry(h, P + "e/styled").style().color());
            assertSame(plain, entry(h, P + "e/plain"), "objects without the style are not rebuilt");

            RenderEntry styled = entry(h, P + "e/styled");
            p.reliable(s, CoreMessages.OBJ_REMOVE, new ObjRemove(List.of(P + "e/~style/red"), List.of()));
            h.tickUntil("unstyled", () -> entry(h, P + "e/styled").style() == null);
            assertNotSame(styled, entry(h, P + "e/styled"));
        }
    }

    @Test
    void oneOwnersDeclarationsAreNotRebuiltForAnother() throws Exception {
        try (Harness h = ObjectSyncTest.always(dir); FakePlugin p = FakePlugin.ready(h, "dev")) {
            int miner = p.openBot("Miner", "example.com").sessionId();
            int smith = p.openBot("Smith", "example.com").sessionId();
            p.upsert(miner, FakePlugin.object(P + "e/~hud/main", CoreKinds.HUD_PANEL, 1,
                    CoreKinds.HUD_PANEL.encode(new HudPanelData("Miner", HudAnchor.TOP_RIGHT, 1, false))));
            p.upsert(smith, FakePlugin.object(Q + "e/~hud/main", CoreKinds.HUD_PANEL, 1,
                    CoreKinds.HUD_PANEL.encode(new HudPanelData("Smith", HudAnchor.TOP_LEFT, 1, false))));
            h.tickUntil("two panels", () -> h.core.declarations().hud().size() == 2);
            var minerPanel = h.core.declarations().hud().get("bot:miner@example.com").get(0);

            p.upsert(smith, FakePlugin.object(Q + "e/~hud/main", CoreKinds.HUD_PANEL, 2,
                    CoreKinds.HUD_PANEL.encode(new HudPanelData("Forge", HudAnchor.TOP_LEFT, 1, false))));
            h.tickUntil("smith renamed", () -> "Forge".equals(
                    h.core.declarations().hud().get("bot:smith@example.com").get(0).data().title()));
            assertSame(minerPanel, h.core.declarations().hud().get("bot:miner@example.com").get(0),
                    "the other owner's declarations are kept as they were");
        }
    }
}
