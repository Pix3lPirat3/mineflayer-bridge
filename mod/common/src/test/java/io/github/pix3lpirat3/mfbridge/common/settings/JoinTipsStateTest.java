package io.github.pix3lpirat3.mfbridge.common.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.pix3lpirat3.mfbridge.common.settings.JoinTipsState.Tip;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JoinTipsStateTest {
    @TempDir
    Path dir;

    @Test
    void welcomeOnceThenRemindersUntilTheMenuIsOpened() {
        final Path file = dir.resolve("tips.json");
        final JoinTipsState state = new JoinTipsState(file);
        assertEquals(Tip.WELCOME, state.onJoin(JoinTips.FIRST_TIME));
        assertEquals(Tip.REMINDER, state.onJoin(JoinTips.FIRST_TIME));
        assertEquals(Tip.REMINDER, new JoinTipsState(file).onJoin(JoinTips.FIRST_TIME), "the welcome is remembered");
        state.menuOpened();
        final JoinTipsState again = new JoinTipsState(file);
        assertEquals(Tip.NONE, again.onJoin(JoinTips.FIRST_TIME));
        assertEquals(Tip.REMINDER, again.onJoin(JoinTips.EVERY_JOIN));
        assertEquals(Tip.NONE, again.onJoin(JoinTips.OFF));
    }

    @Test
    void offShowsNothingEvenTheFirstTime() {
        assertEquals(Tip.NONE, JoinTipsState.decide(JoinTips.OFF, false, false));
        assertEquals(Tip.WELCOME, JoinTipsState.decide(JoinTips.EVERY_JOIN, false, true));
    }
}
