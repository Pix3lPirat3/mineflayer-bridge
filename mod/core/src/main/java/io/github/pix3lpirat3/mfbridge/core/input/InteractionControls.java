package io.github.pix3lpirat3.mfbridge.core.input;

import io.github.pix3lpirat3.mfbridge.api.thread.ClientThread;
import io.github.pix3lpirat3.mfbridge.protocol.core.ElementInput;
import io.github.pix3lpirat3.mfbridge.protocol.core.Target;
import io.github.pix3lpirat3.mfbridge.protocol.core.ToolInput;
import io.github.pix3lpirat3.mfbridge.protocol.core.Vec3d;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.jspecify.annotations.Nullable;

/** What the player triggers on bots: actions, commands, keybinds, tools and interactive elements. */
@ClientThread
public interface InteractionControls {
    /**
     * Runs a bot's action on a target.
     *
     * @param source how the player ran it ({@code menu}, {@code chat}, ...), or null
     * @param bots names of the bots the player had selected, such as on the map, or null
     * @return completes with the bot's result, or fails with a {@code BridgeException}
     */
    CompletableFuture<InvocationResult> invokeAction(String actionKey, Target target, @Nullable String source,
            @Nullable List<String> bots);

    /**
     * Runs a bot's command.
     *
     * @param path the subcommand below the root that runs; empty for the root
     * @param source how the player ran it ({@code chat}, {@code command}, ...), or null
     * @param bots the crew bots an order is for; empty for other commands
     * @return completes with the bot's result, or fails with a {@code BridgeException}
     */
    CompletableFuture<InvocationResult> invokeCommand(String commandKey, List<String> path, Map<String, Object> args,
            @Nullable String source, List<String> bots);

    /** Tells the owner of a keybind that its key went down or up. */
    void keybindEvent(String keybindKey, boolean pressed);

    /** Sends an input of the active tool to its owner. */
    void toolEvent(String toolKey, ToolInput input, @Nullable Float delta, @Nullable String key, @Nullable Target target);

    /** Sends an input on an interactive element to its owner, when the element declared that input. */
    void elementEvent(String elementKey, ElementInput input, @Nullable Float delta, @Nullable String key,
            @Nullable Vec3d local, int modifiers);

    /** Makes a tool the active one, or none with null; the owners of the old and new tool are told. */
    void setActiveTool(@Nullable String toolKey);

    /** Returns the key of the active tool, or null. */
    @Nullable String activeTool();
}
