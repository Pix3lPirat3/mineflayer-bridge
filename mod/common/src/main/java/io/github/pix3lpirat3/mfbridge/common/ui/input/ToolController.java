package io.github.pix3lpirat3.mfbridge.common.ui.input;

import io.github.pix3lpirat3.mfbridge.common.ui.UiContext;
import io.github.pix3lpirat3.mfbridge.core.BridgeCore;
import io.github.pix3lpirat3.mfbridge.core.decl.DeclarationSnapshot;
import io.github.pix3lpirat3.mfbridge.core.decl.ToolDecl;
import io.github.pix3lpirat3.mfbridge.protocol.core.Target;
import io.github.pix3lpirat3.mfbridge.protocol.core.ToolInput;
import org.jspecify.annotations.Nullable;

/**
 * Tool mode. While a tool is active and no screen is open, attack, use and pick-block
 * clicks are captured and never reach vanilla; scroll is captured when the tool declares it. Each
 * declared input is sent to the owner as {@code tool.event} with the target resolved for the tool's
 * declared target types. Client thread only.
 */
public final class ToolController {

    private final UiContext ui;
    private @Nullable ToolDecl cached;
    private @Nullable String cachedKey;
    private long cachedVersion = -1L;

    /**
     * Creates the controller.
     *
     * @param ui the UI services
     */
    public ToolController(final UiContext ui) {
        this.ui = ui;
    }

    /**
     * Returns the active tool's declaration.
     *
     * @return the tool, or null when tool mode is off or the core is not running
     */
    public @Nullable ToolDecl active() {
        final BridgeCore core = ui.core();
        if (core == null) {
            return null;
        }
        final String key = core.activeTool();
        if (key == null) {
            return null;
        }
        final DeclarationSnapshot decl = core.declarations();
        if (!key.equals(cachedKey) || decl.version() != cachedVersion) {
            cachedKey = key;
            cachedVersion = decl.version();
            cached = null;
            for (final ToolDecl tool : decl.tools()) {
                if (tool.key().equals(key)) {
                    cached = tool;
                    break;
                }
            }
        }
        return cached;
    }

    /**
     * Selects a tool or ends tool mode.
     *
     * @param toolKey the tool's object key, or null to end tool mode
     */
    public void select(final @Nullable String toolKey) {
        final BridgeCore core = ui.core();
        if (core != null) {
            core.setActiveTool(toolKey);
        }
    }

    /**
     * Returns whether the active tool declares an input.
     *
     * @param input the input
     * @return whether events of that input are sent
     */
    public boolean declares(final ToolInput input) {
        final ToolDecl tool = active();
        return tool != null && tool.data().inputs().contains(input);
    }

    /**
     * Sends a click or key event for the active tool, when it declares the input.
     *
     * @param input primary, secondary, middle or key
     * @param keyName the vanilla key name for {@link ToolInput#KEY}, else null
     */
    public void fire(final ToolInput input, final @Nullable String keyName) {
        final ToolDecl tool = active();
        final BridgeCore core = ui.core();
        if (tool == null || core == null || !tool.data().inputs().contains(input)) {
            return;
        }
        core.toolEvent(tool.key(), input, null, keyName, target(tool));
    }

    /**
     * Sends scroll steps for the active tool, when it declares scroll.
     *
     * @param steps whole steps, positive up
     */
    public void scroll(final int steps) {
        final ToolDecl tool = active();
        final BridgeCore core = ui.core();
        if (tool == null || core == null || steps == 0 || !tool.data().inputs().contains(ToolInput.SCROLL)) {
            return;
        }
        core.toolEvent(tool.key(), ToolInput.SCROLL, (float) steps, null, target(tool));
    }

    private static @Nullable Target target(final ToolDecl tool) {
        if (tool.data().targets().isEmpty()) {
            return null;
        }
        return Crosshair.firstTarget(Crosshair.hit(Crosshair.ACTION_RANGE, e -> true), tool.data().targets());
    }
}
