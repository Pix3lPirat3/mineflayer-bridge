package io.github.pix3lpirat3.mfbridge.core;

import static io.github.pix3lpirat3.mfbridge.core.InputTestSupport.IDENTITY;
import static io.github.pix3lpirat3.mfbridge.core.InputTestSupport.P;
import static io.github.pix3lpirat3.mfbridge.core.InputTestSupport.assertError;
import static io.github.pix3lpirat3.mfbridge.core.InputTestSupport.failure;
import static io.github.pix3lpirat3.mfbridge.core.InputTestSupport.obj;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.pix3lpirat3.mfbridge.core.container.ContainerCloseRequested;
import io.github.pix3lpirat3.mfbridge.core.container.ContainerControls;
import io.github.pix3lpirat3.mfbridge.core.container.ContainerOpenRequested;
import io.github.pix3lpirat3.mfbridge.core.decl.ContainerViewDecl;
import io.github.pix3lpirat3.mfbridge.protocol.core.ContainerAck;
import io.github.pix3lpirat3.mfbridge.protocol.core.ContainerClose;
import io.github.pix3lpirat3.mfbridge.protocol.core.ContainerInput;
import io.github.pix3lpirat3.mfbridge.protocol.core.ContainerOpen;
import io.github.pix3lpirat3.mfbridge.protocol.core.ContainerStorage;
import io.github.pix3lpirat3.mfbridge.protocol.core.ContainerViewData;
import io.github.pix3lpirat3.mfbridge.protocol.core.CoreKinds;
import io.github.pix3lpirat3.mfbridge.protocol.core.CoreMessages;
import io.github.pix3lpirat3.mfbridge.protocol.core.ErrorMessage;
import io.github.pix3lpirat3.mfbridge.protocol.core.ObjRemove;
import io.github.pix3lpirat3.mfbridge.protocol.core.SessionClose;
import io.github.pix3lpirat3.mfbridge.protocol.core.SlotClick;
import io.github.pix3lpirat3.mfbridge.protocol.core.SlotEntry;
import io.github.pix3lpirat3.mfbridge.protocol.core.SlotItem;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ContainerViewTest {
    static final String VIEW = P + "menus/~view/jobs";
    static final String INV = P + "menus/~view/inv";

    @TempDir
    Path dir;

    static ContainerViewData jobs() {
        return new ContainerViewData("Jobs", "chest:3", List.of(
                new SlotEntry(11, named("minecraft:iron_pickaxe", "Mine", List.of("Strip-mine at y -58"), false)),
                new SlotEntry(15, named("minecraft:wheat", "Farm", null, true))),
                true, named("minecraft:gray_stained_glass_pane", " ", null, false), null, null, null, null, false);
    }

    static SlotItem named(String id, String name, @Nullable List<String> lore, boolean glint) {
        return new SlotItem(id, 1, name, lore, glint, null, null, null, null, null, null, null, null, 500, false, null, null,
                null);
    }

    static ContainerViewData inventory() {
        return new ContainerViewData("Miner's inventory", "player_inventory", List.of());
    }

    static int declare(Harness h, FakePlugin p) {
        int s = p.openBot("Miner", "example.com").sessionId();
        p.upsert(s, obj(VIEW, CoreKinds.CONTAINER_VIEW, jobs()), obj(INV, CoreKinds.CONTAINER_VIEW, inventory()));
        p.sync();
        h.tick();
        return s;
    }

    static void ack(FakePlugin p, int session, String view, int input, boolean ok, String message) {
        p.reliable(session, CoreMessages.CONTAINER_ACK, new ContainerAck(view, input, ok, message));
    }

    @Test
    void viewsAppearInDeclarationsOfAttachedBots() throws Exception {
        try (Harness h = ResumeTest.always(dir, b -> {}); FakePlugin p = FakePlugin.ready(h, "dev")) {
            declare(h, p);
            List<ContainerViewDecl> views = h.core.declarations().containerViews();
            assertEquals(List.of(INV, VIEW), views.stream().map(ContainerViewDecl::key).toList());
            ContainerViewDecl v = h.core.declarations().containerView(VIEW);
            assertEquals("Miner", v.ownerName());
            assertEquals(IDENTITY, v.ownerIdentity());
            assertEquals(jobs(), v.data());
            assertNull(h.core.declarations().containerView(P + "menus/~view/none"));

            h.core.attach().setChoice(IDENTITY, false);
            h.tick();
            assertTrue(h.core.declarations().containerViews().isEmpty(), "detached bots declare nothing");
        }
    }

    @Test
    void botOpensAViewAndSlotGesturesRoundTripWithAcks() throws Exception {
        try (Harness h = ResumeTest.always(dir, b -> {}); FakePlugin p = FakePlugin.ready(h, "dev")) {
            int s = declare(h, p);
            List<ContainerOpenRequested> opens = new ArrayList<>();
            h.core.events().subscribe(ContainerOpenRequested.TYPE, opens::add);
            ContainerControls c = h.core.containers();

            p.reliable(s, CoreMessages.CONTAINER_OPEN, new ContainerOpen(VIEW));
            p.reliable(s, CoreMessages.CONTAINER_OPEN, new ContainerOpen(VIEW));
            p.sync();
            assertEquals(List.of(new ContainerOpenRequested(VIEW)), opens, "a repeated request is not announced again");
            assertEquals(VIEW, c.requestedOpen());
            assertNull(c.open());

            assertError("invalid_message", "not open", c.input(VIEW, 11, SlotClick.LEFT, null));
            c.opened(VIEW);
            assertNull(c.requestedOpen());
            assertEquals(VIEW, c.open());
            p.sync();
            assertFalse(p.poll(f -> f.is(CoreMessages.CONTAINER_OPEN)), "a requested view is not echoed back");

            CompletableFuture<ContainerAck> left = c.input(VIEW, 11, SlotClick.LEFT, null);
            ContainerInput in1 = p.await(CoreMessages.CONTAINER_INPUT, s);
            assertEquals(VIEW, in1.view());
            assertEquals(11, in1.slot());
            assertEquals(SlotClick.LEFT, in1.click());
            assertNull(in1.hotbar());
            assertNotEquals(0, in1.input());
            CompletableFuture<ContainerAck> number = c.input(VIEW, 15, SlotClick.NUMBER, 3);
            ContainerInput in2 = p.await(CoreMessages.CONTAINER_INPUT, s);
            assertEquals(3, in2.hotbar());
            assertNotEquals(in1.input(), in2.input());
            CompletableFuture<ContainerAck> outside = c.input(VIEW, -999, SlotClick.DROP_ALL, null);
            assertEquals(-999, p.await(CoreMessages.CONTAINER_INPUT, s).slot());

            ack(p, s, INV, in1.input(), true, "another view is ignored");
            ack(p, s, VIEW, in1.input() + 1000, true, "unknown input is ignored");
            ack(p, s, VIEW, in2.input(), false, "hotbar is full");
            ack(p, s, VIEW, in1.input(), true, null);
            h.tickUntil("acks", () -> left.isDone() && number.isDone());
            assertEquals(new ContainerAck(VIEW, in1.input(), true, null), left.join());
            assertEquals(new ContainerAck(VIEW, in2.input(), false, "hotbar is full"), number.join());

            h.clock.advanceMs(ContainerControls.ACK_TIMEOUT_MS);
            h.tickUntil("ack timeout", outside::isDone);
            assertEquals("timeout", failure(outside).code());

            assertError("invalid_message", "hotbar", c.input(VIEW, 1, SlotClick.NUMBER, null));
            assertError("invalid_message", "hotbar", c.input(VIEW, 1, SlotClick.LEFT, 2));
            assertError("invalid_message", "hotbar", c.input(VIEW, 1, SlotClick.NUMBER, 9));
            assertError("invalid_message", "not in the view", c.input(VIEW, -5, SlotClick.LEFT, null));

            c.closed(VIEW);
            assertNull(c.open());
            assertEquals(new ContainerClose(VIEW), p.await(CoreMessages.CONTAINER_CLOSE, s));
            c.closed(VIEW);
            p.sync();
            assertFalse(p.poll(f -> f.is(CoreMessages.CONTAINER_CLOSE)), "closing twice sends one close");
        }
    }

    @Test
    void playerOpenedViewsAreAnnouncedAndOpeningAnotherClosesTheFirst() throws Exception {
        try (Harness h = ResumeTest.always(dir, b -> {}); FakePlugin p = FakePlugin.ready(h, "dev")) {
            int s = declare(h, p);
            ContainerControls c = h.core.containers();
            assertThrows(IllegalArgumentException.class, () -> c.opened(P + "menus/~view/none"));
            c.opened(INV);
            assertEquals(new ContainerOpen(INV), p.await(CoreMessages.CONTAINER_OPEN, s));
            c.opened(VIEW);
            assertEquals(new ContainerClose(INV), p.await(CoreMessages.CONTAINER_CLOSE, s));
            assertEquals(new ContainerOpen(VIEW), p.await(CoreMessages.CONTAINER_OPEN, s));
            assertEquals(VIEW, c.open());
        }
    }

    @Test
    void botCloseRemovalDetachAndDisconnectEndTheView() throws Exception {
        try (Harness h = ResumeTest.always(dir, b -> {}); FakePlugin p = FakePlugin.ready(h, "dev")) {
            int s = declare(h, p);
            List<ContainerCloseRequested> closes = new ArrayList<>();
            h.core.events().subscribe(ContainerCloseRequested.TYPE, closes::add);
            ContainerControls c = h.core.containers();

            p.reliable(s, CoreMessages.CONTAINER_OPEN, new ContainerOpen(VIEW));
            p.sync();
            c.opened(VIEW);
            p.reliable(s, CoreMessages.CONTAINER_CLOSE, new ContainerClose(VIEW));
            p.sync();
            assertEquals(List.of(new ContainerCloseRequested(VIEW)), closes);
            assertNull(c.open());
            c.closed(VIEW);
            p.sync();
            assertFalse(p.poll(f -> f.is(CoreMessages.CONTAINER_CLOSE)), "a bot close is not echoed back");

            c.opened(INV);
            p.await(CoreMessages.CONTAINER_OPEN, s);
            p.reliable(s, CoreMessages.OBJ_REMOVE, new ObjRemove(List.of(INV), List.of()));
            p.sync();
            h.tick();
            assertNull(c.open(), "a removed view closes");
            assertEquals(new ContainerClose(INV, true), p.await(CoreMessages.CONTAINER_CLOSE, s));
            assertEquals(new ContainerCloseRequested(INV), closes.get(closes.size() - 1));

            c.opened(VIEW);
            p.await(CoreMessages.CONTAINER_OPEN, s);
            h.core.attach().setChoice(IDENTITY, false);
            h.tick();
            assertNull(c.open(), "detaching the bot closes its view");
            assertEquals(new ContainerClose(VIEW, true), p.await(CoreMessages.CONTAINER_CLOSE, s), "a lapsed view is not a player close");
            assertEquals(new ContainerCloseRequested(VIEW), closes.get(closes.size() - 1));

            p.reliable(s, CoreMessages.CONTAINER_OPEN, new ContainerOpen(VIEW));
            ErrorMessage refused = p.await(CoreMessages.ERROR, s);
            assertEquals("not_attached", refused.error().code());
            assertEquals(CoreMessages.CONTAINER_OPEN.id(), refused.relatesTo());
            assertNull(c.requestedOpen());

            h.core.attach().setChoice(IDENTITY, true);
            h.tick();
            c.opened(VIEW);
            p.await(CoreMessages.CONTAINER_OPEN, s);
            CompletableFuture<ContainerAck> waiting = c.input(VIEW, 1, SlotClick.RIGHT, null);
            p.await(CoreMessages.CONTAINER_INPUT, s);
            p.control(s, CoreMessages.SESSION_CLOSE, new SessionClose("bye"));
            h.tickUntil("view closed", () -> c.open() == null);
            assertEquals("disconnected", failure(waiting).code());
        }
    }

    @Test
    void botsMayOnlyOpenTheirOwnDeclaredViews() throws Exception {
        try (Harness h = ResumeTest.always(dir, b -> {}); FakePlugin p = FakePlugin.ready(h, "dev")) {
            int s = declare(h, p);
            p.reliable(s, CoreMessages.CONTAINER_OPEN, new ContainerOpen("bot:other@example.com/menus/~view/jobs"));
            assertEquals("not_owner", p.await(CoreMessages.ERROR, s).error().code());
            p.reliable(s, CoreMessages.CONTAINER_OPEN, new ContainerOpen(P + "menus/~view/none"));
            assertEquals("invalid_message", p.await(CoreMessages.ERROR, s).error().code());
            assertNull(h.core.containers().requestedOpen());
        }
    }

    @Test
    void storageChangesAndAskMovesReachTheBot() throws Exception {
        try (Harness h = ResumeTest.always(dir, b -> {}); FakePlugin p = FakePlugin.ready(h, "dev")) {
            int s = declare(h, p);
            ContainerControls c = h.core.containers();
            List<SlotEntry> storage = List.of(new SlotEntry(3, new SlotItem("minecraft:stone"), "free", null, null));
            c.storageChanged(VIEW, storage);
            c.opened(VIEW);
            p.sync();
            assertEquals(VIEW, p.await(CoreMessages.CONTAINER_OPEN, s).view());
            c.storageChanged(VIEW, storage);
            ContainerStorage report = p.await(CoreMessages.CONTAINER_STORAGE, s);
            assertEquals(new ContainerStorage(VIEW, storage), report, "only the open view reports storage");

            SlotItem moved = new SlotItem("minecraft:stone");
            c.input(VIEW, 4, SlotClick.LEFT, null, moved, -1);
            ContainerInput in = p.await(CoreMessages.CONTAINER_INPUT, s);
            assertEquals(moved, in.moved());
            assertEquals(-1, in.fromSlot());
            assertError("invalid_message", "fromSlot", c.input(VIEW, 4, SlotClick.LEFT, null, moved, null));
        }
    }
}
