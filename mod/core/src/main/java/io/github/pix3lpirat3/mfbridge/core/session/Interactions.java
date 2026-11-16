package io.github.pix3lpirat3.mfbridge.core.session;

import io.github.pix3lpirat3.mfbridge.core.input.InteractionControls;
import io.github.pix3lpirat3.mfbridge.api.BridgeException;
import io.github.pix3lpirat3.mfbridge.api.thread.ClientThread;
import io.github.pix3lpirat3.mfbridge.core.command.TreeNode;
import io.github.pix3lpirat3.mfbridge.core.input.InvocationResult;
import io.github.pix3lpirat3.mfbridge.core.store.ObjectKeys;
import io.github.pix3lpirat3.mfbridge.core.store.ObjectStore;
import io.github.pix3lpirat3.mfbridge.core.store.StoredObject;
import io.github.pix3lpirat3.mfbridge.protocol.core.Scope;
import io.github.pix3lpirat3.mfbridge.protocol.core.ActionData;
import io.github.pix3lpirat3.mfbridge.protocol.core.ActionInvoked;
import io.github.pix3lpirat3.mfbridge.protocol.core.CommandData;
import io.github.pix3lpirat3.mfbridge.protocol.core.CommandInvoked;
import io.github.pix3lpirat3.mfbridge.protocol.core.CoreMessages;
import io.github.pix3lpirat3.mfbridge.protocol.core.ElementEvent;
import io.github.pix3lpirat3.mfbridge.protocol.core.ElementInput;
import io.github.pix3lpirat3.mfbridge.core.traits.Traits;
import io.github.pix3lpirat3.mfbridge.core.util.TextLimits;
import io.github.pix3lpirat3.mfbridge.protocol.core.Interactive;
import io.github.pix3lpirat3.mfbridge.protocol.core.KeybindData;
import io.github.pix3lpirat3.mfbridge.protocol.core.KeybindEvent;
import io.github.pix3lpirat3.mfbridge.protocol.core.Target;
import io.github.pix3lpirat3.mfbridge.protocol.core.ToolData;
import io.github.pix3lpirat3.mfbridge.protocol.core.ToolEvent;
import io.github.pix3lpirat3.mfbridge.protocol.core.ToolInput;
import io.github.pix3lpirat3.mfbridge.protocol.core.ToolState;
import io.github.pix3lpirat3.mfbridge.protocol.core.Vec3d;
import io.github.pix3lpirat3.mfbridge.protocol.wire.Envelope;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import org.jspecify.annotations.Nullable;

/**
 * Player-to-bot input on declarations: context action and command invocations with their
 * results, keybind, tool and element events, and the active tool.
 */
@ClientThread
final class Interactions implements InteractionControls {
    /** How long an invocation waits for {@code invocation.result}, in ms. */
    static final long INVOCATION_TIMEOUT_MS = 30_000;

    private final SessionHost host;
    private final SurfaceGate gate;
    private final ObjectStore store;
    private final PendingAnswers<InvocationResult> pending = new PendingAnswers<>(INVOCATION_TIMEOUT_MS);
    private @Nullable String activeTool;

    Interactions(SessionHost host, ObjectStore store, SurfaceGate gate) {
        this.host = host;
        this.gate = gate;
        this.store = store;
    }

    @Nullable StoredObject declaration(String key, Class<?> data) {
        StoredObject o = store.get(key);
        return o != null && data.isInstance(o.state().decoded()) ? o : null;
    }

    /**
     * Resolves the live, visible owner of a declaration.
     *
     * @throws BridgeException {@code disconnected}, {@code not_attached} or {@code world_mismatch}
     */
    Session reachableOwner(StoredObject o) {
        Session s = host.writer(o.parts().owner());
        if (s == null || s.superseded) {
            throw new BridgeException("disconnected", "no session owns " + o.key());
        }
        host.requireVisible(s, o);
        return s;
    }

    /**
     * The live session that owns a command: a bot in any world can be given orders, so only a
     * world-scoped command outside the player's world is refused.
     *
     * @throws BridgeException {@code disconnected} or {@code world_mismatch}
     */
    Session commandOwner(StoredObject o) {
        Session s = host.writer(o.parts().owner());
        if (s == null || s.superseded) {
            throw new BridgeException("disconnected", "no session owns " + o.key());
        }
        if (o.state().scope() == Scope.WORLD && !host.worldVisible(o.state().world())) {
            throw new BridgeException("world_mismatch", "the player is not in the world of " + o.key());
        }
        return s;
    }

    CompletableFuture<InvocationResult> invokeAction(String actionKey, Target target) {
        return invokeAction(actionKey, target, null);
    }

    /** {@code source} says how the player ran it ({@code menu}, {@code chat}, ...), for the bot to answer the same way. */
    CompletableFuture<InvocationResult> invokeAction(String actionKey, Target target, @Nullable String source) {
        return invokeAction(actionKey, target, source, null);
    }

