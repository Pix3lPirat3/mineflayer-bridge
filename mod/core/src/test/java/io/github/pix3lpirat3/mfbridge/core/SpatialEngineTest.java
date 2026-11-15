package io.github.pix3lpirat3.mfbridge.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.pix3lpirat3.mfbridge.core.history.HistoryEntry;
import io.github.pix3lpirat3.mfbridge.core.spatial.SpatialIndex;
import io.github.pix3lpirat3.mfbridge.core.store.PatchApplier;
import io.github.pix3lpirat3.mfbridge.core.world.ServerKeys;
import io.github.pix3lpirat3.mfbridge.protocol.core.CoreKinds;
import io.github.pix3lpirat3.mfbridge.protocol.core.Lifetime;
import io.github.pix3lpirat3.mfbridge.protocol.core.ObjectFull;
import io.github.pix3lpirat3.mfbridge.protocol.core.Scope;
import io.github.pix3lpirat3.mfbridge.protocol.core.SharedCreate;
import io.github.pix3lpirat3.mfbridge.protocol.core.SharedOpBody;
import io.github.pix3lpirat3.mfbridge.protocol.core.SharedUpdate;
import io.github.pix3lpirat3.mfbridge.protocol.core.Vec3i;
import io.github.pix3lpirat3.mfbridge.protocol.core.WorldKey;
import io.github.pix3lpirat3.mfbridge.protocol.core.ZoneBox;
import io.github.pix3lpirat3.mfbridge.protocol.core.ZoneData;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SpatialEngineTest {
    static final WorldKey W = new WorldKey("example.com", null, "minecraft:overworld");
    static final String KEY = "shared/bridge:zones/base";

    @TempDir
    Path dir;

    static byte[] zone(String name, int size) {
        return CoreKinds.ZONE.encode(new ZoneData(name, List.of(new ZoneBox(new Vec3i(0, 60, 0), new Vec3i(size, 70, size))),
                null, null, null, List.of("base"), 0xFF3366FF, 0.1f, true, true));
    }

    @Test
    void recordedSharedEditsUndoAcrossRestartsAndFeedTheIndex() throws Exception {
        try (Harness h = new Harness(dir)) {
            h.core.setPlayerContext(W);
            h.tickUntil("stores", h.core::storesLoaded);
            h.core.history().sharedOp("Create zone", new SharedOpBody.Create(new SharedCreate(new ObjectFull(KEY,
                    CoreKinds.ZONE.id(), 0, null, Lifetime.PERSISTENT, Scope.WORLD, W, null, zone("Base", 9)))));
            h.core.history().sharedOp("Resize zone", new SharedOpBody.Update(new SharedUpdate(KEY, 1,
                    PatchApplier.fullPatch(zone("Base", 19)))));
            h.tick();
            SpatialIndex idx = h.core.spatial();
            assertEquals(1, idx.containing(15, 65, 15).size());
            assertEquals("Base", idx.byTag("base").getFirst().name());
            HistoryEntry undone = h.core.history().undo().join();
            assertEquals("Resize zone", undone.label());
            h.tick();
            assertTrue(h.core.spatial().containing(15, 65, 15).isEmpty());
            assertEquals(1, h.core.spatial().containing(5, 65, 5).size());
        }
        assertTrue(Files.exists(dir.resolve("mineflayer_bridge/data").resolve(ServerKeys.directoryName("example.com:25565"))
                .resolve("history.bin")));

        try (Harness h = new Harness(dir)) {
            h.core.setPlayerContext(W);
            h.tickUntil("history", () -> h.core.storesLoaded() && h.core.history().entries().size() == 2);
            assertTrue(h.core.history().canRedo());
            h.core.history().redo().join();
            h.tick();
            assertEquals(1, h.core.spatial().containing(15, 65, 15).size());
            h.core.history().undo().join();
            h.core.history().undo().join();
            h.tick();
            assertNull(h.core.spatial().get(KEY));
            assertFalse(h.core.history().canUndo());
            h.core.history().redo().join();
            h.tick();
            assertNotNull(h.core.spatial().get(KEY));
        }
    }
}
