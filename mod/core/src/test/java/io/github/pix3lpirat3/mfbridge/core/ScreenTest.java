package io.github.pix3lpirat3.mfbridge.core;

import static io.github.pix3lpirat3.mfbridge.core.InputTestSupport.IDENTITY;
import static io.github.pix3lpirat3.mfbridge.core.InputTestSupport.P;
import static io.github.pix3lpirat3.mfbridge.core.InputTestSupport.assertError;
import static io.github.pix3lpirat3.mfbridge.core.InputTestSupport.obj;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.pix3lpirat3.mfbridge.core.decl.ScreenDecl;
import io.github.pix3lpirat3.mfbridge.core.input.InvocationResult;
import io.github.pix3lpirat3.mfbridge.core.screen.ScreenControls;
import io.github.pix3lpirat3.mfbridge.protocol.core.CoreKinds;
import io.github.pix3lpirat3.mfbridge.protocol.core.CoreMessages;
import io.github.pix3lpirat3.mfbridge.protocol.core.ScreenClose;
import io.github.pix3lpirat3.mfbridge.protocol.core.ScreenData;
import io.github.pix3lpirat3.mfbridge.protocol.core.ScreenEvent;
import io.github.pix3lpirat3.mfbridge.protocol.core.ScreenItem;
import io.github.pix3lpirat3.mfbridge.protocol.core.ScreenOpen;
import io.github.pix3lpirat3.mfbridge.protocol.core.ScreenRow;
import io.github.pix3lpirat3.mfbridge.protocol.core.ScreenTab;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ScreenTest {
    static final String MAIN = P + "ui/~screen/main";
    static final String JOBS = P + "ui/~screen/jobs";

    @TempDir
    Path dir;

    static ScreenItem item(String kind, @Nullable String id, String text, @Nullable Object value, @Nullable List<String> options,
            boolean enabled) {
        return new ScreenItem(kind).withId(id).withText(text).withValue(value).withOptions(options).withEnabled(enabled);
    }

    static ScreenData main() {
        return new ScreenData("Miner", List.of(new ScreenTab("work", "Work", List.of(
                new ScreenRow(List.of(item("heading", null, "Jobs", null, null, true))),
                new ScreenRow(List.of(item("button", "mine", "Mine", null, null, true), item("button", "rest", "Rest", null, null, false))),
                new ScreenRow(List.of(item("toggle", "deposit", "Auto-deposit", true, null, true),
                        item("cycle", "speed", "Speed", 0L, List.of("slow", "fast"), true)))))),
                ScreenDecl.HOME_TAB, 0);
    }

    static int declare(Harness h, FakePlugin p) {
        int s = p.openBot("Miner", "example.com").sessionId();
        p.upsert(s, obj(MAIN, CoreKinds.SCREEN, main()),
                obj(JOBS, CoreKinds.SCREEN, new ScreenData("Jobs", List.of(), "none", 0)));
        p.sync();
        h.tick();
        return s;
    }

    @Test
    void screensAppearInDeclarationsOfAttachedBots() throws Exception {
        try (Harness h = ResumeTest.always(dir, b -> {}); FakePlugin p = FakePlugin.ready(h, "dev")) {
            declare(h, p);
            List<ScreenDecl> screens = h.core.declarations().screens();
            assertEquals(List.of(JOBS, MAIN), screens.stream().map(ScreenDecl::key).toList());
            ScreenDecl main = screens.get(1);
            assertEquals("Miner", main.ownerName());
            assertEquals(IDENTITY, main.ownerIdentity());
            assertEquals(main(), main.data());

            h.core.attach().setChoice(IDENTITY, false);
            h.tick();
            assertTrue(h.core.declarations().screens().isEmpty(), "detached bots declare nothing");
        }
    }

    @Test
    void clicksAndValuesReachTheOwnerAndItsAnswerCompletesTheEvent() throws Exception {
        try (Harness h = ResumeTest.always(dir, b -> {}); FakePlugin p = FakePlugin.ready(h, "dev")) {
            int s = declare(h, p);
            ScreenControls c = h.core.screens();

            CompletableFuture<InvocationResult> click = c.event(MAIN, "mine", null);
            ScreenEvent e = p.await(CoreMessages.SCREEN_EVENT, s);
            assertEquals(MAIN, e.screen());
            assertEquals("mine", e.item());
            assertNull(e.value());
            InvocationTest.answer(p, s, e.invocation(), false, "No pickaxe");
            h.tickUntil("answer", click::isDone);
            assertEquals(new InvocationResult(false, "No pickaxe"), click.join());

            c.event(MAIN, "deposit", false);
            assertEquals(false, p.await(CoreMessages.SCREEN_EVENT, s).value());
            c.event(MAIN, "speed", 1L);
            assertEquals(1L, ((Number) p.await(CoreMessages.SCREEN_EVENT, s).value()).longValue());

            assertError("invalid_message", "no enabled item", c.event(MAIN, "rest", null));
            assertError("invalid_message", "no enabled item", c.event(MAIN, "missing", null));
            assertError("invalid_message", "takes a boolean", c.event(MAIN, "deposit", 1L));
            assertError("invalid_message", "option index below 2", c.event(MAIN, "speed", 2L));
            assertError("invalid_message", "carries no value", c.event(MAIN, "mine", true));
            assertError("invalid_message", "unknown screen", c.event(P + "ui/~screen/none", "mine", null));
        }
    }

    @Test
    void openAndCloseAreSharedBetweenPlayerAndBot() throws Exception {
        try (Harness h = ResumeTest.always(dir, b -> {}); FakePlugin p = FakePlugin.ready(h, "dev")) {
            int s = declare(h, p);
            ScreenControls c = h.core.screens();

            c.opened(MAIN);
            assertTrue(c.isOpen(MAIN));
            assertEquals(new ScreenOpen(MAIN), p.await(CoreMessages.SCREEN_OPEN, s), "the player opened it");
            c.opened(JOBS);
            p.await(CoreMessages.SCREEN_OPEN, s);
            assertTrue(c.isOpen(MAIN), "a child keeps its parent open");
            c.closed(JOBS);
            assertEquals(new ScreenClose(JOBS), p.await(CoreMessages.SCREEN_CLOSE, s));
            c.closed(JOBS);
            p.sync();
            assertFalse(p.poll(f -> f.is(CoreMessages.SCREEN_CLOSE)), "closing twice says nothing");

            p.reliable(s, CoreMessages.SCREEN_CLOSE, new ScreenClose(MAIN));
            p.sync();
            assertFalse(c.isOpen(MAIN), "the bot closed it");

            p.reliable(s, CoreMessages.SCREEN_OPEN, new ScreenOpen(JOBS));
            p.sync();
            assertEquals(JOBS, c.requestedOpen());
            c.opened(JOBS);
            assertNull(c.requestedOpen());
            p.sync();
            assertFalse(p.poll(f -> f.is(CoreMessages.SCREEN_OPEN)), "a requested screen is not echoed back");

            p.reliable(s, CoreMessages.SCREEN_OPEN, new ScreenOpen("bot:other@example.com/ui/~screen/main"));
            p.sync();
            assertNull(c.requestedOpen(), "a bot opens only its own screens");
            assertThrows(IllegalArgumentException.class, () -> c.opened(P + "ui/~screen/none"));

            h.core.attach().setChoice(IDENTITY, false);
            h.tick();
            assertFalse(c.isOpen(JOBS), "a detached owner's screens close");
        }
    }

    @Test
    void thePrimaryMenuIsThePlayersPickWhileItsOwnerStillOffersIt() throws Exception {
        try (Harness h = ResumeTest.always(dir, b -> {}); FakePlugin p = FakePlugin.ready(h, "dev")) {
            int s = declare(h, p);
            String menu = P + "ui/~screen/menu";
            ScreenControls c = h.core.screens();
            assertNull(c.primaryMenu(), "Bridge home by default");

            c.setPrimaryMenu(MAIN);
            assertNull(c.primaryMenu(), "a home tab is not a menu");
            assertEquals(MAIN, c.primaryMenuChoice());

            p.upsert(s, obj(menu, CoreKinds.SCREEN, new ScreenData("Miner", List.of(), ScreenDecl.HOME_MENU, 0)));
            p.sync();
            h.tick();
            c.setPrimaryMenu(menu);
            assertEquals(menu, c.primaryMenu());

            h.core.attach().setChoice(IDENTITY, false);
            h.tick();
            assertNull(c.primaryMenu(), "a detached owner falls back to Bridge home");
            assertEquals(menu, c.primaryMenuChoice(), "the pick is kept for when it returns");
            h.core.attach().setChoice(IDENTITY, true);
            h.tick();
            assertEquals(menu, c.primaryMenu());
            c.setPrimaryMenu(null);
            assertNull(c.primaryMenu());
        }
    }
}
