package io.github.pix3lpirat3.mfbridge.common.ui.logic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class DisplayTextTest {
    private static final Text EN = EnglishText.INSTANCE;

    @Test
    void ageBuckets() {
        final long now = 10_000_000_000L;
        assertEquals("never", DisplayText.age(EN, 0, now));
        assertEquals("just now", DisplayText.age(EN, now - 59_000, now));
        assertEquals("5 min ago", DisplayText.age(EN, now - 300_000, now));
        assertEquals("2 h ago", DisplayText.age(EN, now - 7_200_000, now));
        assertEquals("3 d ago", DisplayText.age(EN, now - 3 * 86_400_000L, now));
    }

    @Test
    void statusPrefersText() {
        assertEquals("", DisplayText.status(null));
        assertEquals("mining", DisplayText.status(Map.of("text", "mining", "hp", 20.0)));
        assertEquals("idle", DisplayText.status("idle"));
    }

    @Test
    void statusListsFirstEntries() {
        final Map<String, Object> m = new LinkedHashMap<>();
        m.put("task", "farm");
        m.put("hp", 18.0);
        m.put("food", 19.5);
        m.put("pos", List.of(1, 2, 3));
        m.put("extra", true);
        assertEquals("task: farm, hp: 18, food: 19.5, pos: ..., ...", DisplayText.status(m));
    }

    @Test
    void ownerPrefixNeedsSeparator() {
        assertTrue(DisplayText.ownedBy("bot:alice/bridge:window/~action/open", DisplayText.botOwner("alice")));
        assertFalse(DisplayText.ownedBy("bot:alice2/x/~action/a", "bot:alice"));
        assertTrue(DisplayText.ownedBy("ctl:dev1/manager/bm/~action/add", "ctl:dev1/manager"));
        assertFalse(DisplayText.ownedBy("bot:alice", "bot:alice"));
    }
}