    /** {@code bots} names the bots the player had selected, such as on the map. */
    @Override
    public CompletableFuture<InvocationResult> invokeAction(String actionKey, Target target, @Nullable String source,
            @Nullable List<String> bots) {
        StoredObject o = declaration(actionKey, ActionData.class);
        if (o == null) {
            return failed("invalid_message", "unknown action '" + actionKey + "'");
        }
        ActionData d = (ActionData) Objects.requireNonNull(o.state().decoded());
        if (!d.target().type().equals(target.type())) {
            return failed("invalid_message", "action '" + d.label() + "' takes a " + d.target().type()
                    + " target, not " + target.type());
        }
        Session s;
        try {
            s = reachableOwner(o);
        } catch (BridgeException e) {
            return CompletableFuture.failedFuture(e);
        }
        int id = allocate();
        asked(s);
        CompletableFuture<InvocationResult> f = track(s, id);
        host.sendReliable(s, CoreMessages.ACTION_INVOKED, new ActionInvoked(actionKey, id, target, source, bots == null || bots.isEmpty() ? null : List.copyOf(bots)), 0,
                Envelope.NONE);
        return f;
    }

    CompletableFuture<InvocationResult> invokeCommand(String commandKey, Map<String, Object> args) {
        return invokeCommand(commandKey, List.of(), args);
    }

    /** {@code path} names the subcommand below the root that runs. */
    CompletableFuture<InvocationResult> invokeCommand(String commandKey, List<String> path, Map<String, Object> args) {
        return invokeCommand(commandKey, path, args, null);
    }

    /** {@code source} says how the player ran it ({@code chat}, {@code command}, ...), for the bot to answer the same way. */
    CompletableFuture<InvocationResult> invokeCommand(String commandKey, List<String> path, Map<String, Object> args,
            @Nullable String source) {
        return invokeCommand(commandKey, path, args, source, List.of());
    }

    /** {@code bots} names the crew bots an order is for; it must be empty for other commands. */
    @Override
    public CompletableFuture<InvocationResult> invokeCommand(String commandKey, List<String> path, Map<String, Object> args,
            @Nullable String source, List<String> bots) {
        StoredObject o = declaration(commandKey, CommandData.class);
        if (o == null) {
            return failed("invalid_message", "unknown command '" + commandKey + "'");
        }
        CommandData d = (CommandData) Objects.requireNonNull(o.state().decoded());
        if (d.orders() && bots.isEmpty()) {
            return failed("invalid_message", d.name() + " is an order: say which bots it is for");
        }
        if (!d.orders() && !bots.isEmpty()) {
            return failed("invalid_message", d.name() + " is not an order and takes no bots");
        }
        TreeNode node = TreeNode.of(d).at(path);
        String label = path.isEmpty() ? d.name() : d.name() + " " + String.join(" ", path);
        if (node == null) {
            return failed("invalid_message", d.name() + " has no subcommand '" + String.join(" ", path) + "'");
        }
        if (!node.runnable()) {
            return failed("invalid_message", label + " only groups subcommands; choose one of them");
        }
        Map<String, Object> checked;
        try {
            checked = ValueRules.commandArgs(node.args(), args);
        } catch (IllegalArgumentException e) {
            return failed("invalid_message", label + ": " + e.getMessage());
        }
        Session s;
        try {
            s = commandOwner(o);
        } catch (BridgeException e) {
            return CompletableFuture.failedFuture(e);
        }
        int id = allocate();
        asked(s);
        CompletableFuture<InvocationResult> f = track(s, id);
        host.sendReliable(s, CoreMessages.COMMAND_INVOKED,
                new CommandInvoked(commandKey, id, Collections.unmodifiableMap(checked), path.isEmpty() ? null : List.copyOf(path), source,
                        bots.isEmpty() ? null : List.copyOf(bots)), 0,
                Envelope.NONE);
        return f;
    }

    /** The player used something of this session, so what it opens next was asked for. */
    private void asked(Session s) {
        gate.asked(s.owner, host.nowMs());
    }

    private static CompletableFuture<InvocationResult> failed(String code, String message) {
        return CompletableFuture.failedFuture(new BridgeException(code, message));
    }

    /** Next u32 invocation id; 0 is never used and ids still pending are skipped after wrap-around. */
    int allocate() {
        return pending.allocate();
    }

    CompletableFuture<InvocationResult> track(Session s, int id) {
        return pending.track(s, id, "", host.nowMs());
    }

    /** Completes the matching invocation; results from another session are ignored. */
    void result(Session s, io.github.pix3lpirat3.mfbridge.protocol.core.InvocationResult r) {
        String message = r.message() == null ? null : TextLimits.clamp(r.message());
        pending.complete(r.invocation(), s, "", new InvocationResult(r.ok(), message, Boolean.TRUE.equals(r.quiet())));
    }

    void expire(long now) {
        pending.expire(now);
    }

    /** Fails the invocations of a session that ended or was superseded. */
    void sessionEnded(Session s) {
        pending.sessionEnded(s);
    }

