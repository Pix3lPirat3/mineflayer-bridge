package io.github.pix3lpirat3.mfbridge.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.pix3lpirat3.mfbridge.core.world.ServerKeys;
import io.github.pix3lpirat3.mfbridge.protocol.core.CoreMessages;
import io.github.pix3lpirat3.mfbridge.protocol.core.ErrorMessage;
import io.github.pix3lpirat3.mfbridge.protocol.core.WorldKey;
import io.github.pix3lpirat3.mfbridge.protocol.core.WorldReport;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The server names bots send are checked, and naming a server costs nothing on disk. */
class WorldStoreTest {
    @TempDir
    Path dir;

    static WorldKey world(String server) {
        return new WorldKey(server, null, "minecraft:overworld");
    }

    static long storeDirs(Path dir) throws IOException {
        Path data = dir.resolve("mineflayer_bridge/data");
        if (!Files.exists(data)) {
            return 0;
        }
        try (Stream<Path> s = Files.list(data)) {
            return s.count();
        }
    }

    @Test
    void badServerNamesAreRefused() throws Exception {
        try (Harness h = new Harness(dir); FakePlugin p = FakePlugin.ready(h, "dev")) {
            int s = p.openBot("Miner", "example.com").sessionId();
            List<WorldKey> bad = List.of(world(""), world("../../etc"), world("a b"), world("x".repeat(256)),
                    world("host\nname"), new WorldKey("example.com", null, "minecraft:over\u0000world"),
                    new WorldKey("example.com", "x".repeat(256), "minecraft:overworld"));
            for (WorldKey w : bad) {
                p.control(s, CoreMessages.WORLD_REPORT, new WorldReport(w, null));
                ErrorMessage e = p.await(CoreMessages.ERROR, s);
                assertEquals("invalid_message", e.error().code(), w.toString());
                assertEquals(CoreMessages.WORLD_REPORT.id(), e.relatesTo());
            }
            for (String ok : List.of("example.com", "Example.COM:25570", "[2001:db8::1]:25565", "my_server-2.lan", "203.0.113.7")) {
                p.control(s, CoreMessages.WORLD_REPORT, new WorldReport(world(ok), null));
            }
            p.sync();
            assertFalse(p.poll(f -> f.is(CoreMessages.ERROR)));
        }
    }

    @Test
    void namingServersCreatesNothingOnDiskAndOnlySoManyAreLoaded() throws Exception {
        try (Harness h = new Harness(dir); FakePlugin p = FakePlugin.ready(h, "dev")) {
            int s = p.openBot("Miner", "example.com").sessionId();
            // The global store is one of the 64; 63 more may be named.
            for (int i = 0; i < 63; i++) {
                p.control(s, CoreMessages.WORLD_REPORT, new WorldReport(world("host" + i + ".example"), null));
            }
            p.sync();
            assertFalse(p.poll(f -> f.is(CoreMessages.ERROR)));
            p.control(s, CoreMessages.WORLD_REPORT, new WorldReport(world("one-too-many.example"), null));
            assertEquals("quota_exceeded", p.await(CoreMessages.ERROR, s).error().code());

            // A server named before still works, and so does the player's own world.
            p.control(s, CoreMessages.WORLD_REPORT, new WorldReport(world("host3.example"), null));
            h.core.setPlayerContext(world("players-own.example"));
            p.sync();
            assertFalse(p.poll(f -> f.is(CoreMessages.ERROR)));
            h.tickUntil("stores loaded", h.core::storesLoaded);
        }
        assertEquals(0, storeDirs(dir), "reading a store that was never written leaves no directory behind");
        assertFalse(Files.exists(dir.resolve("mineflayer_bridge/data").resolve(ServerKeys.directoryName("host3.example:25565"))));
        assertTrue(Files.exists(dir.resolve("mineflayer_bridge")));
    }
}
