package io.github.pix3lpirat3.mfbridge.core;

import static io.github.pix3lpirat3.mfbridge.core.InputTestSupport.matchedBot;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.pix3lpirat3.mfbridge.protocol.core.ActionData;
import io.github.pix3lpirat3.mfbridge.protocol.core.CoreKinds;
import io.github.pix3lpirat3.mfbridge.protocol.core.CoreMessages;
import io.github.pix3lpirat3.mfbridge.protocol.core.ErrorMessage;
import io.github.pix3lpirat3.mfbridge.protocol.core.Target;
import io.github.pix3lpirat3.mfbridge.protocol.core.TargetSpec;
import io.github.pix3lpirat3.mfbridge.protocol.core.Notify;
import io.github.pix3lpirat3.mfbridge.protocol.core.NotifyButton;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NotifyTest {
    @TempDir
    Path dir;

    @Test
    void buttonsRunTheSendersOwnActions() throws Exception {
        try (Harness h = new Harness(dir); FakePlugin p = FakePlugin.ready(h, "dev")) {
            int s = matchedBot(h, p, "Miner");
            String action = InputTestSupport.OWNER + "/user:default/~action/dump";
            p.reliable(s, CoreMessages.NOTIFY, new Notify("Inventory full", "warn", null,
                    List.of(new NotifyButton("Dump", action, null), new NotifyButton("Again", null, "@Miner mine stone")), "block.note_block.bell"));
            h.tickUntil("notified", () -> !h.notifications.isEmpty());
            Notifier.Notification n = h.notifications.get(0);
            assertEquals("Miner", n.source());
            assertEquals(InputTestSupport.OWNER, n.owner());
            assertEquals("warn", n.level());
            assertEquals(List.of(new Notifier.Button("Dump", action), new Notifier.Button("Again", null, "@Miner mine stone")), n.buttons());
            assertEquals("block.note_block.bell", n.sound());

            p.reliable(s, CoreMessages.NOTIFY, new Notify("Click me", "info", null,
                    List.of(new NotifyButton("Steal", "bot:other@example.com/user:default/~action/x", null)), null));
            assertEquals("invalid_message", p.await(CoreMessages.ERROR, s).error().code());
            p.reliable(s, CoreMessages.NOTIFY, new Notify("Both", "info", null, List.of(new NotifyButton("Go", action, "@Miner go")), null));
            assertEquals("invalid_message", p.await(CoreMessages.ERROR, s).error().code());
            p.reliable(s, CoreMessages.NOTIFY, new Notify("Neither", "info", null, List.of(new NotifyButton("Go", null, null)), null));
            assertEquals("invalid_message", p.await(CoreMessages.ERROR, s).error().code());
            p.reliable(s, CoreMessages.NOTIFY, new Notify("Long", "info", null, List.of(new NotifyButton("Go", null, "x".repeat(257))), null));
            assertEquals("invalid_message", p.await(CoreMessages.ERROR, s).error().code());
            p.reliable(s, CoreMessages.NOTIFY, new Notify("Loud", "info", null, null, "Not A Sound"));
            assertEquals("invalid_message", p.await(CoreMessages.ERROR, s).error().code());
            h.tick();
            assertEquals(1, h.notifications.size(), "refused notifications show nothing");

            p.reliable(s, CoreMessages.NOTIFY, new Notify("Plain"));
            h.tickUntil("plain", () -> h.notifications.size() == 2);
            assertTrue(h.notifications.get(1).buttons().isEmpty());
            assertNull(h.notifications.get(1).sound());
        }
    }

    @Test
    void onlyAttachedBotsNotify() throws Exception {
        try (Harness h = new Harness(dir); FakePlugin p = FakePlugin.ready(h, "dev")) {
            // The player is not in this bot's world, so it is not attached.
            int s = p.openBot("Miner", "example.com").sessionId();
            p.reliable(s, CoreMessages.NOTIFY, new Notify("Psst"));
            ErrorMessage e = p.await(CoreMessages.ERROR, s);
            assertEquals("not_attached", e.error().code());
            assertEquals(CoreMessages.NOTIFY.id(), e.relatesTo());
            assertTrue(h.notifications.isEmpty());
        }
    }

    @Test
    void notificationsAreRateLimitedAndCut() throws Exception {
        try (Harness h = new Harness(dir); FakePlugin p = FakePlugin.ready(h, "dev")) {
            int s = matchedBot(h, p, "Miner");
            for (int i = 0; i < 12; i++) {
                p.reliable(s, CoreMessages.NOTIFY, new Notify("Spam " + i));
            }
            ErrorMessage first = p.await(CoreMessages.ERROR, s);
            assertEquals("rate_limited", first.error().code());
            assertTrue(first.error().retryable());
            assertEquals("rate_limited", p.await(CoreMessages.ERROR, s).error().code(), "every one over the limit is answered");
            p.sync();
            assertEquals(10, h.notifications.size(), "ten in one second");

            h.clock.advanceMs(1_000);
            String longText = "line\n".repeat(30) + "x".repeat(5_000);
            p.reliable(s, CoreMessages.NOTIFY, new Notify(longText));
            h.tickUntil("the next second", () -> h.notifications.size() == 11);
            String shown = h.notifications.get(10).text();
            assertEquals(20, shown.split("\n", -1).length, "twenty lines at most");
            p.reliable(s, CoreMessages.NOTIFY, new Notify("x".repeat(5_000)));
            h.tickUntil("a long one", () -> h.notifications.size() == 12);
            assertEquals(4_096, h.notifications.get(11).text().length());
        }
    }

    @Test
    void repliesToThePlayersCommandsAreCut() throws Exception {
        try (Harness h = new Harness(dir); FakePlugin p = FakePlugin.ready(h, "dev")) {
            int s = matchedBot(h, p, "Miner");
            String action = InputTestSupport.OWNER + "/user:default/~action/dump";
            p.upsert(s, InputTestSupport.obj(action, CoreKinds.ACTION,
                    new ActionData("Dump", null, new TargetSpec("air", null, null), 0)));
            p.sync();
            h.tick();
            var result = h.core.invokeAction(action, new Target("air"));
            int invocation = p.await(CoreMessages.ACTION_INVOKED, s).invocation();
            p.reliable(s, CoreMessages.INVOCATION_RESULT, new io.github.pix3lpirat3.mfbridge.protocol.core.InvocationResult(
                    invocation, true, "y\n".repeat(100) + "x".repeat(9_000), null));
            h.tickUntil("the reply", result::isDone);
            String message = java.util.Objects.requireNonNull(result.join().message());
            assertEquals(20, message.split("\n", -1).length);
            assertTrue(message.length() <= 4_096);
        }
    }
}