    /** Owner of a declaration that may receive events, or null when the event should be dropped. */
    private @Nullable Session eventOwner(String key, Class<?> data) {
        StoredObject o = declaration(key, data);
        if (o == null) {
            return null;
        }
        try {
            return reachableOwner(o);
        } catch (BridgeException e) {
            return null;
        }
    }

    @Override
    public void keybindEvent(String keybindKey, boolean pressed) {
        Session s = eventOwner(keybindKey, KeybindData.class);
        if (s != null) {
            if (pressed) {
                asked(s);
            }
            host.sendReliable(s, CoreMessages.KEYBIND_EVENT, new KeybindEvent(keybindKey, pressed), 0,
                    Envelope.NONE);
        }
    }

    @Override
    public void toolEvent(String toolKey, ToolInput input, @Nullable Float delta, @Nullable String key, @Nullable Target target) {
        Session s = eventOwner(toolKey, ToolData.class);
        if (s != null) {
            asked(s);
            host.sendReliable(s, CoreMessages.TOOL_EVENT, new ToolEvent(toolKey, input, delta, key, target), 0,
                    Envelope.NONE);
        }
    }

    /**
     * Sends {@code element.event} to the owner of an interactive
     * object: a display, or any kind with {@code traits.interactive}. Dropped when the element is gone or not interactive, its owner is gone, detached or
     * outside the element's world, or the element did not declare the input (or, for {@code key},
     * the key). Hover leave is delivered whenever hover enter is declared, so the bot sees pairs.
     */
    @Override
    public void elementEvent(String elementKey, ElementInput input, @Nullable Float delta, @Nullable String key,
            @Nullable Vec3d local, int modifiers) {
        StoredObject o = store.get(elementKey);
        if (o == null) {
            return;
        }
        Interactive spec = Traits.interactive(o.state().traits());
        if (spec == null || !declares(spec, input, key)) {
            return;
        }
        Session s;
        try {
            s = reachableOwner(o);
        } catch (BridgeException e) {
            return;
        }
        if (input != ElementInput.HOVER_ENTER && input != ElementInput.HOVER_LEAVE) {
            asked(s);
        }
        host.sendReliable(s, CoreMessages.ELEMENT_EVENT, new ElementEvent(elementKey, input, delta, key, local,
                modifiers), 0, Envelope.NONE);
    }

    private static boolean declares(Interactive spec, ElementInput input, @Nullable String key) {
        ElementInput needed = switch (input) {
            case HOVER_LEAVE -> ElementInput.HOVER_ENTER;
            case DRAG, DRAG_END -> ElementInput.DRAG_START;
            case HOVER_ENTER, PRIMARY, SECONDARY, MIDDLE, SCROLL, KEY, DRAG_START -> input;
        };
        if (!spec.inputs().contains(needed) && !spec.inputs().contains(input)) {
            return false;
        }
        if (input == ElementInput.KEY) {
            List<String> keys = spec.keys();
            return key != null && keys != null && keys.contains(key);
        }
        return true;
    }

    @Override
    public @Nullable String activeTool() {
        return activeTool;
    }

    /**
     * Switches tool mode. The owner of the previous tool gets {@code tool.state{active: null}} and the
     * owner of the new one {@code tool.state{active: key}}; when both are one session the latter wins
     * because {@code tool.state} is latest-wins. A key that names no live, visible tool switches tool
     * mode off.
     */
    @Override
    public void setActiveTool(@Nullable String toolKey) {
        String next = toolKey != null && eventOwner(toolKey, ToolData.class) != null ? toolKey : null;
        String previous = activeTool;
        if (Objects.equals(previous, next)) {
            return;
        }
        activeTool = next;
        if (previous != null) {
            Session old = ownerOf(previous);
            if (old != null) {
                host.sendLatest(old, CoreMessages.TOOL_STATE, new ToolState(null), null);
            }
        }
        if (next != null) {
            Session s = Objects.requireNonNull(ownerOf(next));
            host.sendLatest(s, CoreMessages.TOOL_STATE, new ToolState(next), null);
        }
    }

    private @Nullable Session ownerOf(String key) {
        try {
            return host.writer(ObjectKeys.parse(key).owner());
        } catch (BridgeException e) {
            return null;
        }
    }

    /** The active tool's object left the store: tool mode ends, and a still-live owner is told. */
    void removed(String key) {
        if (key.equals(activeTool)) {
            activeTool = null;
            Session s = ownerOf(key);
            if (s != null) {
                host.sendLatest(s, CoreMessages.TOOL_STATE, new ToolState(null), null);
            }
        }
    }

    /** Re-sends the latest-wins tool state after a resume, since latest messages are not logged. */
    void resumed(Session s) {
        String key = activeTool;
        if (key != null && ownerOf(key) == s) {
            host.sendLatest(s, CoreMessages.TOOL_STATE, new ToolState(key), null);
        }
    }
}
