package io.github.pix3lpirat3.mfbridge.core;

import static io.github.pix3lpirat3.mfbridge.core.InputQueueTest.farmForm;
import static io.github.pix3lpirat3.mfbridge.core.InputQueueTest.pick;
import static io.github.pix3lpirat3.mfbridge.core.InputQueueTest.prompt;
import static io.github.pix3lpirat3.mfbridge.core.InputTestSupport.P;
import static io.github.pix3lpirat3.mfbridge.core.InputTestSupport.errorResponse;
import static io.github.pix3lpirat3.mfbridge.core.InputTestSupport.matchedBot;
import static io.github.pix3lpirat3.mfbridge.core.InputTestSupport.obj;
import static io.github.pix3lpirat3.mfbridge.core.InputTestSupport.response;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.pix3lpirat3.mfbridge.core.input.InputControls;
import io.github.pix3lpirat3.mfbridge.core.input.InputRequest;
import io.github.pix3lpirat3.mfbridge.protocol.core.ActionData;
import io.github.pix3lpirat3.mfbridge.protocol.core.ContainerOpen;
import io.github.pix3lpirat3.mfbridge.protocol.core.CoreKinds;
import io.github.pix3lpirat3.mfbridge.protocol.core.CoreMessages;
import io.github.pix3lpirat3.mfbridge.protocol.core.ErrorMessage;
import io.github.pix3lpirat3.mfbridge.protocol.core.FormOpen;
import io.github.pix3lpirat3.mfbridge.protocol.core.PickKind;
import io.github.pix3lpirat3.mfbridge.protocol.core.PromptKind;
import io.github.pix3lpirat3.mfbridge.protocol.core.ScreenOpen;
import io.github.pix3lpirat3.mfbridge.protocol.core.Target;
import io.github.pix3lpirat3.mfbridge.protocol.core.TargetSpec;
import io.github.pix3lpirat3.mfbridge.protocol.wire.Envelope;
import io.github.pix3lpirat3.mfbridge.protocol.wire.MessageType;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A bot that keeps putting things in front of the player is held off, and what the player asks for is not. */
class LockoutTest {
    static final String ACTION = P + "ui/~action/add";

    @TempDir
    Path dir;

    static long activeId(Harness h) {
        return Objects.requireNonNull(h.core.input().active(), "active request").id();
    }

    /** Awaits the {@code cooldown} answer to a request and returns the ms it says to wait. */
    static long cooldown(FakePlugin p, long req, MessageType<?> request) {
        FakePlugin.Frame f = p.await("error for req " + req, x -> x.is(CoreMessages.ERROR) && x.env().req() == req);
        assertTrue(f.env().has(Envelope.RESPONSE) && f.env().has(Envelope.ERROR), "error response flags");
        ErrorMessage m = f.decode(CoreMessages.ERROR);
        assertEquals("cooldown", m.error().code(), m.error().message());
        assertTrue(m.error().retryable(), "the bot may try again later");
        assertEquals(request.id(), m.relatesTo());
        Map<?, ?> details = assertInstanceOf(Map.class, m.error().details());
        return ((Number) Objects.requireNonNull(details.get("retryAfterMs"), "retryAfterMs")).longValue();
    }

    /** Lets time pass in steps the heartbeat survives. */
    static void advance(Harness h, FakePlugin p, long ms) {
        for (long left = ms; left > 0; left -= 4_000) {
            h.clock.advanceMs(Math.min(left, 4_000));
            p.sync();
        }
    }

    /** Opens a form and has the player dismiss it. */
    static void openAndCancel(Harness h, FakePlugin p, int s) {
        long req = p.reliableRequest(s, CoreMessages.FORM_OPEN, farmForm());
        p.sync();
        h.core.input().cancel(activeId(h));
        errorResponse(p, req, "cancelled", CoreMessages.FORM_OPEN);
    }

