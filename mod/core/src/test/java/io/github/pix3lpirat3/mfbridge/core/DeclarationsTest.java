package io.github.pix3lpirat3.mfbridge.core;

import static io.github.pix3lpirat3.mfbridge.core.InputTestSupport.BOT_WORLD;
import static io.github.pix3lpirat3.mfbridge.core.InputTestSupport.IDENTITY;
import static io.github.pix3lpirat3.mfbridge.core.InputTestSupport.NETHER;
import static io.github.pix3lpirat3.mfbridge.core.InputTestSupport.OWNER;
import static io.github.pix3lpirat3.mfbridge.core.InputTestSupport.P;
import static io.github.pix3lpirat3.mfbridge.core.InputTestSupport.PLAYER_WORLD;
import static io.github.pix3lpirat3.mfbridge.core.InputTestSupport.obj;
import static io.github.pix3lpirat3.mfbridge.core.InputTestSupport.worldObj;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.pix3lpirat3.mfbridge.core.decl.CommandDecl;
import io.github.pix3lpirat3.mfbridge.core.decl.DeclarationSnapshot;
import io.github.pix3lpirat3.mfbridge.core.decl.HudPanel;
import io.github.pix3lpirat3.mfbridge.core.decl.HudWidget;
import io.github.pix3lpirat3.mfbridge.core.decl.SettingsDecl;
import io.github.pix3lpirat3.mfbridge.protocol.core.ActionData;
import io.github.pix3lpirat3.mfbridge.protocol.core.BossbarData;
import io.github.pix3lpirat3.mfbridge.protocol.core.CommandData;
import io.github.pix3lpirat3.mfbridge.protocol.core.CoreKinds;
import io.github.pix3lpirat3.mfbridge.protocol.core.CoreMessages;
import io.github.pix3lpirat3.mfbridge.protocol.core.HudAnchor;
import io.github.pix3lpirat3.mfbridge.protocol.core.HudBarData;
import io.github.pix3lpirat3.mfbridge.protocol.core.HudKvData;
import io.github.pix3lpirat3.mfbridge.protocol.core.HudPanelData;
import io.github.pix3lpirat3.mfbridge.protocol.core.HudTextData;
import io.github.pix3lpirat3.mfbridge.protocol.core.KeyActivation;
import io.github.pix3lpirat3.mfbridge.protocol.core.KeybindData;
import io.github.pix3lpirat3.mfbridge.protocol.core.KvEntry;
import io.github.pix3lpirat3.mfbridge.protocol.core.Scope;
import io.github.pix3lpirat3.mfbridge.protocol.core.SessionClose;
import io.github.pix3lpirat3.mfbridge.protocol.core.SettingDef;
import io.github.pix3lpirat3.mfbridge.protocol.core.SettingsData;
import io.github.pix3lpirat3.mfbridge.protocol.core.TargetSpec;
import io.github.pix3lpirat3.mfbridge.protocol.core.ToolData;
import io.github.pix3lpirat3.mfbridge.protocol.core.ToolInput;
import io.github.pix3lpirat3.mfbridge.protocol.core.WorldReport;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DeclarationsTest {
    static final String HUD = P + "status/~hud/";

    @TempDir
    Path dir;

    static HudTextData text(String panel, int order, String text) {
        return new HudTextData(panel, order, text, null, null);
    }

    static List<String> widgetKeys(HudPanel panel) {
        List<String> out = new ArrayList<>();
        for (HudWidget w : panel.widgets()) {
            out.add(w.key());
        }
        return out;
    }

    @Test
    void snapshotHoldsEveryDeclarationKindWithHudPanelsGroupedAndSorted() throws Exception {
        try (Harness h = ResumeTest.always(dir, b -> {}); FakePlugin p = FakePlugin.ready(h, "dev")) {
            int s = p.openBot("Miner", "example.com").sessionId();
            CommandData mine = new CommandData("mine", null, List.of());
            ActionData deposit = new ActionData("Deposit", null, new TargetSpec("block", null, null), 0);
            KeybindData stop = new KeybindData("Stop", List.of("key.keyboard.g"), "ingame", KeyActivation.PRESS, 0, true);
            ToolData mark = new ToolData("Mark", null, List.of("block"), List.of(ToolInput.PRIMARY));
            SettingsData settings = new SettingsData("Miner", List.of(
                    new SettingDef("radius", "int", "Radius", null, 32, 4.0, 128.0, null),
                    new SettingDef("note", "string", null, null, null, null, null, null)));
            HudPanelData main = new HudPanelData("Miner", HudAnchor.TOP_RIGHT, 1, false);
            HudPanelData aside = new HudPanelData(null, HudAnchor.TOP_LEFT, -1, true);
            HudBarData bar = new HudBarData("main", 2, "Iron", 34, 0, 64, null);
            HudKvData kv = new HudKvData("main", 2, List.of(new KvEntry("Task", "Mining", null)));
            BossbarData boss = new BossbarData("Quarry", 0.25f, "blue", "progress");
            p.upsert(s,
                    obj(P + "miner/~cmd/mine", CoreKinds.COMMAND, mine),
                    obj(P + "miner/~action/deposit", CoreKinds.ACTION, deposit),
                    obj(P + "miner/~key/stop", CoreKinds.KEYBIND, stop),
                    obj(P + "miner/~tool/mark", CoreKinds.TOOL, mark),
                    obj(P + "miner/~settings/default", CoreKinds.SETTINGS, settings),
                    obj(HUD + "main", CoreKinds.HUD_PANEL, main),
                    obj(HUD + "aside", CoreKinds.HUD_PANEL, aside),
                    obj(HUD + "main/bar", CoreKinds.HUD_BAR, bar),
                    obj(HUD + "main/kv", CoreKinds.HUD_KV, kv),
                    obj(HUD + "main/title", CoreKinds.HUD_TEXT, text("main", 1, "Mining")),
                    obj(HUD + "main/stray", CoreKinds.HUD_TEXT, text("aside", 0, "panel field disagrees with key")),
                    obj(HUD + "orphan/w", CoreKinds.HUD_TEXT, text("orphan", 0, "no such panel")),
                    obj(P + "miner/~bossbar/quarry", CoreKinds.BOSSBAR, boss),
                    FakePlugin.object(P + "viz/box", CoreKinds.BOX, 1, ObjectSyncTest.box(1)));
            p.sync();
            h.tick();
            DeclarationSnapshot d = h.core.declarations();

            assertEquals(List.of(new CommandDecl(P + "miner/~cmd/mine", "Miner", IDENTITY, Scope.GLOBAL, mine)),
                    d.commands());
            assertEquals(deposit, d.actions().get(0).data());
            assertEquals("Miner", d.actions().get(0).ownerName());
            assertEquals(stop, d.keybinds().get(0).data());
            assertEquals(P + "miner/~tool/mark", d.tools().get(0).key());
            SettingsDecl group = d.settings().get(0);
            assertEquals(CoreKinds.SETTINGS.decode(CoreKinds.SETTINGS.encode(settings)), group.data(),
                    "defaults decode as Long");
            assertEquals(Map.of("radius", 32L), group.values(), "defaults; settings without one are absent");
            assertEquals(boss, d.bossbars().get(0).data());

            assertEquals(List.of(OWNER), List.copyOf(d.hud().keySet()));
            List<HudPanel> panels = d.hud().get(OWNER);
            assertEquals(List.of(HUD + "aside", HUD + "main"), List.of(panels.get(0).key(), panels.get(1).key()),
                    "panels sorted by order");
            assertTrue(panels.get(0).widgets().isEmpty(), "a widget whose panel field disagrees with its key is left out");
            assertEquals(List.of(HUD + "main/title", HUD + "main/bar", HUD + "main/kv"), widgetKeys(panels.get(1)),
                    "widgets sorted by order, then key");
            assertEquals(bar, panels.get(1).widgets().get(1).data());
            assertEquals("Miner", panels.get(1).ownerName());
        }
    }

    @Test
    void visibilityFollowsAttachAndWorldAndUnchangedContentReusesTheInstance() throws Exception {
        try (Harness h = new Harness(dir); FakePlugin p = FakePlugin.ready(h, "dev")) {
            int s = p.openBot("Miner", "example.com").sessionId();
            p.control(s, CoreMessages.WORLD_REPORT, new WorldReport(BOT_WORLD, null));
            p.upsert(s,
                    obj(P + "miner/~cmd/mine", CoreKinds.COMMAND, new CommandData("mine", null, List.of())),
                    worldObj(P + "miner/~action/here", CoreKinds.ACTION, BOT_WORLD,
                            new ActionData("Here", null, new TargetSpec("block", null, null), 0)),
                    worldObj(P + "miner/~tool/nether", CoreKinds.TOOL, NETHER,
                            new ToolData("Nether", null, List.of(), List.of())));
            p.sync();
            h.tick();
            assertEquals(1, h.core.declarations().commands().size(), "a detached bot can still be given orders");
            assertTrue(h.core.declarations().actions().isEmpty(), "but shows nothing while detached on the title screen");

            h.core.setPlayerContext(PLAYER_WORLD);
            h.tick();
            DeclarationSnapshot a = h.core.declarations();
            assertEquals(1, a.commands().size());
            assertEquals(1, a.actions().size());
            assertTrue(a.tools().isEmpty(), "world-scoped objects of another world are hidden");

            h.tick();
            p.upsert(s, FakePlugin.object(P + "viz/box", CoreKinds.BOX, 1, ObjectSyncTest.box(2)));
            p.sync();
            h.tick();
            assertSame(a, h.core.declarations(), "unrelated changes keep the instance");

            h.core.attach().setChoice(IDENTITY, false);
            h.tick();
            DeclarationSnapshot detached = h.core.declarations();
            assertNotSame(a, detached);
            assertTrue(detached.version() > a.version());
            assertEquals(1, detached.commands().size(), "its global command stays");
            assertTrue(detached.actions().isEmpty());

            h.core.attach().setChoice(IDENTITY, true);
            h.core.setPlayerContext(NETHER);
            h.tick();
            DeclarationSnapshot elsewhere = h.core.declarations();
            assertEquals(1, elsewhere.commands().size(), "global declarations stay while attached");
            assertTrue(elsewhere.actions().isEmpty());
            assertEquals(1, elsewhere.tools().size());

            p.control(s, CoreMessages.SESSION_CLOSE, new SessionClose("bye"));
            p.sync();
            h.tick();
            assertTrue(h.core.declarations().commands().isEmpty(), "ended sessions declare nothing");
        }
    }
}
