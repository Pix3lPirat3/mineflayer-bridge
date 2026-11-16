package io.github.pix3lpirat3.mfbridge.core;

import io.github.pix3lpirat3.mfbridge.core.remote.Permission;
import static io.github.pix3lpirat3.mfbridge.core.InputTestSupport.IDENTITY;
import static io.github.pix3lpirat3.mfbridge.core.InputTestSupport.NETHER;
import static io.github.pix3lpirat3.mfbridge.core.InputTestSupport.errorResponse;
import static io.github.pix3lpirat3.mfbridge.core.InputTestSupport.matchedBot;
import static io.github.pix3lpirat3.mfbridge.core.InputTestSupport.response;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.pix3lpirat3.mfbridge.core.input.FormInput;
import io.github.pix3lpirat3.mfbridge.core.input.InputChanged;
import io.github.pix3lpirat3.mfbridge.core.input.InputControls;
import io.github.pix3lpirat3.mfbridge.core.input.InputRequest;
import io.github.pix3lpirat3.mfbridge.core.input.PickInput;
import io.github.pix3lpirat3.mfbridge.core.input.PromptInput;
import io.github.pix3lpirat3.mfbridge.protocol.core.CoreMessages;
import io.github.pix3lpirat3.mfbridge.protocol.core.ErrorMessage;
import io.github.pix3lpirat3.mfbridge.protocol.core.FieldType;
import io.github.pix3lpirat3.mfbridge.protocol.core.FormClose;
import io.github.pix3lpirat3.mfbridge.protocol.core.FormButton;
import io.github.pix3lpirat3.mfbridge.protocol.core.FormField;
import io.github.pix3lpirat3.mfbridge.protocol.core.FormOpen;
import io.github.pix3lpirat3.mfbridge.protocol.core.FormResult;
import io.github.pix3lpirat3.mfbridge.protocol.core.PickBlock;
import io.github.pix3lpirat3.mfbridge.protocol.core.PickCancel;
import io.github.pix3lpirat3.mfbridge.protocol.core.PickDirection;
import io.github.pix3lpirat3.mfbridge.protocol.core.PickKind;
import io.github.pix3lpirat3.mfbridge.protocol.core.PickRequest;
import io.github.pix3lpirat3.mfbridge.protocol.core.PickResult;
import io.github.pix3lpirat3.mfbridge.protocol.core.PickResultMessage;
import io.github.pix3lpirat3.mfbridge.protocol.core.PromptCancel;
import io.github.pix3lpirat3.mfbridge.protocol.core.PromptKind;
import io.github.pix3lpirat3.mfbridge.protocol.core.PromptRequest;
import io.github.pix3lpirat3.mfbridge.protocol.core.PromptResult;
import io.github.pix3lpirat3.mfbridge.protocol.core.SessionClose;
import io.github.pix3lpirat3.mfbridge.protocol.core.Vec3d;
import io.github.pix3lpirat3.mfbridge.protocol.core.Vec3i;
import io.github.pix3lpirat3.mfbridge.protocol.core.WorldReport;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InputQueueTest {
    @TempDir
    Path dir;

    static PickRequest pick(PickKind kind, int timeoutMs, boolean urgent) {
        return new PickRequest(kind, "Click the chest", timeoutMs, null, null, null, urgent);
    }

    static PromptRequest prompt(PromptKind kind, Object defaultValue, int timeoutMs, String surface) {
        return new PromptRequest(kind, "Place 214 blocks?", kind == PromptKind.CHOICE ? List.of("pickaxe", "shovel") : null,
                defaultValue, timeoutMs, null, surface, null, null, null, null, null, null, null);
    }

    static FormOpen farmForm() {
        return new FormOpen("Farm settings", List.of(
                new FormField("crop", FieldType.SELECT, null, null, null, null, null, List.of("wheat", "carrots"), true),
                new FormField("radius", FieldType.SLIDER, "Radius", 16, 4.0, 64.0, 1.0, null, false),
                new FormField("replant", FieldType.TOGGLE, null, true, null, null, null, null, false)), "Save", "screen", 3_600_000, null);
    }

    static PickResult block() {
        return new PickResult.Block(new PickBlock(new Vec3i(1, 64, 2), "up", new Vec3d(1.5, 65, 2.5),
                "minecraft:chest[facing=north]"));
    }

    static InputRequest active(Harness h) {
        return Objects.requireNonNull(h.core.input().active(), "active request");
    }

    @Test
    void queueKeepsArrivalOrderWithUrgentPicksFirstAndAnswersEachRequest() throws Exception {
        try (Harness h = new Harness(dir); FakePlugin p = FakePlugin.ready(h, "dev")) {
            int s = matchedBot(h, p, "Miner");
            // Jumping the pick queue is off by default.
            h.core.devices().setPermission("dev", Permission.INPUT_PICK_URGENT, true);
            List<InputChanged> changes = new ArrayList<>();
            h.core.events().subscribe(InputChanged.TYPE, changes::add);
            long r1 = p.reliableRequest(s, CoreMessages.PICK_REQUEST, pick(PickKind.BLOCK, 60_000, false));
            long r2 = p.reliableRequest(s, CoreMessages.PROMPT_REQUEST, prompt(PromptKind.CONFIRM, false, 30_000, "hud"));
            long r3 = p.reliableRequest(s, CoreMessages.FORM_OPEN, farmForm());
            long r4 = p.reliableRequest(s, CoreMessages.PICK_REQUEST, pick(PickKind.ENTITY, 60_000, true));
            long r5 = p.reliableRequest(s, CoreMessages.PICK_REQUEST, pick(PickKind.DIRECTION, 60_000, true));
            p.sync();
            InputControls in = h.core.input();

            PickInput first = assertInstanceOf(PickInput.class, active(h));
            assertEquals(PickKind.BLOCK, first.request().kind());
            assertEquals("Miner", first.ownerName());
            assertEquals(IDENTITY, first.ownerIdentity());
            assertEquals(s, first.sessionId());
            assertEquals(first.createdAtMs() + 60_000, first.deadlineMs());
            assertEquals(4, in.queued(), "urgent picks queue without displacing the active one");
            assertEquals(5, in.pending().size());
            assertSame(first, in.pending().get(0), "pending lists the active one first");
            assertEquals(PickKind.ENTITY, assertInstanceOf(PickInput.class, in.pending().get(1)).request().kind(),
                    "then the others in queue order");
            assertSame(first, changes.get(0).active());
            assertEquals(4, changes.get(changes.size() - 1).queued());

            assertThrows(IllegalArgumentException.class, () -> in.resolvePick(first.id(),
                    new PickResult.Direction(new PickDirection(0, 0, "north"))));
            assertThrows(IllegalArgumentException.class, () -> in.resolvePrompt(first.id(), true));
            in.resolvePick(first.id(), block());
            PickResultMessage picked = response(p, CoreMessages.PICK_RESULT, r1);
            assertEquals(block(), picked.result());

            PickInput urgent = assertInstanceOf(PickInput.class, active(h));
            assertEquals(PickKind.ENTITY, urgent.request().kind(), "first urgent pick jumps the queue");
            in.cancel(urgent.id());
            errorResponse(p, r4, "cancelled", CoreMessages.PICK_REQUEST);
            PickInput second = assertInstanceOf(PickInput.class, active(h));
            assertEquals(PickKind.DIRECTION, second.request().kind(), "urgent picks keep their own order");
            in.resolvePick(second.id(), new PickResult.Direction(new PickDirection(90, 0, "west")));
            response(p, CoreMessages.PICK_RESULT, r5);

            PromptInput confirm = assertInstanceOf(PromptInput.class, active(h));
            assertThrows(IllegalArgumentException.class, () -> in.resolvePrompt(confirm.id(), "yes"));
            in.resolvePrompt(confirm.id(), true);
            assertEquals(new PromptResult(true, false, "hud"), response(p, CoreMessages.PROMPT_RESULT, r2));

            FormInput form = assertInstanceOf(FormInput.class, active(h));
            assertEquals(0, in.queued());
            assertThrows(IllegalArgumentException.class, () -> in.resolveForm(form.id(), Map.of("radius", 20)),
                    "crop is required");
            assertThrows(IllegalArgumentException.class, () -> in.resolveForm(form.id(), Map.of("crop", "potato")));
            assertThrows(IllegalArgumentException.class,
                    () -> in.resolveForm(form.id(), Map.of("crop", "wheat", "radius", 100)));
            assertThrows(IllegalArgumentException.class, () -> in.resolveForm(form.id(), Map.of("crop", "wheat", "x", 1)));
            in.resolveForm(form.id(), Map.of("crop", "carrots", "radius", 20));
            FormResult values = response(p, CoreMessages.FORM_RESULT, r3);
            assertEquals(Map.of("crop", "carrots", "radius", 20L, "replant", true), values.values());

            assertNull(in.active());
            assertEquals(0, in.queued());
            assertNull(changes.get(changes.size() - 1).active());
            in.resolvePick(first.id(), block());
            in.cancel(form.id());
            p.sync();
            assertFalse(p.poll(f -> f.is(CoreMessages.ERROR)), "stale ids are ignored: " + p.describe());
        }
    }

    @Test
    void anExtraButtonAnswersTheFormWithoutValues() throws Exception {
        try (Harness h = new Harness(dir); FakePlugin p = FakePlugin.ready(h, "dev")) {
            int s = matchedBot(h, p, "Miner");
            FormOpen chest = new FormOpen("Chest", List.of(new FormField("name", FieldType.TEXT, null, null, null, null, null, null, true)),
                    "Save", "screen", 60_000, List.of(new FormButton("remove", "Remove container", "mineflayer_bridge:trash", true)));
            long req = p.reliableRequest(s, CoreMessages.FORM_OPEN, chest);
            p.sync();
            InputControls in = h.core.input();
            FormInput form = assertInstanceOf(FormInput.class, active(h));
            assertThrows(IllegalArgumentException.class, () -> in.pressFormButton(form.id(), "keep"));
            in.pressFormButton(form.id(), "remove");
            assertEquals(new FormResult(Map.of(), "remove"), response(p, CoreMessages.FORM_RESULT, req));
            assertNull(in.active());
        }
    }

    @Test
    void deadlinesTimeOutPicksAndDefaultPrompts() throws Exception {
        try (Harness h = new Harness(dir); FakePlugin p = FakePlugin.ready(h, "dev")) {
            int s = matchedBot(h, p, "Miner");
            long pickReq = p.reliableRequest(s, CoreMessages.PICK_REQUEST, pick(PickKind.REGION, 5_000, false));
            long promptReq = p.reliableRequest(s, CoreMessages.PROMPT_REQUEST,
                    prompt(PromptKind.CHOICE, "shovel", 10_000, "hud"));
            long formReq = p.reliableRequest(s, CoreMessages.FORM_OPEN, farmForm());
            p.sync();
            assertEquals(2, h.core.input().queued());

            h.clock.advanceMs(4_999);
            h.tick();
            assertInstanceOf(PickInput.class, active(h));
            h.clock.advanceMs(1);
            errorResponse(p, pickReq, "timeout", CoreMessages.PICK_REQUEST);
            PromptInput choice = assertInstanceOf(PromptInput.class, active(h));
            assertThrows(IllegalArgumentException.class, () -> h.core.input().resolvePrompt(choice.id(), "axe"));

            h.clock.advanceMs(5_000);
            assertEquals(new PromptResult("shovel", true, "hud"), response(p, CoreMessages.PROMPT_RESULT, promptReq),
                    "a timed-out prompt resolves with its default");
            FormInput form = assertInstanceOf(FormInput.class, active(h));
            assertEquals(form.createdAtMs() + CoreMessages.FORM_OPEN.timeoutMs(), form.deadlineMs());
            h.clock.advanceMs(CoreMessages.FORM_OPEN.timeoutMs());
            errorResponse(p, formReq, "timeout", CoreMessages.FORM_OPEN);
            assertNull(h.core.input().active());
        }
    }

    @Test
    void requestsAreRejectedWhenNotAttachedOrNotInTheBotsWorld() throws Exception {
        try (Harness h = new Harness(dir); FakePlugin p = FakePlugin.ready(h, "dev")) {
            int s = p.openBot("Miner", "example.com").sessionId();
            p.control(s, CoreMessages.WORLD_REPORT, new WorldReport(InputTestSupport.BOT_WORLD, null));
            long detached = p.reliableRequest(s, CoreMessages.PROMPT_REQUEST, prompt(PromptKind.TEXT, "", 1_000, "hud"));
            errorResponse(p, detached, "not_attached", CoreMessages.PROMPT_REQUEST);

            h.core.attach().setChoice(IDENTITY, true);
            h.core.setPlayerContext(NETHER);
            long block = p.reliableRequest(s, CoreMessages.PICK_REQUEST, pick(PickKind.BLOCK, 1_000, false));
            errorResponse(p, block, "world_mismatch", CoreMessages.PICK_REQUEST);
            long worldPrompt = p.reliableRequest(s, CoreMessages.PROMPT_REQUEST,
                    prompt(PromptKind.CONFIRM, true, 1_000, "world"));
            errorResponse(p, worldPrompt, "world_mismatch", CoreMessages.PROMPT_REQUEST);

            long item = p.reliableRequest(s, CoreMessages.PICK_REQUEST, pick(PickKind.ITEM, 60_000, false));
            long hudPrompt = p.reliableRequest(s, CoreMessages.PROMPT_REQUEST,
                    prompt(PromptKind.NUMBER, 3, 60_000, "hud"));
            long form = p.reliableRequest(s, CoreMessages.FORM_OPEN, farmForm());
            long last = p.reliableRequest(s, CoreMessages.PROMPT_REQUEST, prompt(PromptKind.CONFIRM, true, 60_000, "hud"));
            p.sync();
            assertEquals(PickKind.ITEM, assertInstanceOf(PickInput.class, active(h)).request().kind(),
                    "item picks and HUD prompts are not world-scoped");
            assertEquals(3, h.core.input().queued());

            // The bot withdraws the active pick and a queued form; neither gets a response.
            p.reliable(s, CoreMessages.PICK_CANCEL, new PickCancel((int) item));
            p.reliable(s, CoreMessages.FORM_CLOSE, new FormClose((int) form));
            p.sync();
            PromptInput number = assertInstanceOf(PromptInput.class, active(h));
            assertEquals(1, h.core.input().queued());
            p.reliable(s, CoreMessages.PROMPT_CANCEL, new PromptCancel((int) hudPrompt));
            p.sync();
            PromptInput remaining = assertInstanceOf(PromptInput.class, active(h));
            assertEquals(PromptKind.CONFIRM, remaining.request().kind());
            assertEquals(0, h.core.input().queued());
            assertFalse(p.poll(f -> f.env().req() == item || f.env().req() == form || f.env().req() == hudPrompt),
                    "withdrawn requests get no response: " + p.describe());
            assertTrue(number.id() < active(h).id());

            // A queued request is re-checked when it becomes active.
            long queuedItem = p.reliableRequest(s, CoreMessages.PICK_REQUEST, pick(PickKind.ITEM, 60_000, false));
            p.sync();
            h.core.attach().setChoice(IDENTITY, false);
            h.tick();
            h.core.input().resolvePrompt(active(h).id(), false);
            response(p, CoreMessages.PROMPT_RESULT, last);
            errorResponse(p, queuedItem, "not_attached", CoreMessages.PICK_REQUEST);
            assertNull(h.core.input().active());
        }
    }

    @Test
    void aRefusedRequestNamesTheMissingPermissionInItsDetails() throws Exception {
        try (Harness h = new Harness(dir); FakePlugin p = FakePlugin.ready(h, "dev")) {
            int s = matchedBot(h, p, "Miner");
            h.core.devices().setPermission("dev", Permission.INPUT_PICK, false);
            long req = p.reliableRequest(s, CoreMessages.PROMPT_REQUEST, prompt(PromptKind.CONFIRM, false, 30_000, "hud"));
            FakePlugin.Frame f = p.await("error for req " + req, x -> x.is(CoreMessages.ERROR) && x.env().req() == req);
            ErrorMessage m = f.decode(CoreMessages.ERROR);
            assertEquals("permission_denied", m.error().code());
            assertEquals(Map.of("permission", "input.pick"), m.error().details(), "a bot reads which permission from the details");
            assertNull(h.core.input().active());
        }
    }

    @Test
    void endedSessionsDropTheirRequests() throws Exception {
        try (Harness h = new Harness(dir); FakePlugin p = FakePlugin.ready(h, "dev")) {
            int a = matchedBot(h, p, "Miner");
            int b = matchedBot(h, p, "Farmer");
            p.reliableRequest(a, CoreMessages.PICK_REQUEST, pick(PickKind.POINT, 60_000, false));
            p.reliableRequest(b, CoreMessages.PROMPT_REQUEST, prompt(PromptKind.CONFIRM, true, 120_000, "hud"));
            p.reliableRequest(a, CoreMessages.FORM_OPEN, farmForm());
            p.sync();
            assertEquals("Miner", active(h).ownerName());
            assertEquals(2, h.core.input().queued());

            p.control(a, CoreMessages.SESSION_CLOSE, new SessionClose("done"));
            p.sync();
            h.tick();
            assertEquals("Farmer", active(h).ownerName());
            assertEquals(0, h.core.input().queued(), "the closed session's queued form is gone too");

            p.abort();
            h.tickUntil("detached", () -> h.core.sessionStats().stream().noneMatch(SessionStats::connected));
            assertEquals("Farmer", active(h).ownerName(), "kept during the resume window");
            h.clock.advanceMs(30_001);
            h.tick();
            assertNull(h.core.input().active(), "resume window expiry drops the request");
        }
    }
}
