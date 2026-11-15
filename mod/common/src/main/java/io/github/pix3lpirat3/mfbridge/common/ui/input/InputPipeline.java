package io.github.pix3lpirat3.mfbridge.common.ui.input;

import io.github.pix3lpirat3.mfbridge.api.client.InputOrder;
import io.github.pix3lpirat3.mfbridge.api.client.InputStage;
import com.mojang.blaze3d.platform.InputConstants;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;
import net.minecraft.client.input.KeyEvent;

/**
 * The ordered in-game input chain. The bridge's own stages and those
 * extensions register run by priority, lower first; ties keep registration order. Keybinds and
 * vanilla come after the chain, so they are not stages. Client thread only.
 */
public final class InputPipeline {

    /** The active input request: pick, world menu and prompt. */
    public static final int REQUEST = InputOrder.REQUEST;
    /** Selection and editing: the move gizmo, Shift+click editing, selecting and focusing. */
    public static final int SELECTION = InputOrder.SELECTION;
    /** Gizmo handles; a handle is hit only when it is in front, so it may come before elements. */
    public static final int GIZMO = InputOrder.GIZMO;
    /** Interactive elements under the crosshair. */
    public static final int ELEMENTS = InputOrder.ELEMENTS;
    /** The active spatial tool (zones, points, routes) and its keys. */
    public static final int SPATIAL = InputOrder.SPATIAL;
    /** The active bot tool. */
    public static final int TOOLS = InputOrder.TOOLS;

    private record Entry(int priority, int order, InputStage stage) {}

    private final List<Entry> stages = new ArrayList<>();

    /**
     * Adds a stage.
     *
     * @param priority where it runs; see the constants
     * @param stage the stage
     */
    public void add(final int priority, final InputStage stage) {
        stages.add(new Entry(priority, stages.size(), stage));
        stages.sort(Comparator.comparingInt(Entry::priority).thenComparingInt(Entry::order));
    }

    /**
     * Runs a key press through the chain.
     *
     * @return CONSUMED when a stage took it; otherwise keybinds may still take it
     */
    public InputStage.Outcome key(final KeyEvent event, final String name) {
        return run(s -> s.key(event, name));
    }

    /**
     * Runs a mouse button press through the chain.
     *
     * @return CONSUMED when a stage took it; otherwise keybinds may still take it
     */
    public InputStage.Outcome button(final InputConstants.Key key, final String name) {
        return run(s -> s.button(key, name));
    }

    /**
     * Runs a scroll through the chain.
     *
     * @return CONSUMED when a stage took it
     */
    public InputStage.Outcome scroll(final double amount) {
        return run(s -> s.scroll(amount));
    }

    private InputStage.Outcome run(final Function<InputStage, InputStage.Outcome> call) {
        for (final Entry e : stages) {
            final InputStage.Outcome o = call.apply(e.stage());
            if (o != InputStage.Outcome.PASS) {
                return o;
            }
        }
        return InputStage.Outcome.PASS;
    }
}