    @Test
    void dismissingASurfaceRefusesUnaskedOnesForAWhileLongerEachTime() throws Exception {
        try (Harness h = new Harness(dir); FakePlugin p = FakePlugin.ready(h, "dev")) {
            int s = matchedBot(h, p, "Miner");
            openAndCancel(h, p, s);

            // The trap: reopen as soon as the player cancels. The answer comes at once.
            long again = p.reliableRequest(s, CoreMessages.FORM_OPEN, farmForm());
            assertEquals(5_000, cooldown(p, again, CoreMessages.FORM_OPEN));
            assertNull(h.core.input().active(), "nothing was put in front of the player");
            advance(h, p, 1_200);
            assertEquals(3_800, cooldown(p, p.reliableRequest(s, CoreMessages.FORM_OPEN, farmForm()), CoreMessages.FORM_OPEN));

            // Every kind of surface is held off, not only the one that was dismissed.
            cooldown(p, p.reliableRequest(s, CoreMessages.PROMPT_REQUEST, prompt(PromptKind.CONFIRM, false, 30_000, "hud")),
                    CoreMessages.PROMPT_REQUEST);
            cooldown(p, p.reliableRequest(s, CoreMessages.PICK_REQUEST, pick(PickKind.BLOCK, 30_000, false)),
                    CoreMessages.PICK_REQUEST);

            advance(h, p, 3_800);
            openAndCancel(h, p, s);
            assertEquals(15_000, cooldown(p, p.reliableRequest(s, CoreMessages.FORM_OPEN, farmForm()), CoreMessages.FORM_OPEN),
                    "a second dismissal in a row waits longer");
            advance(h, p, 15_000);
            openAndCancel(h, p, s);
            assertEquals(30_000, cooldown(p, p.reliableRequest(s, CoreMessages.FORM_OPEN, farmForm()), CoreMessages.FORM_OPEN));
            advance(h, p, 30_000);
            openAndCancel(h, p, s);
            assertEquals(30_000, cooldown(p, p.reliableRequest(s, CoreMessages.FORM_OPEN, farmForm()), CoreMessages.FORM_OPEN),
                    "the longest wait repeats");

            // A minute without a dismissal starts the count over.
            advance(h, p, 60_000);
            openAndCancel(h, p, s);
            assertEquals(5_000, cooldown(p, p.reliableRequest(s, CoreMessages.FORM_OPEN, farmForm()), CoreMessages.FORM_OPEN));
        }
    }

    @Test
    void answeringASurfaceEndsTheCooldown() throws Exception {
        try (Harness h = new Harness(dir); FakePlugin p = FakePlugin.ready(h, "dev")) {
            int s = matchedBot(h, p, "Miner");
            openAndCancel(h, p, s);
            advance(h, p, 5_000);
            openAndCancel(h, p, s);
            advance(h, p, 15_000);

            long req = p.reliableRequest(s, CoreMessages.PROMPT_REQUEST, prompt(PromptKind.CONFIRM, false, 30_000, "hud"));
            p.sync();
            h.core.input().resolvePrompt(activeId(h), true);
            response(p, CoreMessages.PROMPT_RESULT, req);

            // Completing one counts as asking for what follows, so a wizard's next step opens.
            long next = p.reliableRequest(s, CoreMessages.FORM_OPEN, farmForm());
            p.sync();
            assertTrue(h.core.input().askedFor(activeId(h)));
            h.core.input().cancel(activeId(h));
            errorResponse(p, next, "cancelled", CoreMessages.FORM_OPEN);
            advance(h, p, 6_000);
            long after = p.reliableRequest(s, CoreMessages.FORM_OPEN, farmForm());
            p.sync();
            assertNotNull(h.core.input().active(), "the first wait was 5 s again: the count started over");
            assertFalse(h.core.input().askedFor(activeId(h)), "and this one the player did not ask for");
            h.core.input().cancel(activeId(h));
            errorResponse(p, after, "cancelled", CoreMessages.FORM_OPEN);
        }
    }

