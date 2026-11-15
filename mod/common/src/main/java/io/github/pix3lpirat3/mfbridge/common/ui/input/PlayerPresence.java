package io.github.pix3lpirat3.mfbridge.common.ui.input;

import io.github.pix3lpirat3.mfbridge.core.BridgeCore;
import net.minecraft.client.Minecraft;

/**
 * What the player is doing, for bots: alive, away (no input for a while) and whether the game
 * window has focus. Client thread.
 */
public final class PlayerPresence {

    private long lastInputMs = System.currentTimeMillis();
    private double mouseX = Double.NaN;
    private double mouseY = Double.NaN;

    /** The player pressed a key, a mouse button or scrolled. */
    public void input() {
        lastInputMs = System.currentTimeMillis();
    }

    /**
     * Checks the player's state once a tick and reports it to the core, which sends changes only.
     *
     * @param core the core
     * @param awayMinutes minutes without input before the player counts as away; 0 never
     */
    public void tick(final BridgeCore core, final int awayMinutes) {
        final Minecraft mc = Minecraft.getInstance();
        final double x = mc.mouseHandler.xpos();
        final double y = mc.mouseHandler.ypos();
        if (x != mouseX || y != mouseY) {
            mouseX = x;
            mouseY = y;
            input();
        }
        final boolean alive = mc.player == null || !mc.player.isDeadOrDying();
        final boolean away = awayMinutes > 0 && System.currentTimeMillis() - lastInputMs >= awayMinutes * 60_000L;
        core.setPlayerState(alive, away, mc.isWindowActive());
    }
}
