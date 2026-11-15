package io.github.pix3lpirat3.mfbridge.core.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.pix3lpirat3.mfbridge.api.WorldMatcher;
import io.github.pix3lpirat3.mfbridge.protocol.core.WorldKey;
import org.junit.jupiter.api.Test;

class WorldTest {
    static WorldKey w(String server, String backend, String dim) {
        return new WorldKey(server, backend, dim);
    }

    @Test
    void serverKeysNormalize() {
        assertEquals("example.com:25565", ServerKeys.normalize(" Example.COM "));
        assertEquals("example.com:25566", ServerKeys.normalize("example.com:25566"));
        assertEquals("example.com:25565", ServerKeys.normalize("example.com:"));
        assertEquals("localhost:25565", ServerKeys.normalize("[::1]"));
        assertEquals("localhost:25565", ServerKeys.normalize("127.0.0.1"));
        assertEquals("localhost:25570", ServerKeys.normalize("0.0.0.0:25570"));
        assertEquals("localhost:25565", ServerKeys.normalize("LOCALHOST"));
        assertEquals("singleplayer:My World", ServerKeys.normalize(" Singleplayer:My World "), "a save folder keeps its case");
        assertEquals("localhost:1234", ServerKeys.normalize("[::1]:1234"));
        assertEquals("[2001:db8::1]:1234", ServerKeys.normalize("[2001:db8::1]:1234"));
        assertEquals("[2001:db8::1]:25565", ServerKeys.normalize("2001:db8::1"));
        assertEquals("203.0.113.7:25565", ServerKeys.normalize("203.0.113.7"));
    }

    @Test
    void directoryNamesAreSafeAndNeverShared() {
        String name = ServerKeys.directoryName("example.com:25565");
        assertTrue(name.matches("example\\.com_25565-[0-9a-f]{16}"), name);
        assertEquals(name, ServerKeys.directoryName("example.com:25565"));
        assertTrue(ServerKeys.directoryName("..").matches("\\.\\.-[0-9a-f]{16}"), "never a path of dots alone");
        assertTrue(ServerKeys.directoryName("[::1]:25565").matches("___1__25565-[0-9a-f]{16}"));
        // These clean up to the same readable part; the hash keeps them apart.
        assertNotEquals(ServerKeys.directoryName("singleplayer:My World"), ServerKeys.directoryName("singleplayer:my world"));
        assertNotEquals(ServerKeys.directoryName("a:b"), ServerKeys.directoryName("a_b"));
        String longKey = "singleplayer:" + "x".repeat(500);
        assertNotEquals(ServerKeys.directoryName(longKey + "1"), ServerKeys.directoryName(longKey + "2"));
        assertTrue(ServerKeys.directoryName(longKey).length() <= 100);
    }

    @Test
    void defaultMatcherComparesServerBackendAndDimension() {
        DefaultWorldMatcher m = DefaultWorldMatcher.INSTANCE;
        WorldKey a = w("Example.com", null, "minecraft:overworld");
        assertEquals(WorldMatcher.Result.MATCH, m.match(a, w("example.com:25565", null, "minecraft:overworld")));
        assertEquals(WorldMatcher.Result.MATCH, m.match(a, w("example.com", "lobby", "minecraft:overworld")),
                "backend only compared when both have one");
        assertEquals(WorldMatcher.Result.MISMATCH,
                m.match(w("example.com", "lobby", "minecraft:overworld"), w("example.com", "survival", "minecraft:overworld")));
        assertEquals(WorldMatcher.Result.MISMATCH, m.match(a, w("other.com", null, "minecraft:overworld")));
        assertEquals(WorldMatcher.Result.MISMATCH, m.match(a, w("example.com", null, "minecraft:the_nether")));
        assertTrue(DefaultWorldMatcher.sameServerOtherDimension(a, w("example.com", null, "minecraft:the_nether")));
        assertFalse(DefaultWorldMatcher.sameServerOtherDimension(a, w("example.com", null, "minecraft:overworld")));
        assertFalse(DefaultWorldMatcher.sameServerOtherDimension(a, w("other.com", null, "minecraft:the_nether")));
    }

    @Test
    void chainRunsByPriorityAndFallsBackToDefault() {
        WorldMatchers chain = new WorldMatchers();
        WorldKey a = w("proxy.net", null, "minecraft:overworld");
        WorldKey b = w("backend-1.internal", null, "minecraft:overworld");
        assertFalse(chain.matches(a, b));
        chain.add((p, o) -> WorldMatcher.Result.ABSTAIN, 100);
        chain.add((p, o) -> o.server().endsWith(".internal") ? WorldMatcher.Result.MATCH : WorldMatcher.Result.ABSTAIN, 10);
        assertTrue(chain.matches(a, b));
        chain.add((p, o) -> WorldMatcher.Result.MISMATCH, 50);
        assertFalse(chain.matches(a, b), "higher priority answers first");
        assertFalse(chain.matches(a, a), "registered definite answers win over the default");
    }
}