    @Test
    void whatThePlayerAsksForOpensDuringACooldown() throws Exception {
        try (Harness h = new Harness(dir); FakePlugin p = FakePlugin.ready(h, "dev")) {
            int s = matchedBot(h, p, "Miner");
            p.upsert(s, obj(ACTION, CoreKinds.ACTION, new ActionData("Add bot", null, new TargetSpec("air", null, null), 0)));
            p.sync();
            h.tick();
            openAndCancel(h, p, s);
            cooldown(p, p.reliableRequest(s, CoreMessages.FORM_OPEN, farmForm()), CoreMessages.FORM_OPEN);

            // The player runs one of the bot's actions; the form it answers with is wanted.
            h.core.invokeAction(ACTION, new Target("air"));
            long req = p.reliableRequest(s, CoreMessages.FORM_OPEN, farmForm());
            p.sync();
            InputRequest form = h.core.input().active();
            assertNotNull(form, "asked for, so it opens in spite of the cooldown");
            assertTrue(h.core.input().askedFor(form.id()), "and may open over Bridge home");

            // A few seconds later the same request no longer counts as asked for.
            advance(h, p, 5_001);
            assertFalse(h.core.input().askedFor(form.id()));
            h.core.input().cancel(form.id());
            errorResponse(p, req, "cancelled", CoreMessages.FORM_OPEN);
            cooldown(p, p.reliableRequest(s, CoreMessages.FORM_OPEN, farmForm()), CoreMessages.FORM_OPEN);
        }
    }

    @Test
    void screensAndContainerViewsAreHeldOffToo() throws Exception {
        try (Harness h = ResumeTest.always(dir, b -> {}); FakePlugin p = FakePlugin.ready(h, "dev")) {
            int s = ScreenTest.declare(h, p);
            p.upsert(s, obj(ContainerViewTest.VIEW, CoreKinds.CONTAINER_VIEW, ContainerViewTest.jobs()));
            p.sync();
            h.tick();

            // The bot opens a screen and the player closes it.
            p.reliable(s, CoreMessages.SCREEN_OPEN, new ScreenOpen(ScreenTest.MAIN));
            p.sync();
            assertEquals(ScreenTest.MAIN, h.core.screens().requestedOpen());
            assertFalse(h.core.screens().askedFor(ScreenTest.MAIN), "nobody asked for it");
            h.core.screens().opened(ScreenTest.MAIN);
            h.core.screens().closed(ScreenTest.MAIN);
            p.await(CoreMessages.SCREEN_CLOSE, s);

            p.reliable(s, CoreMessages.SCREEN_OPEN, new ScreenOpen(ScreenTest.MAIN));
            ErrorMessage refused = p.await(CoreMessages.ERROR, s);
            assertEquals("cooldown", refused.error().code());
            assertEquals(CoreMessages.SCREEN_OPEN.id(), refused.relatesTo());
            assertEquals(ScreenTest.MAIN, ((Map<?, ?>) Objects.requireNonNull(refused.error().details())).get("key"),
                    "the refusal names the screen");
            assertNull(h.core.screens().requestedOpen());

            p.reliable(s, CoreMessages.CONTAINER_OPEN, new ContainerOpen(ContainerViewTest.VIEW));
            ErrorMessage view = p.await(CoreMessages.ERROR, s);
            assertEquals("cooldown", view.error().code());
            assertEquals(5_000L, ((Number) ((Map<?, ?>) Objects.requireNonNull(view.error().details())).get("retryAfterMs")).longValue());
            assertEquals(ContainerViewTest.VIEW, ((Map<?, ?>) view.error().details()).get("key"), "and the view, next to the wait");
            assertNull(h.core.containers().requestedOpen());

            // After the wait the view opens; the player closing it is a dismissal again.
            advance(h, p, 5_000);
            p.reliable(s, CoreMessages.CONTAINER_OPEN, new ContainerOpen(ContainerViewTest.VIEW));
            p.sync();
            assertEquals(ContainerViewTest.VIEW, h.core.containers().requestedOpen());
            h.core.containers().opened(ContainerViewTest.VIEW);
            h.core.containers().closed(ContainerViewTest.VIEW);
            p.reliable(s, CoreMessages.SCREEN_OPEN, new ScreenOpen(ScreenTest.MAIN));
            assertEquals("cooldown", p.await(CoreMessages.ERROR, s).error().code());

            // A screen the player opened is theirs to close: no cooldown starts, and it counts as asking.
            advance(h, p, 60_000);
            h.core.screens().opened(ScreenTest.JOBS);
            assertTrue(h.core.screens().askedFor(ScreenTest.MAIN));
            h.core.screens().closed(ScreenTest.JOBS);
            p.reliable(s, CoreMessages.SCREEN_OPEN, new ScreenOpen(ScreenTest.MAIN));
            p.sync();
            assertEquals(ScreenTest.MAIN, h.core.screens().requestedOpen());
        }
    }

