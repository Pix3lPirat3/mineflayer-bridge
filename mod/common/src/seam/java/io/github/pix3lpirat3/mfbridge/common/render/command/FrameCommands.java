package io.github.pix3lpirat3.mfbridge.common.render.command;

import java.util.ArrayList;
import java.util.List;

/**
 * The immediate-tier commands of one frame, in two parts. Stable lists are immutable and keep their
 * identity from frame to frame while their content is the same, so a backend prepares each one once
 * (text, bounds) and reuses that until the list is replaced. Extras are the commands that exist for
 * this frame only: shapes that follow entities, previews, tracers.
 *
 * <p>One instance is reused for every frame, so the steady state allocates nothing.
 */
public final class FrameCommands {
    private final ArrayList<List<DrawCommand>> stable = new ArrayList<>();
    private final ArrayList<DrawCommand> extras = new ArrayList<>();

    /** Creates an empty set. */
    public FrameCommands() {
    }

    /** Empties both parts, at the start of a frame. */
    public void clear() {
        stable.clear();
        extras.clear();
    }

    /**
     * Adds a list that is immutable and stays the same instance while its content does not change.
     *
     * @param list the list; empty lists are skipped
     */
    public void addStable(final List<DrawCommand> list) {
        if (!list.isEmpty()) {
            stable.add(list);
        }
    }

    /**
     * Adds one command for this frame only.
     *
     * @param command the command
     */
    public void add(final DrawCommand command) {
        extras.add(command);
    }

    /**
     * Adds commands for this frame only.
     *
     * @param commands the commands; read now and not kept
     */
    public void addAll(final List<DrawCommand> commands) {
        extras.addAll(commands);
    }

    /**
     * Returns the stable lists in the order they were added.
     *
     * @return the lists, valid until the next {@link #clear}
     */
    public List<List<DrawCommand>> stable() {
        return stable;
    }

    /**
     * Returns the commands added for this frame only.
     *
     * @return the commands, valid until the next {@link #clear}
     */
    public List<DrawCommand> extras() {
        return extras;
    }

    /**
     * Returns the number of commands in both parts.
     *
     * @return the count
     */
    public int size() {
        int n = extras.size();
        for (int i = 0; i < stable.size(); i++) {
            n += stable.get(i).size();
        }
        return n;
    }

    /**
     * Returns whether there is nothing to draw.
     *
     * @return {@code true} when both parts are empty
     */
    public boolean isEmpty() {
        return stable.isEmpty() && extras.isEmpty();
    }
}
