package io.github.pix3lpirat3.mfbridge.core.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.pix3lpirat3.mfbridge.core.FocusControls;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class FocusStateTest {
    private final List<String> attached = new ArrayList<>(List.of("bot:a", "bot:b", "bot:c"));
    private final AtomicInteger changes = new AtomicInteger();
    private final FocusState focus = new FocusState(() -> List.copyOf(attached), changes::incrementAndGet);

    @Test
    void refreshFocusesTheFirstAttachedBotAndFollowsDetach() {
        assertNull(focus.focused());
        focus.refresh();
        assertEquals("bot:a", focus.focused());
        focus.focus("bot:c");
        attached.remove("bot:c");
        focus.refresh();
        assertEquals("bot:a", focus.focused());
        attached.clear();
        focus.refresh();
        assertNull(focus.focused());
        assertEquals(4, changes.get());
        assertEquals(4, focus.version());
    }

    @Test
    void cycleWrapsBothWaysAndIgnoresUnknownOwners() {
        focus.refresh();
        focus.cycle(1);
        assertEquals("bot:b", focus.focused());
        focus.cycle(1);
        focus.cycle(1);
        assertEquals("bot:a", focus.focused());
        focus.cycle(-1);
        assertEquals("bot:c", focus.focused());
        focus.focus("bot:nobody");
        assertEquals("bot:c", focus.focused());
    }

    @Test
    void requestsAreRateLimitedPerBotAndCanBeRefused() {
        focus.refresh();
        assertTrue(focus.request("bot:b", 1_000));
        assertEquals("bot:b", focus.focused());
        focus.focus("bot:a");
        assertFalse(focus.request("bot:b", 1_000 + FocusControls.REQUEST_INTERVAL_MS - 1));
        assertEquals("bot:a", focus.focused());
        assertTrue(focus.request("bot:c", 1_500), "the limit is per bot");
        assertTrue(focus.request("bot:b", 1_000 + FocusControls.REQUEST_INTERVAL_MS));
        focus.setAllowBotRequests(false);
        assertFalse(focus.request("bot:a", 100_000));
        assertEquals("bot:b", focus.focused());
        assertFalse(focus.request("bot:z", 200_000), "unattached bots cannot take focus");
    }
}