    @Test
    void anyRefusedOpenNamesItsSurface() throws Exception {
        try (Harness h = ObjectSyncTest.always(dir); FakePlugin p = FakePlugin.ready(h, "dev")) {
            int s = p.openBot("Miner", "example.com").sessionId();
            String screen = "bot:miner@example.com/ui/missing-screen";
            p.reliable(s, CoreMessages.SCREEN_OPEN, new ScreenOpen(screen));
            ErrorMessage noScreen = p.await(CoreMessages.ERROR, s);
            assertEquals("invalid_message", noScreen.error().code());
            assertEquals(CoreMessages.SCREEN_OPEN.id(), noScreen.relatesTo());
            assertEquals(Map.of("key", screen), noScreen.error().details());

            String view = "bot:someone-else@example.com/ui/chest";
            p.reliable(s, CoreMessages.CONTAINER_OPEN, new ContainerOpen(view));
            ErrorMessage notMine = p.await(CoreMessages.ERROR, s);
            assertEquals("not_owner", notMine.error().code());
            assertEquals(CoreMessages.CONTAINER_OPEN.id(), notMine.relatesTo());
            assertEquals(Map.of("key", view), notMine.error().details());
        }
    }

    @Test
    void aSessionMayOnlyQueueSoManyRequests() throws Exception {
        try (Harness h = new Harness(dir); FakePlugin p = FakePlugin.ready(h, "dev")) {
            int s = matchedBot(h, p, "Miner");
            for (int i = 0; i < 16; i++) {
                p.reliableRequest(s, CoreMessages.FORM_OPEN, farmForm());
            }
            long over = p.reliableRequest(s, CoreMessages.FORM_OPEN, farmForm());
            errorResponse(p, over, "quota_exceeded", CoreMessages.FORM_OPEN);
            InputControls in = h.core.input();
            assertEquals(15, in.queued(), "one active and fifteen waiting");

            // Answering one makes room for one more.
            in.resolveForm(activeId(h), Map.of("crop", "wheat"));
            p.reliableRequest(s, CoreMessages.FORM_OPEN, farmForm());
            p.sync();
            assertEquals(15, in.queued());
        }
    }

    @Test
    void absurdTimeoutsAreCut() throws Exception {
        try (Harness h = new Harness(dir); FakePlugin p = FakePlugin.ready(h, "dev")) {
            int s = matchedBot(h, p, "Miner");
            FormOpen forever = new FormOpen("Forever", farmForm().fields(), null, "screen", -1, null);
            p.reliableRequest(s, CoreMessages.FORM_OPEN, forever);
            p.sync();
            InputRequest form = Objects.requireNonNull(h.core.input().active());
            assertEquals(form.createdAtMs() + 3_600_000, form.deadlineMs(), "49 days became one hour");
        }
    }
}
