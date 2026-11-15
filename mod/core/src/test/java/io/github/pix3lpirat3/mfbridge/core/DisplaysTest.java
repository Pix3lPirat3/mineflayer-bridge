package io.github.pix3lpirat3.mfbridge.core;

import static io.github.pix3lpirat3.mfbridge.core.InputTestSupport.BOT_WORLD;
import static io.github.pix3lpirat3.mfbridge.core.InputTestSupport.IDENTITY;
import static io.github.pix3lpirat3.mfbridge.core.InputTestSupport.NETHER;
import static io.github.pix3lpirat3.mfbridge.core.InputTestSupport.P;
import static io.github.pix3lpirat3.mfbridge.core.InputTestSupport.PLAYER_WORLD;
import static io.github.pix3lpirat3.mfbridge.core.InputTestSupport.errorResponse;
import static io.github.pix3lpirat3.mfbridge.core.InputTestSupport.matchedBot;
import static io.github.pix3lpirat3.mfbridge.core.InputTestSupport.obj;
import static io.github.pix3lpirat3.mfbridge.core.InputTestSupport.response;
import static io.github.pix3lpirat3.mfbridge.core.InputTestSupport.worldObj;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.pix3lpirat3.mfbridge.core.container.ContainerCloseRequested;
import io.github.pix3lpirat3.mfbridge.core.effect.EffectRequested;
import io.github.pix3lpirat3.mfbridge.core.input.InputControls;
import io.github.pix3lpirat3.mfbridge.core.input.PromptInput;
import io.github.pix3lpirat3.mfbridge.core.input.WorldMenuInput;
import io.github.pix3lpirat3.mfbridge.protocol.core.ContainerClose;
import io.github.pix3lpirat3.mfbridge.protocol.core.ContainerOpen;
import io.github.pix3lpirat3.mfbridge.protocol.core.CoreKinds;
import io.github.pix3lpirat3.mfbridge.protocol.core.CoreMessages;
import io.github.pix3lpirat3.mfbridge.protocol.core.DisplayEntityData;
import io.github.pix3lpirat3.mfbridge.protocol.core.DisplayItemData;
import io.github.pix3lpirat3.mfbridge.protocol.core.DisplayTextData;
import io.github.pix3lpirat3.mfbridge.protocol.core.EffectActionBar;
import io.github.pix3lpirat3.mfbridge.protocol.core.EffectBody;
import io.github.pix3lpirat3.mfbridge.protocol.core.EffectParticles;
import io.github.pix3lpirat3.mfbridge.protocol.core.EffectPlay;
import io.github.pix3lpirat3.mfbridge.protocol.core.EffectSound;
import io.github.pix3lpirat3.mfbridge.protocol.core.ElementEvent;
import io.github.pix3lpirat3.mfbridge.protocol.core.ElementInput;
import io.github.pix3lpirat3.mfbridge.protocol.core.ErrorMessage;
import io.github.pix3lpirat3.mfbridge.protocol.core.GroupData;
import io.github.pix3lpirat3.mfbridge.protocol.core.Interactive;
import io.github.pix3lpirat3.mfbridge.protocol.core.ObjRemove;
import io.github.pix3lpirat3.mfbridge.protocol.core.ObjUpsert;
import io.github.pix3lpirat3.mfbridge.protocol.core.ObjectFull;
import io.github.pix3lpirat3.mfbridge.protocol.core.ObjectWrite;
import io.github.pix3lpirat3.mfbridge.protocol.core.ParticleEmitterData;
import io.github.pix3lpirat3.mfbridge.protocol.core.PromptKind;
import io.github.pix3lpirat3.mfbridge.protocol.core.PromptResult;
import io.github.pix3lpirat3.mfbridge.protocol.core.SlotItem;
import io.github.pix3lpirat3.mfbridge.protocol.core.Vec3d;
import io.github.pix3lpirat3.mfbridge.protocol.core.WorldMenuCancel;
import io.github.pix3lpirat3.mfbridge.protocol.core.WorldMenuEntry;
import io.github.pix3lpirat3.mfbridge.protocol.core.WorldMenuRequest;
import io.github.pix3lpirat3.mfbridge.protocol.core.WorldMenuResult;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Milestone 5 core behaviour: element events, world menus, effects, prompt surfaces and quotas. */
class DisplaysTest {
    static final String OFFER = P + "test:demo/ui/offer";
    static final String TITLE = P + "test:demo/ui/title";
    static final Vec3d POS = new Vec3d(1, 65, 2);

