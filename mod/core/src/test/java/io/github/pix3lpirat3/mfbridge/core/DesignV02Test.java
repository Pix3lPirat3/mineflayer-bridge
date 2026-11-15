package io.github.pix3lpirat3.mfbridge.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.pix3lpirat3.mfbridge.core.vars.TemplateCache;
import io.github.pix3lpirat3.mfbridge.core.vars.Variables;
import io.github.pix3lpirat3.mfbridge.protocol.core.CoreKinds;
import io.github.pix3lpirat3.mfbridge.protocol.core.CoreMessages;
import io.github.pix3lpirat3.mfbridge.protocol.core.EditableField;
import io.github.pix3lpirat3.mfbridge.protocol.core.ElementInput;
import io.github.pix3lpirat3.mfbridge.protocol.core.ErrorMessage;
import io.github.pix3lpirat3.mfbridge.protocol.core.FocusRequest;
import io.github.pix3lpirat3.mfbridge.protocol.core.Interactive;
import io.github.pix3lpirat3.mfbridge.protocol.core.Interest;
import io.github.pix3lpirat3.mfbridge.protocol.core.Lifetime;
import io.github.pix3lpirat3.mfbridge.protocol.core.ObjBegin;
import io.github.pix3lpirat3.mfbridge.protocol.core.ObjCommit;
import io.github.pix3lpirat3.mfbridge.protocol.core.ObjRemove;
import io.github.pix3lpirat3.mfbridge.protocol.core.ObjUpsert;
import io.github.pix3lpirat3.mfbridge.protocol.core.ObjectFull;
import io.github.pix3lpirat3.mfbridge.protocol.core.ObjectPatch;
import io.github.pix3lpirat3.mfbridge.protocol.core.ObjectTraits;
import io.github.pix3lpirat3.mfbridge.protocol.core.ObjectWrite;
import io.github.pix3lpirat3.mfbridge.protocol.core.Scope;
import io.github.pix3lpirat3.mfbridge.protocol.core.SessionClose;
import io.github.pix3lpirat3.mfbridge.protocol.core.VarsData;
import io.github.pix3lpirat3.mfbridge.protocol.core.Visibility;
import io.github.pix3lpirat3.mfbridge.core.persist.PersistedObject;
import io.github.pix3lpirat3.mfbridge.core.store.PatchApplier;
import io.github.pix3lpirat3.mfbridge.protocol.core.LabelData;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Traits, focus, variables, transactions and local approval through the engine. */
class DesignV02Test {
    private static final String MINER = "bot:miner@example.com";
    private static final String DIGGER = "bot:digger@example.com";
    private static final ObjectTraits CLICKABLE = new ObjectTraits(new Interactive(List.of(ElementInput.PRIMARY)), null,
            List.of(new EditableField("text", null, "Text")), List.of("open"), null);
    private static final ObjectTraits FOCUSED_ONLY = new ObjectTraits(null, null, null, null,
            new Visibility(null, null, true, false));

    @TempDir
    Path dir;

    private static ObjectFull traited(String key, long rev, byte[] data, ObjectTraits traits) {
        return new ObjectFull(key, CoreKinds.LABEL.id(), rev, null, Lifetime.SESSION, Scope.GLOBAL, null, null, data, traits);
    }

    private static boolean has(Harness h, String key) {
        for (RenderEntry e : h.core.renderSnapshot().entries()) {
            if (e.key().equals(key)) {
                return true;
            }
        }
        return false;
    }

    private static void patch(FakePlugin p, int s, String key, long rev, byte[] fields, ObjectTraits traits) {
        p.reliable(s, CoreMessages.OBJ_UPSERT, new ObjUpsert(List.of(new ObjectWrite.Patch(
                new ObjectPatch(key, rev, fields, null, traits)))));
    }

    @Test
    void traitsAreStoredWithFullWritesAndReplacedByPatchesThatCarryThem() throws Exception {
        try (Harness h = ObjectSyncTest.always(dir); FakePlugin p = FakePlugin.ready(h, "dev")) {
            int s = p.openBot("Miner", "example.com").sessionId();
            String key = MINER + "/e/l";
            p.upsert(s, traited(key, 1, ObjectSyncTest.label("hi"), CLICKABLE));
            p.sync();
            RenderEntry e = ObjectSyncTest.entry(h, key);
            assertEquals(CLICKABLE, e.traits());
            assertEquals(CLICKABLE.interactive(), e.interactive());

            patch(p, s, key, 2, ObjectSyncTest.patch(1, "bye"), null);
            p.sync();
            e = ObjectSyncTest.entry(h, key);
            assertEquals("bye", ((LabelData) e.data()).text());
            assertEquals(CLICKABLE, e.traits(), "a patch without traits keeps them");

            ObjectTraits other = new ObjectTraits(null, null, null, List.of("inspect"), null);
            patch(p, s, key, 3, ObjectSyncTest.patch(1, "again"), other);
            p.sync();
            e = ObjectSyncTest.entry(h, key);
            assertEquals(other, e.traits());
            assertNull(e.interactive());
        }
    }

