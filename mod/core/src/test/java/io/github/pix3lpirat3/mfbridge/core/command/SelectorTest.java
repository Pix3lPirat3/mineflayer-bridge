package io.github.pix3lpirat3.mfbridge.core.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class SelectorTest {
    @Test
    void simpleSelectors() {
        assertInstanceOf(Selector.Me.class, Selector.parse("@me"));
        assertInstanceOf(Selector.Self.class, Selector.parse("@SELF"));
        assertInstanceOf(Selector.Looked.class, Selector.parse("@looked"));
        assertEquals(new Selector.Nearest(Selector.Filter.ANY), Selector.parse("@nearest"));
        assertEquals(new Selector.All(Selector.Filter.ANY), Selector.parse("@all"));
        assertEquals(new Selector.All(Selector.Filter.ANY), Selector.parse("@all[]"));
        assertTrue(Selector.looksLike("@x"));
        assertFalse(Selector.looksLike("x"));
    }

    @Test
    void options() {
        Selector.Nearest n = assertInstanceOf(Selector.Nearest.class,
                Selector.parse("@nearest[type=zombie,type=minecraft:husk,tag=boss,distance=..16,from=bot]"));
        Selector.Filter f = n.filter();
        assertEquals(List.of("minecraft:zombie", "minecraft:husk"), f.types());
        assertEquals(List.of("boss"), f.tags());
        assertEquals(null, f.minDistance());
        assertEquals(16.0, f.maxDistance());
        assertTrue(f.fromBot());
        assertTrue(f.inRange(16));
        assertFalse(f.inRange(16.5));

        Selector.All a = assertInstanceOf(Selector.All.class, Selector.parse("@all[distance=2..8,limit=3,from=player]"));
        assertEquals(2.0, a.filter().minDistance());
        assertEquals(8.0, a.filter().maxDistance());
        assertEquals(3, a.filter().limit());
        assertFalse(a.filter().fromBot());
        Selector.All exact = assertInstanceOf(Selector.All.class, Selector.parse("@all[distance=5]"));
        assertEquals(5.0, exact.filter().minDistance());
        assertEquals(5.0, exact.filter().maxDistance());
        Selector.All open = assertInstanceOf(Selector.All.class, Selector.parse("@all[distance=4..]"));
        assertEquals(null, open.filter().maxDistance());
    }

    @Test
    void errorsPointAtTheProblem() {
        ArgException unknown = assertThrows(ArgException.class, () -> Selector.parse("@foo"));
        assertEquals(0, unknown.start());
        assertEquals(4, unknown.end());
        ArgException open = assertThrows(ArgException.class, () -> Selector.parse("@all[type=cow"));
        assertEquals(13, open.start());
        ArgException key = assertThrows(ArgException.class, () -> Selector.parse("@all[colour=red]"));
        assertEquals(5, key.start());
        assertEquals(11, key.end());
        ArgException range = assertThrows(ArgException.class, () -> Selector.parse("@all[distance=9..2]"));
        assertEquals(14, range.start());
        assertThrows(ArgException.class, () -> Selector.parse("@all[distance=..]"));
        assertThrows(ArgException.class, () -> Selector.parse("@all[distance=-1]"));
        assertThrows(ArgException.class, () -> Selector.parse("@me[type=cow]"));
        assertThrows(ArgException.class, () -> Selector.parse("@all[from=moon]"));
        assertThrows(ArgException.class, () -> Selector.parse("@all[limit=0]"));
        assertThrows(ArgException.class, () -> Selector.parse("@all[type]"));
        assertThrows(ArgException.class, () -> Selector.parse("@all[type=cow,,tag=a]"));
    }
}