    @TempDir
    Path dir;

    static DisplayTextData text(String text, @Nullable Interactive interactive) {
        return new DisplayTextData(POS, text, 200, 0x40000000, false, false, "center", 1, null, null, "fixed", 0, null,
                0.1f, 2000, null, null, null, interactive, 64);
    }

    static DisplayItemData item(@Nullable Interactive interactive) {
        return new DisplayItemData(POS, new SlotItem("minecraft:diamond"), "ground", 1,
                null, null, "fixed", 0, null, 0.1f, 2000, null, null, null, interactive, 64);
    }

    static DisplayEntityData entity() {
        return new DisplayEntityData(POS, "minecraft:cow");
    }

    static ParticleEmitterData emitter() {
        return new ParticleEmitterData(POS, "minecraft:flame");
    }

    static WorldMenuRequest menu(int timeoutMs, String... ids) {
        List<WorldMenuEntry> entries = new ArrayList<>();
        for (String id : ids) {
            entries.add(new WorldMenuEntry(id, id.toUpperCase(java.util.Locale.ROOT), null, null, null, id));
        }
        return new WorldMenuRequest(new Vec3d(0, 66, 0), entries, "ring", 1.5f, "Which one?", timeoutMs);
    }

    @Test
    void elementEventsReachTheOwnerOnlyForDeclaredInputs() throws Exception {
        try (Harness h = new Harness(dir); FakePlugin p = FakePlugin.ready(h, "dev")) {
            int s = matchedBot(h, p, "Miner");
            Interactive spec = new Interactive(List.of(ElementInput.PRIMARY, ElementInput.HOVER_ENTER,
                    ElementInput.KEY, ElementInput.DRAG_START), "Left: take one", List.of("key.keyboard.q"), null, null,
                    null);
            p.upsert(s, worldObj(OFFER, CoreKinds.DISPLAY_ITEM, BOT_WORLD, item(spec)),
                    worldObj(TITLE, CoreKinds.DISPLAY_TEXT, BOT_WORLD, text("Shop", null)));
            p.sync();
            h.tick();

            Vec3d local = new Vec3d(0.1, 0.2, 0.3);
            h.core.elementEvent(OFFER, ElementInput.SECONDARY, null, null, local, 0);
            h.core.elementEvent(OFFER, ElementInput.KEY, null, "key.keyboard.e", null, 0);
            h.core.elementEvent(TITLE, ElementInput.PRIMARY, null, null, local, 0);
            h.core.elementEvent(P + "test:demo/ui/none", ElementInput.PRIMARY, null, null, local, 0);
            h.core.elementEvent(OFFER, ElementInput.PRIMARY, null, null, local, 3);
            assertEquals(new ElementEvent(OFFER, ElementInput.PRIMARY, null, null, local, 3),
                    p.await(CoreMessages.ELEMENT_EVENT, s));
            h.core.elementEvent(OFFER, ElementInput.HOVER_LEAVE, null, null, null, 0);
            assertEquals(ElementInput.HOVER_LEAVE, p.await(CoreMessages.ELEMENT_EVENT, s).input(),
                    "hover leave pairs with a declared hover enter");
            h.core.elementEvent(OFFER, ElementInput.DRAG, null, null, local, 0);
            assertEquals(ElementInput.DRAG, p.await(CoreMessages.ELEMENT_EVENT, s).input());
            h.core.elementEvent(OFFER, ElementInput.KEY, null, "key.keyboard.q", null, 0);
            assertEquals("key.keyboard.q", p.await(CoreMessages.ELEMENT_EVENT, s).key());
            p.sync();
            assertFalse(p.poll(f -> f.is(CoreMessages.ELEMENT_EVENT)), "undeclared inputs are dropped: " + p.describe());

            h.core.setPlayerContext(NETHER);
            h.tick();
            h.core.elementEvent(OFFER, ElementInput.PRIMARY, null, null, local, 0);
            h.core.setPlayerContext(PLAYER_WORLD);
            h.tick();
            h.core.attach().setChoice(IDENTITY, false);
            h.tick();
            h.core.elementEvent(OFFER, ElementInput.PRIMARY, null, null, local, 0);
            p.sync();
            assertFalse(p.poll(f -> f.is(CoreMessages.ELEMENT_EVENT)), "out of world or detached: dropped");
        }
    }

