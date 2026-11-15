package io.github.pix3lpirat3.mfbridge.core.vars;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.HashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

class TemplatesTest {
    private static final String OWNER = "bot:miner@example.com";

    private static Variables vars(long version, Map<String, @Nullable Object> own, Map<String, @Nullable Object> user) {
        Map<String, Map<String, @Nullable Object>> m = new HashMap<>();
        m.put(OWNER + "/bridge:mine", own);
        m.put(OWNER + "/" + Variables.USER_SCOPE, user);
        return new Variables(version, m);
    }

    private static String resolve(String text, Variables v) {
        return Templates.resolve(text, name -> v.lookup(OWNER, "bridge:mine", name));
    }

    @Test
    void namesResolveOwnScopeFirstThenUserScope() {
        Variables v = vars(1, Map.of("task", "mining", "done", 3L), Map.of("task", "ignored", "total", 10L));
        assertEquals("mining 3/10", resolve("{task} {done}/{total}", v));
        assertEquals("plain text", resolve("plain text", v));
    }

    @Test
    void unknownAndMalformedPlaceholdersStayRaw() {
        Variables v = vars(1, Map.of("a", 1L), Map.of());
        assertEquals("{missing} 1", resolve("{missing} {a}", v));
        assertEquals("{a:wide} {} {a b} {a", resolve("{a:wide} {} {a b} {a", v));
        assertEquals("{a} 1 }", resolve("{{a}} {a} }}", v));
        assertEquals("{1", resolve("{{{a}", v));
        assertEquals("{" + "x".repeat(65) + "}", resolve("{" + "x".repeat(65) + "}", v));
    }

    @Test
    void formatsApplyToNumbers() {
        Map<String, @Nullable Object> own = new HashMap<>();
        own.put("f", 0.256);
        own.put("whole", 4.0);
        own.put("s", "text");
        own.put("nil", null);
        Variables v = vars(1, own, Map.of());
        assertEquals("0 0.3 0.26 26%", resolve("{f:int} {f:1f} {f:2f} {f:pct}", v));
        assertEquals("4 0.256 text text", resolve("{whole} {f} {s} {s:2f}", v));
        assertEquals("[]", resolve("[{nil}]", v));
    }

    @Test
    void cacheReturnsTheSameStringUntilTheVersionChanges() {
        TemplateCache cache = new TemplateCache();
        String key = OWNER + "/bridge:mine/label";
        String text = new String("{done} blocks");
        Variables v1 = vars(1, Map.of("done", 3L), Map.of());
        String first = cache.resolve(text, key, v1);
        assertEquals("3 blocks", first);
        assertSame(first, cache.resolve(text, key, v1), "steady state allocates nothing");
        String second = cache.resolve(text, key, vars(2, Map.of("done", 4L), Map.of()));
        assertEquals("4 blocks", second);
        assertNotSame(first, second);
        String plain = "no placeholders";
        assertEquals("{done}", cache.resolve("{{done}}", key, v1));
        assertSame(plain, cache.resolve(plain, key, v1));
        assertEquals(2, cache.size());
        assertEquals("{done}", cache.resolve("{done}", "malformed", v1));
    }

    @Test
    void firstVariableSummarisesAnOwner() {
        Variables v = vars(1, Map.of("zeta", 1L, "alpha", 2.5), Map.of());
        assertEquals("alpha 2.5", v.firstOf(OWNER));
        assertEquals(null, v.firstOf("bot:other"));
    }
}
