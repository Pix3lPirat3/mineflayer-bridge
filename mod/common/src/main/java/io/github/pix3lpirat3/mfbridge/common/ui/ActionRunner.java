package io.github.pix3lpirat3.mfbridge.common.ui;

import io.github.pix3lpirat3.mfbridge.common.ui.input.Crosshair;
import io.github.pix3lpirat3.mfbridge.core.BridgeCore;
import io.github.pix3lpirat3.mfbridge.core.decl.ActionDecl;
import io.github.pix3lpirat3.mfbridge.protocol.core.Target;
import io.github.pix3lpirat3.mfbridge.protocol.core.Vec3d;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

/** Sends a bot's action for a target and follows its answer. Client thread only. */
public final class ActionRunner {

    private final UiContext ui;

    /**
     * Creates the runner.
     *
     * @param ui the core and the tracker of answers
     */
    public ActionRunner(final UiContext ui) {
        this.ui = ui;
    }

    /**
     * Invokes an action on a target and tracks its result; does nothing while the core is down.
     * {@code source} tells the bot how the player ran it.
     */
    public void invoke(final ActionDecl a, final Target target, final String source) {
        invoke(a, target, source, List.of());
    }

    /** As above, naming the bots the player had selected (on the map), for the bot to send its order to. */
    public void invoke(final ActionDecl a, final Target target, final String source, final List<String> bots) {
        final BridgeCore core = ui.core();
        if (core != null) {
            ui.track(a.data().label(), a.ownerName(), core.invokeAction(a.key(), target, source, bots));
        }
    }

    /** Runs a global action on what the crosshair is on, as the palette does. */
    public void runGlobal(final ActionDecl a) {
        if (ui.core() == null) {
            return;
        }
        final String type = a.data().target().type();
        final Target resolved = Crosshair.target(Crosshair.hit(Crosshair.ACTION_RANGE, e -> true), type);
        invoke(a, resolved != null ? resolved : new Target(type, null, null, null, null, null, null), "palette");
    }

    /**
     * Runs a global action from a menu rather than the crosshair, so global actions work
     * on the title screen. An {@code air} target sits at the player's position in a world and has no
     * position on the title screen; other target types carry only their type.
     *
     * @param a the action
     */
    public void runAtPlayer(final ActionDecl a) {
        final String type = a.data().target().type();
        final LocalPlayer player = Minecraft.getInstance().player;
        invoke(a, player != null && type.equals("air")
                ? new Target(type, null, null, null, new Vec3d(player.getX(), player.getY(), player.getZ()), null, null)
                : new Target(type), "screen");
    }

    /**
     * Runs an action whose target is a bot, such as {@code bridge:window}'s "Open inventory", on that bot.
     *
     * @param a the action; its target type must be {@code bot}
     * @param botOwner the target bot's owner, {@code bot:<identity>}
     */
    public void runOnBot(final ActionDecl a, final String botOwner) {
        invoke(a, new Target("bot", null, null, null, null, null, botOwner), "screen");
    }
}