    @Test
    void worldMenusQueueWithPromptsAndResolveCancelOrTimeOut() throws Exception {
        try (Harness h = ResumeTest.always(dir, b -> {}); FakePlugin p = FakePlugin.ready(h, "dev")) {
            int s = matchedBot(h, p, "Miner");
            InputControls in = h.core.input();
            long prompt = p.reliableRequest(s, CoreMessages.PROMPT_REQUEST,
                    InputQueueTest.prompt(PromptKind.CONFIRM, false, 30_000, "hud"));
            long first = p.reliableRequest(s, CoreMessages.WORLD_MENU_REQUEST, menu(60_000, "a", "b"));
            long second = p.reliableRequest(s, CoreMessages.WORLD_MENU_REQUEST, menu(60_000, "c"));
            long third = p.reliableRequest(s, CoreMessages.WORLD_MENU_REQUEST, menu(1_000, "d"));
            p.sync();
            assertEquals(3, in.queued());

            PromptInput confirm = assertInstanceOf(PromptInput.class, in.active());
            in.resolvePrompt(confirm.id(), true, "dialog");
            assertEquals(new PromptResult(true, false, "dialog"), response(p, CoreMessages.PROMPT_RESULT, prompt));

            WorldMenuInput m = assertInstanceOf(WorldMenuInput.class, in.active());
            assertEquals("Miner", m.ownerName());
            assertEquals(m.createdAtMs() + 60_000, m.deadlineMs());
            assertThrows(IllegalArgumentException.class, () -> in.resolveWorldMenu(m.id(), "zzz"));
            assertThrows(IllegalArgumentException.class, () -> in.resolvePrompt(m.id(), true));
            in.resolveWorldMenu(m.id(), "b");
            assertEquals(new WorldMenuResult("b"), response(p, CoreMessages.WORLD_MENU_RESULT, first));

            p.reliable(s, CoreMessages.WORLD_MENU_CANCEL, new WorldMenuCancel((int) second));
            p.sync();
            WorldMenuInput timed = assertInstanceOf(WorldMenuInput.class, in.active());
            assertEquals("d", timed.request().entries().get(0).id(), "a withdrawn menu leaves the queue");
            assertFalse(p.poll(f -> f.env().req() == second), "withdrawn menus get no response");
            h.clock.advanceMs(1_000);
            errorResponse(p, third, "timeout", CoreMessages.WORLD_MENU_REQUEST);
            assertNull(in.active());

            long cancelled = p.reliableRequest(s, CoreMessages.WORLD_MENU_REQUEST, menu(60_000, "e"));
            p.sync();
            in.cancel(Objects.requireNonNull(in.active()).id());
            errorResponse(p, cancelled, "cancelled", CoreMessages.WORLD_MENU_REQUEST);

            long empty = p.reliableRequest(s, CoreMessages.WORLD_MENU_REQUEST, menu(60_000));
            errorResponse(p, empty, "invalid_message", CoreMessages.WORLD_MENU_REQUEST);

            h.core.setPlayerContext(NETHER);
            long elsewhere = p.reliableRequest(s, CoreMessages.WORLD_MENU_REQUEST, menu(60_000, "f"));
            errorResponse(p, elsewhere, "world_mismatch", CoreMessages.WORLD_MENU_REQUEST);
        }
    }

    @Test
    void promptResultsReportTheSurfaceShown() throws Exception {
        try (Harness h = new Harness(dir); FakePlugin p = FakePlugin.ready(h, "dev")) {
            int s = matchedBot(h, p, "Miner");
            InputControls in = h.core.input();
            long timed = p.reliableRequest(s, CoreMessages.PROMPT_REQUEST,
                    InputQueueTest.prompt(PromptKind.CONFIRM, false, 1_000, "hud"));
            long unknown = p.reliableRequest(s, CoreMessages.PROMPT_REQUEST,
                    InputQueueTest.prompt(PromptKind.CONFIRM, true, 1_000, "hud"));
            long shown = p.reliableRequest(s, CoreMessages.PROMPT_REQUEST,
                    InputQueueTest.prompt(PromptKind.CONFIRM, true, 60_000, "hud"));
            p.sync();

            in.surfaceShown(Objects.requireNonNull(in.active()).id(), "anvil");
            h.clock.advanceMs(1_000);
            assertEquals(new PromptResult(false, true, "anvil"), response(p, CoreMessages.PROMPT_RESULT, timed));
            h.clock.advanceMs(1_000);
            assertEquals(new PromptResult(true, true, null), response(p, CoreMessages.PROMPT_RESULT, unknown),
                    "a prompt never shown reports no surface");

            long id = Objects.requireNonNull(in.active()).id();
            in.surfaceShown(id, "world");
            in.resolvePrompt(id, false);
            assertEquals(new PromptResult(false, false, "world"), response(p, CoreMessages.PROMPT_RESULT, shown),
                    "the two-argument overload reports the surface last shown");
        }
    }

