package io.github.pix3lpirat3.mfbridge.core;

import static io.github.pix3lpirat3.mfbridge.core.InputTestSupport.IDENTITY;
import static io.github.pix3lpirat3.mfbridge.core.InputTestSupport.NETHER;
import static io.github.pix3lpirat3.mfbridge.core.InputTestSupport.P;
import static io.github.pix3lpirat3.mfbridge.core.InputTestSupport.assertError;
import static io.github.pix3lpirat3.mfbridge.core.InputTestSupport.failure;
import static io.github.pix3lpirat3.mfbridge.core.InputTestSupport.obj;
import static io.github.pix3lpirat3.mfbridge.core.InputTestSupport.worldObj;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.pix3lpirat3.mfbridge.api.BridgeException;
import io.github.pix3lpirat3.mfbridge.core.input.InvocationResult;
import io.github.pix3lpirat3.mfbridge.protocol.core.ActionData;
import io.github.pix3lpirat3.mfbridge.protocol.core.ActionInvoked;
import io.github.pix3lpirat3.mfbridge.protocol.core.ArgType;
import io.github.pix3lpirat3.mfbridge.protocol.core.CommandArg;
import io.github.pix3lpirat3.mfbridge.protocol.core.CommandData;
import io.github.pix3lpirat3.mfbridge.protocol.core.CommandInvoked;
import io.github.pix3lpirat3.mfbridge.protocol.core.CoreKinds;
import io.github.pix3lpirat3.mfbridge.protocol.core.CoreMessages;
import io.github.pix3lpirat3.mfbridge.protocol.core.KeyActivation;
import io.github.pix3lpirat3.mfbridge.protocol.core.KeybindData;
import io.github.pix3lpirat3.mfbridge.protocol.core.KeybindEvent;
import io.github.pix3lpirat3.mfbridge.protocol.core.ObjRemove;
import io.github.pix3lpirat3.mfbridge.protocol.core.SessionClose;
import io.github.pix3lpirat3.mfbridge.protocol.core.Target;
import io.github.pix3lpirat3.mfbridge.protocol.core.TargetSpec;
import io.github.pix3lpirat3.mfbridge.protocol.core.ToolData;
import io.github.pix3lpirat3.mfbridge.protocol.core.ToolEvent;
import io.github.pix3lpirat3.mfbridge.protocol.core.ToolInput;
import io.github.pix3lpirat3.mfbridge.protocol.core.ToolState;
import io.github.pix3lpirat3.mfbridge.protocol.core.Vec3i;
import io.github.pix3lpirat3.mfbridge.protocol.core.WorldReport;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InvocationTest {
    static final String ACTION = P + "deposit/~action/deposit";
    static final String COMMAND = P + "miner/~cmd/mine";

    @TempDir
    Path dir;

    static Target chest() {
        return new Target("block", new Vec3i(1, 64, 2), "up", null, null, null, null);
    }

    static ActionData depositAction() {
        return new ActionData("Deposit here", "minecraft:chest", new TargetSpec("block", null, List.of("c:chests")), 0);
    }

    static CommandData mineCommand() {
        return new CommandData("mine", "Mine a block type", List.of(
                new CommandArg("block", ArgType.BLOCK_ID, false, null, null, null, null, null, null),
                new CommandArg("count", ArgType.INT, false, 64, 1.0, 4096.0, null, null, null),
                new CommandArg("mode", ArgType.CHOICE, true, null, null, null, List.of("fast", "safe"), null, null),
                new CommandArg("at", ArgType.BLOCK_POS, true, null, null, null, null, null, null),
                new CommandArg("speed", ArgType.FLOAT, true, null, 0.0, 1.0, null, null, null)));
    }

    static void answer(FakePlugin p, int session, int invocation, boolean ok, String message) {
        p.reliable(session, CoreMessages.INVOCATION_RESULT,
                new io.github.pix3lpirat3.mfbridge.protocol.core.InvocationResult(invocation, ok, message, null));
    }

    static int declare(Harness h, FakePlugin p) {
        int s = p.openBot("Miner", "example.com").sessionId();
        p.upsert(s, obj(ACTION, CoreKinds.ACTION, depositAction()), obj(COMMAND, CoreKinds.COMMAND, mineCommand()));
        p.sync();
        h.tick();
        return s;
    }

    @Test
    void actionsAndCommandsRoundTripWithTheirResults() throws Exception {
        try (Harness h = ResumeTest.always(dir, b -> {}); FakePlugin p = FakePlugin.ready(h, "dev")) {
            int s = declare(h, p);
            CompletableFuture<InvocationResult> action = h.core.invokeAction(ACTION, chest());
            ActionInvoked ai = p.await(CoreMessages.ACTION_INVOKED, s);
            assertEquals(ACTION, ai.action());
            assertEquals(chest(), ai.target());
            assertNotEquals(0, ai.invocation());

            Map<String, Object> args = new HashMap<>();
            args.put("block", "minecraft:iron_ore");
            args.put("mode", "safe");
            args.put("at", List.of(1, 2, 3));
            args.put("speed", 0.5f);
            CompletableFuture<InvocationResult> command = h.core.invokeCommand(COMMAND, args);
            CommandInvoked ci = p.await(CoreMessages.COMMAND_INVOKED, s);
            assertEquals(COMMAND, ci.command());
            assertNotEquals(ai.invocation(), ci.invocation());
            assertEquals(Map.of("block", "minecraft:iron_ore", "count", 64L, "mode", "safe",
                    "at", Map.of("x", 1L, "y", 2L, "z", 3L), "speed", 0.5), ci.args());

            answer(p, s, ci.invocation() + 1000, true, "unknown invocation is ignored");
            answer(p, s, ci.invocation(), false, "no iron nearby");
            answer(p, s, ai.invocation(), true, null);
            h.tickUntil("results", () -> action.isDone() && command.isDone());
            assertEquals(new InvocationResult(true, null), action.join());
            assertEquals(new InvocationResult(false, "no iron nearby"), command.join());
        }
    }

    @Test
    void invalidInvocationsFailWithReadableMessages() throws Exception {
        try (Harness h = ResumeTest.always(dir, b -> {}); FakePlugin p = FakePlugin.ready(h, "dev")) {
            declare(h, p);
            assertError("invalid_message", "missing argument 'block'", h.core.invokeCommand(COMMAND, Map.of()));
            assertError("invalid_message", "at least 1",
                    h.core.invokeCommand(COMMAND, Map.of("block", "minecraft:stone", "count", 0)));
            assertError("invalid_message", "whole number",
                    h.core.invokeCommand(COMMAND, Map.of("block", "minecraft:stone", "count", "many")));
            assertError("invalid_message", "one of fast, safe",
                    h.core.invokeCommand(COMMAND, Map.of("block", "minecraft:stone", "mode", "slow")));
            assertError("invalid_message", "at most 1",
                    h.core.invokeCommand(COMMAND, Map.of("block", "minecraft:stone", "speed", 2)));
            assertError("invalid_message", "position",
                    h.core.invokeCommand(COMMAND, Map.of("block", "minecraft:stone", "at", List.of(1, 2))));
            assertError("invalid_message", "unknown argument 'depth'",
                    h.core.invokeCommand(COMMAND, Map.of("block", "minecraft:stone", "depth", 3)));
            assertError("invalid_message", "unknown command", h.core.invokeCommand(P + "miner/~cmd/nope", Map.of()));
            assertError("invalid_message", "unknown action", h.core.invokeAction(COMMAND, chest()));
            assertError("invalid_message", "block target",
                    h.core.invokeAction(ACTION, new Target("entity", null, null, 7, null, null, null)));
            p.sync();
            assertFalse(p.poll(f -> f.is(CoreMessages.COMMAND_INVOKED) || f.is(CoreMessages.ACTION_INVOKED)),
                    "nothing invalid reaches the bot");
        }
    }

    @Test
    void invocationsTimeOutAndFailWhenTheSessionEndsOrIsNotReachable() throws Exception {
        try (Harness h = new Harness(dir); FakePlugin p = FakePlugin.ready(h, "dev")) {
            int s = declare(h, p);
            assertError("not_attached", "attached", h.core.invokeAction(ACTION, chest()));
            h.core.attach().setChoice(IDENTITY, true);
            p.control(s, CoreMessages.WORLD_REPORT, new WorldReport(InputTestSupport.BOT_WORLD, null));
            h.core.setPlayerContext(NETHER);
            p.upsert(s, worldObj(P + "deposit/~action/here", CoreKinds.ACTION, InputTestSupport.BOT_WORLD,
                    depositAction()));
            p.sync();
            assertError("world_mismatch", "world", h.core.invokeAction(P + "deposit/~action/here", chest()));

            CompletableFuture<InvocationResult> slow = h.core.invokeAction(ACTION, chest());
            p.await(CoreMessages.ACTION_INVOKED, s);
            // The peer counts as dead after 15 s of silence, so time advances in steps.
            for (int i = 0; i < 3; i++) {
                h.clock.advanceMs(i < 2 ? 10_000 : 9_999);
                p.sync();
            }
            h.tick();
            assertFalse(slow.isDone());
            h.clock.advanceMs(1);
            h.tick();
            assertError("timeout", "30 s", slow);

            CompletableFuture<InvocationResult> pending =
                    h.core.invokeCommand(COMMAND, Map.of("block", "minecraft:stone"));
            p.await(CoreMessages.COMMAND_INVOKED, s);
            p.control(s, CoreMessages.SESSION_CLOSE, new SessionClose("bye"));
            h.tickUntil("disconnect", pending::isDone);
            assertError("disconnected", "Miner", pending);
            assertError("invalid_message", "unknown action", h.core.invokeAction(ACTION, chest()),
                    "the closed session's declarations are gone");
        }
    }

    @Test
    void keybindAndToolEventsReachTheOwnerAndToolStateFollowsTheActiveTool() throws Exception {
        try (Harness h = ResumeTest.always(dir, b -> {}); FakePlugin p = FakePlugin.ready(h, "dev")) {
            int a = p.openBot("Miner", "example.com").sessionId();
            int b = p.openBot("Farmer", "example.com").sessionId();
            String stop = P + "miner/~key/stop";
            String mark = P + "miner/~tool/mark";
            String plant = "bot:farmer@example.com/farm/~tool/plant";
            p.upsert(a, obj(stop, CoreKinds.KEYBIND, new KeybindData("Stop", List.of("key.keyboard.g"), "ingame",
                            KeyActivation.PRESS, 0, true)),
                    obj(mark, CoreKinds.TOOL, new ToolData("Mark ore", "minecraft:diamond_ore", List.of("block"),
                            List.of(ToolInput.PRIMARY, ToolInput.SCROLL))));
            p.upsert(b, obj(plant, CoreKinds.TOOL, new ToolData("Plant", null, List.of("block"), List.of(ToolInput.PRIMARY))));
            p.sync();

            h.core.keybindEvent(stop, true);
            assertEquals(new KeybindEvent(stop, true), p.await(CoreMessages.KEYBIND_EVENT, a));
            h.core.toolEvent(mark, ToolInput.SCROLL, 2.0f, null, chest());
            assertEquals(new ToolEvent(mark, ToolInput.SCROLL, 2.0f, null, chest()), p.await(CoreMessages.TOOL_EVENT, a));
            h.core.keybindEvent(P + "miner/~key/missing", true);
            h.core.toolEvent(stop, ToolInput.PRIMARY, null, null, null);

            h.core.setActiveTool(mark);
            assertEquals(mark, h.core.activeTool());
            assertEquals(new ToolState(mark), p.await(CoreMessages.TOOL_STATE, a));
            h.core.setActiveTool(plant);
            assertEquals(new ToolState(null), p.await(CoreMessages.TOOL_STATE, a));
            assertEquals(new ToolState(plant), p.await(CoreMessages.TOOL_STATE, b));
            h.core.setActiveTool(null);
            assertEquals(new ToolState(null), p.await(CoreMessages.TOOL_STATE, b));

            h.core.setActiveTool(mark);
            assertEquals(new ToolState(mark), p.await(CoreMessages.TOOL_STATE, a));
            p.reliable(a, CoreMessages.OBJ_REMOVE, new ObjRemove(List.of(mark), List.of()));
            assertEquals(new ToolState(null), p.await(CoreMessages.TOOL_STATE, a), "removing the active tool ends tool mode");
            assertNull(h.core.activeTool());
            h.core.setActiveTool(P + "miner/~tool/unknown");
            assertNull(h.core.activeTool());

            h.core.attach().setChoice(IDENTITY, false);
            h.core.keybindEvent(stop, false);
            p.sync();
            assertFalse(p.poll(f -> f.is(CoreMessages.KEYBIND_EVENT) || f.is(CoreMessages.TOOL_EVENT)),
                    "events for gone or detached owners are dropped: " + p.describe());
        }
    }
}
