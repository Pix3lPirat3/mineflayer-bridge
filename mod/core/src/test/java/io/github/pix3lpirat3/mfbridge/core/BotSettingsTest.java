package io.github.pix3lpirat3.mfbridge.core;

import static io.github.pix3lpirat3.mfbridge.core.InputTestSupport.P;
import static io.github.pix3lpirat3.mfbridge.core.InputTestSupport.obj;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.pix3lpirat3.mfbridge.protocol.core.CoreKinds;
import io.github.pix3lpirat3.mfbridge.protocol.core.CoreMessages;
import io.github.pix3lpirat3.mfbridge.protocol.core.SettingDef;
import io.github.pix3lpirat3.mfbridge.protocol.core.SettingsData;
import io.github.pix3lpirat3.mfbridge.protocol.core.SettingsValues;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BotSettingsTest {
    static final String MAIN = P + "miner/~settings/default";
    static final String EXTRA = P + "miner/~settings/extra";

    @TempDir
    Path dir;

    static SettingsData main() {
        return new SettingsData("Miner", List.of(
                new SettingDef("radius", "int", "Mining radius", null, 32, 4.0, 128.0, null),
                new SettingDef("replant", "bool", null, null, true, null, null, null),
                new SettingDef("color", "color", "Path color", null, "#3b82f6", null, null, null),
                new SettingDef("speed", "float", null, null, 0.5, 0.0, 1.0, null),
                new SettingDef("mode", "choice", null, null, null, null, null, List.of("fast", "safe"))));
    }

    static SettingsData extra() {
        return new SettingsData(null, List.of(new SettingDef("name", "string", null, null, "Steve", null, 16.0, null)));
    }

    static int declare(FakePlugin p) {
        int s = p.openBot("Miner", "example.com").sessionId();
        p.upsert(s, obj(MAIN, CoreKinds.SETTINGS, main()), obj(EXTRA, CoreKinds.SETTINGS, extra()));
        return s;
    }

    /** Awaits settings.values for both groups, which arrive in one tick and must not coalesce. */
    static Map<String, Map<String, Object>> both(FakePlugin p, int s) {
        Map<String, Map<String, Object>> out = new HashMap<>();
        while (out.size() < 2) {
            SettingsValues v = p.await(CoreMessages.SETTINGS_VALUES, s);
            out.put(v.settings(), v.values());
        }
        return out;
    }

    @Test
    void valuesAreSentOnDeclareAndOnEveryValidChange() throws Exception {
        try (Harness h = ResumeTest.always(dir, b -> {}); FakePlugin p = FakePlugin.ready(h, "dev")) {
            int s = declare(p);
            Map<String, Map<String, Object>> first = both(p, s);
            assertEquals(Map.of("radius", 32L, "replant", true, "color", 0xFF3B82F6L, "speed", 0.5), first.get(MAIN),
                    "defaults, with the colour normalized to 0xAARRGGBB");
            assertEquals(Map.of("name", "Steve"), first.get(EXTRA));

            h.core.setSettingValue(MAIN, "radius", 64);
            assertEquals(64L, p.await(CoreMessages.SETTINGS_VALUES, s).values().get("radius"));
            h.core.setSettingValue(MAIN, "mode", "safe");
            h.core.setSettingValue(MAIN, "color", 0x80FF0000L);
            SettingsValues changed = p.await(CoreMessages.SETTINGS_VALUES, s);
            assertEquals(MAIN, changed.settings());
            assertEquals("safe", changed.values().get("mode"));
            assertEquals(0x80FF0000L, changed.values().get("color"));

            assertThrows(IllegalArgumentException.class, () -> h.core.setSettingValue(MAIN, "radius", 200));
            assertThrows(IllegalArgumentException.class, () -> h.core.setSettingValue(MAIN, "radius", 2.5));
            assertThrows(IllegalArgumentException.class, () -> h.core.setSettingValue(MAIN, "replant", "yes"));
            assertThrows(IllegalArgumentException.class, () -> h.core.setSettingValue(MAIN, "speed", -1));
            assertThrows(IllegalArgumentException.class, () -> h.core.setSettingValue(MAIN, "mode", "slow"));
            assertThrows(IllegalArgumentException.class, () -> h.core.setSettingValue(MAIN, "color", "red"));
            assertThrows(IllegalArgumentException.class, () -> h.core.setSettingValue(EXTRA, "name", "x".repeat(17)));
            assertThrows(IllegalArgumentException.class, () -> h.core.setSettingValue(MAIN, "depth", 3));
            assertThrows(IllegalArgumentException.class, () -> h.core.setSettingValue(P + "miner/~settings/none", "a", 1));
            p.sync();
            assertFalse(p.poll(f -> f.is(CoreMessages.SETTINGS_VALUES)), "rejected values send nothing");

            h.tick();
            assertEquals(64L, h.core.declarations().settings().get(0).values().get("radius"),
                    "the declaration snapshot carries current values");
        }
    }

    @Test
    void valuesPersistPerBotAcrossRestarts() throws Exception {
        try (Harness h = ResumeTest.always(dir, b -> {}); FakePlugin p = FakePlugin.ready(h, "dev")) {
            int s = declare(p);
            both(p, s);
            h.core.setSettingValue(MAIN, "radius", 100);
            h.core.setSettingValue(MAIN, "color", "#ff000080");
            h.core.setSettingValue(MAIN, "replant", true);
            h.core.setSettingValue(EXTRA, "name", "Alex");
            p.await(CoreMessages.SETTINGS_VALUES, s);
        }
        String file = Files.readString(dir.resolve("mineflayer_bridge/player/settings.json"));
        assertTrue(file.contains(MAIN) && file.contains("radius"), file);
        assertFalse(file.contains("replant"), "values equal to the default are not stored: " + file);

        try (Harness h = ResumeTest.always(dir, b -> {}); FakePlugin p = FakePlugin.ready(h, "dev")) {
            int s = declare(p);
            Map<String, Map<String, Object>> restored = both(p, s);
            assertEquals(100L, restored.get(MAIN).get("radius"));
            assertEquals(0x80FF0000L, restored.get(MAIN).get("color"));
            assertEquals(true, restored.get(MAIN).get("replant"));
            assertEquals("Alex", restored.get(EXTRA).get("name"));

            // A stored value that no longer fits the declaration falls back to the default.
            p.upsert(s, FakePlugin.object(MAIN, CoreKinds.SETTINGS, 2, CoreKinds.SETTINGS.encode(new SettingsData(null,
                    List.of(new SettingDef("radius", "int", null, null, 8, 1.0, 16.0, null))))));
            assertEquals(Map.of("radius", 8L), p.await(CoreMessages.SETTINGS_VALUES, s).values());
        }
    }
}