    @Test
    void effectsArePostedForAttachedBotsAndRateLimited() throws Exception {
        try (Harness h = ResumeTest.always(dir, b -> {}); FakePlugin p = FakePlugin.ready(h, "dev")) {
            int s = matchedBot(h, p, "Miner");
            List<EffectRequested> played = new ArrayList<>();
            h.core.events().subscribe(EffectRequested.TYPE, played::add);
            EffectBody bar = new EffectBody.Actionbar(new EffectActionBar("Mining"));
            for (int i = 0; i < EffectRequested.MAX_PER_SECOND + 5; i++) {
                p.control(s, CoreMessages.EFFECT_PLAY, new EffectPlay(bar));
            }
            p.sync();
            assertEquals(EffectRequested.MAX_PER_SECOND, played.size(), "effects beyond the rate are dropped");
            assertEquals(new EffectRequested("Miner", bar), played.get(0));

            h.clock.advanceMs(1_000);
            EffectBody sparks = new EffectBody.Particles(new EffectParticles("minecraft:flame", POS, 4, null, 0, null));
            p.control(s, CoreMessages.EFFECT_PLAY, new EffectPlay(sparks));
            p.sync();
            assertEquals(sparks, played.get(played.size() - 1).effect(), "a new second admits effects again");

            h.core.setPlayerContext(NETHER);
            h.tick();
            int before = played.size();
            p.control(s, CoreMessages.EFFECT_PLAY, new EffectPlay(sparks));
            p.control(s, CoreMessages.EFFECT_PLAY,
                    new EffectPlay(new EffectBody.Sound(new EffectSound("minecraft:block.note_block.pling").withAt(POS))));
            EffectBody ui = new EffectBody.Sound(new EffectSound("minecraft:ui.button.click"));
            p.control(s, CoreMessages.EFFECT_PLAY, new EffectPlay(ui));
            p.sync();
            assertEquals(List.of(ui), played.subList(before, played.size()).stream().map(EffectRequested::effect).toList(),
                    "positional effects need the bot's world");

            h.core.setPlayerContext(PLAYER_WORLD);
            h.core.attach().setChoice(IDENTITY, false);
            h.tick();
            p.control(s, CoreMessages.EFFECT_PLAY, new EffectPlay(bar));
            p.sync();
            assertEquals(before + 1, played.size(), "detached bots play nothing");
        }
    }

    @Test
    void containerOpenRequestsLapseAfterThirtySeconds() throws Exception {
        try (Harness h = ResumeTest.always(dir, b -> {}); FakePlugin p = FakePlugin.ready(h, "dev")) {
            int s = ContainerViewTest.declare(h, p);
            List<ContainerCloseRequested> closes = new ArrayList<>();
            h.core.events().subscribe(ContainerCloseRequested.TYPE, closes::add);
            p.reliable(s, CoreMessages.CONTAINER_OPEN, new ContainerOpen(ContainerViewTest.VIEW));
            p.sync();
            assertEquals(ContainerViewTest.VIEW, h.core.containers().requestedOpen());

            // Advanced in steps with pings between, so the peer is not timed out as dead.
            h.clock.advanceMs(9_999);
            p.sync();
            h.clock.advanceMs(10_000);
            p.sync();
            h.clock.advanceMs(10_000);
            p.sync();
            assertEquals(ContainerViewTest.VIEW, h.core.containers().requestedOpen());
            h.clock.advanceMs(1);
            h.tick();
            assertNull(h.core.containers().requestedOpen(), "a stale request is dropped");
            assertEquals(new ContainerClose(ContainerViewTest.VIEW, true), p.await(CoreMessages.CONTAINER_CLOSE, s));
            assertEquals(List.of(new ContainerCloseRequested(ContainerViewTest.VIEW)), closes);
        }
    }