    @Test
    void persistedObjectsKeepTheirTraits() {
        PersistedObject o = new PersistedObject("shared/e/a", "core:label", 2, null, Lifetime.PERSISTENT, Scope.GLOBAL, null,
                ObjectSyncTest.label("x"), CLICKABLE);
        assertEquals(CLICKABLE, PersistedObject.decodePut(o.encodePut()).traits());
        PersistedObject plain = new PersistedObject("shared/e/b", "core:label", 1, null, Lifetime.PERSISTENT, Scope.GLOBAL, null,
                ObjectSyncTest.label("y"));
        assertNull(PersistedObject.decodePut(plain.encodePut()).traits());
    }

    @Test
    void fieldPatchesCarryOnlyTheEditedFields() {
        byte[] data = ObjectSyncTest.label("hi");
        byte[] edited = ObjectSyncTest.label("edited");
        byte[] patched = PatchApplier.apply(data, PatchApplier.fieldsPatch(edited, 1));
        assertEquals("edited", CoreKinds.LABEL.decode(patched).text());
    }

    @Test
    void focusDrivesInterestAndFocusedOnlyVisibility() throws Exception {
        try (Harness h = ObjectSyncTest.always(dir); FakePlugin p = FakePlugin.ready(h, "dev")) {
            int miner = p.openBot("Miner", "example.com").sessionId();
            awaitFocused(p, miner, true);
            int digger = p.openBot("Digger", "example.com").sessionId();
            awaitFocused(p, digger, false);
            assertEquals(MINER, h.core.focus().focused(), "a new bot does not take focus");
            h.core.focus().cycle(-1);
            assertEquals(DIGGER, h.core.focus().focused());
            awaitFocused(p, digger, true);
            awaitFocused(p, miner, false);

            p.upsert(miner, traited(MINER + "/e/only", 1, ObjectSyncTest.label("x"), FOCUSED_ONLY));
            p.sync();
            assertFalse(has(h, MINER + "/e/only"), "hidden while another bot is focused");
            h.core.focus().cycle(1);
            assertEquals(MINER, h.core.focus().focused());
            h.tickUntil("shown once focused", () -> has(h, MINER + "/e/only"));
            awaitFocused(p, miner, true);
            awaitFocused(p, digger, false);

            p.control(digger, CoreMessages.FOCUS_REQUEST, new FocusRequest("look"));
            h.tickUntil("bot request moves focus", () -> DIGGER.equals(h.core.focus().focused()));
            h.core.focus().focus(MINER);
            p.control(digger, CoreMessages.FOCUS_REQUEST, new FocusRequest());
            h.tickFor(50);
            assertEquals(MINER, h.core.focus().focused(), "one request per 5 s");
            h.clock.advanceMs(FocusControls.REQUEST_INTERVAL_MS);
            h.core.focus().setAllowBotRequests(false);
            p.control(digger, CoreMessages.FOCUS_REQUEST, new FocusRequest());
            h.tickFor(50);
            assertEquals(MINER, h.core.focus().focused(), "the player turned requests off");
        }
    }

    private static void awaitFocused(FakePlugin p, int session, boolean focused) {
        p.await("interest focused=" + focused + " on " + session, f -> f.is(CoreMessages.INTEREST)
                && f.env().session() == session && f.decode(CoreMessages.INTEREST).focused() == focused);
    }

    @Test
    void variablesArePublishedPerOwnerAndExtension() throws Exception {
        try (Harness h = ObjectSyncTest.always(dir); FakePlugin p = FakePlugin.ready(h, "dev")) {
            int s = p.openBot("Miner", "example.com").sessionId();
            p.upsert(s, FakePlugin.object(MINER + "/bridge:mine/~vars", CoreKinds.VARS, 1,
                            CoreKinds.VARS.encode(new VarsData(Map.of("done", 3L, "task", "dig")))),
                    FakePlugin.object(MINER + "/user:default/~vars", CoreKinds.VARS, 1,
                            CoreKinds.VARS.encode(new VarsData(Map.of("total", 9L)))));
            h.tickUntil("variables", () -> h.core.variables().lookup(MINER, "bridge:mine", "total") != Variables.MISSING);
            Variables v = h.core.variables();
            assertFalse(has(h, MINER + "/bridge:mine/~vars"), "variables are not render entries");
            TemplateCache cache = new TemplateCache();
            String text = "{task} {done}/{total} {other}";
            assertEquals("dig 3/9 {other}", cache.resolve(text, MINER + "/bridge:mine/~hud/p/w", v));

            p.upsert(s, FakePlugin.object(MINER + "/bridge:mine/~vars", CoreKinds.VARS, 2,
                    CoreKinds.VARS.encode(new VarsData(Map.of("done", 4L, "task", "dig")))));
            h.tickUntil("new version", () -> h.core.variables().version() > v.version());
            assertEquals("dig 4/9 {other}", cache.resolve(text, MINER + "/bridge:mine/~hud/p/w", h.core.variables()));

            p.upsert(s, FakePlugin.object(MINER + "/bridge:mine/elsewhere", CoreKinds.VARS, 1,
                    CoreKinds.VARS.encode(new VarsData(Map.of()))));
            assertEquals("invalid_message", p.await(CoreMessages.ERROR, s).error().code());

            p.reliable(s, CoreMessages.OBJ_REMOVE, new ObjRemove(List.of(MINER + "/user:default/~vars"), List.of()));
            h.tickUntil("removed", () -> h.core.variables().lookup(MINER, "bridge:mine", "total") == Variables.MISSING);
        }
    }

