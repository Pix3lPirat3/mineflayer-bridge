package io.github.pix3lpirat3.mfbridge.core.layout;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.pix3lpirat3.mfbridge.core.AttachPolicy;
import io.github.pix3lpirat3.mfbridge.core.persist.PlayerPrefs;
import io.github.pix3lpirat3.mfbridge.protocol.core.HudAnchor;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

class HudLayoutsTest {
    private static final String MINER = "bot:miner@example.com/vitals/~hud/main";
    private static final String DIGGER = "bot:digger@example.com/vitals/~hud/main";
    private static final String OTHER = "bot:miner@example.com/tasks/~hud/main";

    @TempDir
    Path dir;

    private PlayerPrefs prefs() {
        return PlayerPrefs.load(dir.resolve("prefs.json"), AttachPolicy.AUTO, (f, b) -> {
            try {
                Files.write(f, b);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }, LoggerFactory.getLogger("test"));
    }

    @Test
    void keysAreOwnerSpecificWithAnExtensionFallback() {
        assertArrayEquals(new String[] {"bot:miner@example.com|vitals|main", "*|vitals|main"}, HudLayouts.keys(MINER));
        assertArrayEquals(new String[] {"ctl:dev/dash|ui|stats", "*|ui|stats"}, HudLayouts.keys("ctl:dev/dash/ui/~hud/stats"));
        assertNull(HudLayouts.keys("nonsense"));
    }

    @Test
    void savedLayoutsResolveOwnerFirstThenExtensionAndSurviveReload() {
        HudLayouts layouts = new HudLayouts(prefs());
        assertNull(layouts.get(MINER));
        long v0 = layouts.version();
        layouts.set(MINER, HudLayout.free(0.25, 0.5).withWidth(150));
        assertNotEquals(v0, layouts.version());

        assertEquals(new HudLayout(null, 0.25, 0.5, 150, false), layouts.get(MINER));
        assertEquals(layouts.get(MINER), layouts.get(DIGGER), "another bot of the same extension uses the fallback");
        assertNull(layouts.get(OTHER), "another extension's panel is unaffected");

        layouts.set(DIGGER, HudLayout.anchored(HudAnchor.BOTTOM_RIGHT).withHidden(true));
        assertEquals(new HudLayout(null, 0.25, 0.5, 150, false), layouts.get(MINER), "the owner's own layout wins");
        assertTrue(layouts.get(DIGGER).hidden());

        HudLayouts reloaded = new HudLayouts(prefs());
        assertEquals(new HudLayout(null, 0.25, 0.5, 150, false), reloaded.get(MINER));
        assertEquals(HudAnchor.BOTTOM_RIGHT, reloaded.get(DIGGER).anchor());

        reloaded.reset(DIGGER);
        assertEquals(new HudLayout(null, 0.25, 0.5, 150, false), reloaded.get(MINER));
        assertNull(reloaded.get("bot:third@example.com/vitals/~hud/main"), "reset also drops the fallback");
        reloaded.resetAll();
        assertNull(reloaded.get(MINER));
    }

    @Test
    void layoutValuesAreClamped() {
        HudLayout l = new HudLayout(null, 2, -1, -5, false);
        assertEquals(1d, l.x());
        assertEquals(0d, l.y());
        assertEquals(0, l.width());
        assertEquals(0d, new HudLayout(null, Double.NaN, 0, 0, false).x());
    }
}