    @Test
    void displayQuotasRejectWritesBeyondTheLimit() throws Exception {
        try (Harness h = ResumeTest.always(dir, b -> {}); FakePlugin p = FakePlugin.ready(h, "dev")) {
            int s = p.openBot("Miner", "example.com").sessionId();
            String base = P + "test:demo/fx/";

            List<ObjectFull> emitters = new ArrayList<>();
            for (int i = 0; i < 32; i++) {
                emitters.add(obj(base + "emitter" + i, CoreKinds.PARTICLE_EMITTER, emitter()));
            }
            upsertAll(p, s, emitters);
            p.upsert(s, obj(base + "emitter32", CoreKinds.PARTICLE_EMITTER, emitter()));
            assertQuotaError(p, s, "particle emitter");
            p.upsert(s, FakePlugin.object(base + "emitter0", CoreKinds.PARTICLE_EMITTER, 2,
                    CoreKinds.PARTICLE_EMITTER.encode(emitter())));
            p.upsert(s, obj(base + "group", CoreKinds.GROUP, new GroupData(POS, null, true, "fixed", 0, null)));
            p.sync();
            h.tick();
            assertFalse(p.poll(f -> f.is(CoreMessages.ERROR)), "rewrites and groups are not limited: " + p.describe());
            assertNull(entry(h, base + "emitter32"), "a rejected write is not stored");

            List<ObjectFull> entities = new ArrayList<>();
            for (int i = 0; i < 256; i++) {
                entities.add(obj(base + "cow" + i, CoreKinds.DISPLAY_ENTITY, entity()));
            }
            upsertAll(p, s, entities);
            p.upsert(s, obj(base + "cow256", CoreKinds.DISPLAY_ENTITY, entity()));
            assertQuotaError(p, s, "entity display");
            p.upsert(s, FakePlugin.object(base + "cow0", CoreKinds.DISPLAY_TEXT, 2,
                    CoreKinds.DISPLAY_TEXT.encode(text("was a cow", null))));
            p.upsert(s, obj(base + "cow256", CoreKinds.DISPLAY_ENTITY, entity()));
            p.sync();
            h.tick();
            assertFalse(p.poll(f -> f.is(CoreMessages.ERROR)), "replacing an entity frees a slot: " + p.describe());

            List<ObjectFull> texts = new ArrayList<>();
            for (int i = 0; i < 4096 - 257; i++) {
                texts.add(obj(base + "t" + i, CoreKinds.DISPLAY_TEXT, text("line " + i, null)));
            }
            upsertAll(p, s, texts);
            p.upsert(s, obj(base + "extra", CoreKinds.DISPLAY_ITEM, item(null)));
            assertQuotaError(p, s, "display");
            p.upsert(s, FakePlugin.object(base + "t0", CoreKinds.DISPLAY_ITEM, 2, CoreKinds.DISPLAY_ITEM.encode(item(null))));
            p.reliable(s, CoreMessages.OBJ_REMOVE, new ObjRemove(List.of(base + "t1"), List.of()));
            p.upsert(s, obj(base + "extra", CoreKinds.DISPLAY_ITEM, item(null)));
            p.sync();
            h.tick();
            assertFalse(p.poll(f -> f.is(CoreMessages.ERROR)), "a removal frees a display slot: " + p.describe());
            assertTrue(entry(h, base + "extra") != null);
        }
    }

    private static void upsertAll(FakePlugin p, int s, List<ObjectFull> objects) {
        for (int from = 0; from < objects.size(); from += 256) {
            List<ObjectWrite> writes = new ArrayList<>();
            for (ObjectFull o : objects.subList(from, Math.min(objects.size(), from + 256))) {
                writes.add(new ObjectWrite.Full(o));
            }
            p.reliable(s, CoreMessages.OBJ_UPSERT, new ObjUpsert(writes));
        }
        p.sync();
        assertFalse(p.poll(f -> f.is(CoreMessages.ERROR)), "within quota: " + p.describe());
    }

    private static void assertQuotaError(FakePlugin p, int s, String what) {
        ErrorMessage e = p.await(CoreMessages.ERROR, s);
        assertEquals("quota_exceeded", e.error().code());
        assertEquals(CoreMessages.OBJ_UPSERT.id(), e.relatesTo());
        assertTrue(e.error().message().startsWith(what), e.error().message());
    }

    private static @Nullable RenderEntry entry(Harness h, String key) {
        for (RenderEntry e : h.core.renderSnapshot().entries()) {
            if (e.key().equals(key)) {
                return e;
            }
        }
        return null;
    }
}