    @Test
    void transactionsApplyAtCommitAndRejectNesting() throws Exception {
        try (Harness h = ObjectSyncTest.always(dir); FakePlugin p = FakePlugin.ready(h, "dev")) {
            int s = p.openBot("Miner", "example.com").sessionId();
            p.reliable(s, CoreMessages.OBJ_BEGIN, new ObjBegin(7));
            p.upsert(s, FakePlugin.object(MINER + "/e/a", CoreKinds.BOX, 1, ObjectSyncTest.box(1)));
            p.upsert(s, FakePlugin.object(MINER + "/e/b", CoreKinds.BOX, 1, ObjectSyncTest.box(2)));
            p.reliable(s, CoreMessages.OBJ_BEGIN, new ObjBegin(8));
            ErrorMessage nested = p.await(CoreMessages.ERROR, s);
            assertEquals("invalid_message", nested.error().code());
            p.reliable(s, CoreMessages.OBJ_COMMIT, new ObjCommit(8));
            assertEquals("invalid_message", p.await(CoreMessages.ERROR, s).error().code());
            p.sync();
            h.tickFor(30);
            assertFalse(has(h, MINER + "/e/a") || has(h, MINER + "/e/b"), "nothing before commit");
            p.reliable(s, CoreMessages.OBJ_COMMIT, new ObjCommit(7));
            h.tickUntil("either", () -> has(h, MINER + "/e/a") || has(h, MINER + "/e/b"));
            assertTrue(has(h, MINER + "/e/a") && has(h, MINER + "/e/b"), "the first snapshot after commit has both");
            p.reliable(s, CoreMessages.OBJ_COMMIT, new ObjCommit(7));
            assertEquals("invalid_message", p.await(CoreMessages.ERROR, s).error().code());
        }
    }

    @Test
    void openTransactionsTimeOutOrEndWithTheSession() throws Exception {
        try (Harness h = ObjectSyncTest.always(dir); FakePlugin p = FakePlugin.ready(h, "dev")) {
            int s = p.openBot("Miner", "example.com").sessionId();
            p.reliable(s, CoreMessages.OBJ_BEGIN, new ObjBegin(1));
            p.upsert(s, FakePlugin.object(MINER + "/e/a", CoreKinds.BOX, 1, ObjectSyncTest.box(1)));
            p.sync();
            h.clock.advanceMs(10_000);
            ErrorMessage timeout = p.await(CoreMessages.ERROR, s);
            assertEquals("timeout", timeout.error().code());
            assertEquals(CoreMessages.OBJ_BEGIN.id(), timeout.relatesTo());
            p.reliable(s, CoreMessages.OBJ_COMMIT, new ObjCommit(1));
            assertEquals("invalid_message", p.await(CoreMessages.ERROR, s).error().code());
            assertFalse(has(h, MINER + "/e/a"));

            p.reliable(s, CoreMessages.OBJ_BEGIN, new ObjBegin(2));
            p.upsert(s, FakePlugin.object(MINER + "/e/b", CoreKinds.BOX, 1, ObjectSyncTest.box(1)));
            p.reliable(s, CoreMessages.SESSION_CLOSE, new SessionClose("done"));
            h.tickUntil("session ended", () -> h.core.sessionStats().isEmpty());
            int s2 = p.openBot("Miner", "example.com").sessionId();
            p.reliable(s2, CoreMessages.OBJ_COMMIT, new ObjCommit(2));
            assertEquals("invalid_message", p.await(CoreMessages.ERROR, s2).error().code());
            assertFalse(has(h, MINER + "/e/b"));
        }
    }

    @Test
    void localDevicesAreApprovedWithoutAskingUnlessTheSettingIsOn() throws Exception {
        AtomicInteger asked = new AtomicInteger();
        DeviceApprover counting = req -> {
            asked.incrementAndGet();
            return CompletableFuture.completedFuture(ApprovalDecision.ALLOW_ONCE);
        };
        try (Harness h = new Harness(dir, b -> b.askLocalApproval(false), counting)) {
            try (FakePlugin p = FakePlugin.ready(h, "auto")) {
                assertNotNull(p.welcome);
            }
            assertEquals(0, asked.get());
            assertTrue(h.core.devices().list().stream().anyMatch(d -> d.id().equals("auto")), "remembered");
            h.core.setAskLocalApproval(true);
            try (FakePlugin p = FakePlugin.ready(h, "asking")) {
                assertNotNull(p.welcome);
            }
            assertEquals(1, asked.get());
            try (FakePlugin p = FakePlugin.ready(h, "auto")) {
                assertTrue(p.welcome != null, "a remembered device is not asked again");
            }
            assertEquals(1, asked.get());
        }
    }
}
