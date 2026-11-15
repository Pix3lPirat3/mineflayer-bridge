package io.github.pix3lpirat3.mfbridge.common.ui.input;

import io.github.pix3lpirat3.mfbridge.common.Constants;
import io.github.pix3lpirat3.mfbridge.common.ui.BridgeUi;
import io.github.pix3lpirat3.mfbridge.common.ui.UiContext;
import io.github.pix3lpirat3.mfbridge.core.BridgeCore;
import io.github.pix3lpirat3.mfbridge.core.decl.InterceptDecl;
import io.github.pix3lpirat3.mfbridge.core.input.InterceptControls;
import io.github.pix3lpirat3.mfbridge.core.input.InterceptOutcome;
import io.github.pix3lpirat3.mfbridge.core.input.InvocationResult;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import org.jspecify.annotations.Nullable;

/**
 * The client half of interaction intercepts. The Minecraft mixin
 * calls {@link #beforeUse}, {@link #beforeAttack} and {@link #beforeContinueAttack} at the head of
 * vanilla's use and attack handling, before any interaction packet is built. The crosshair target
 * vanilla resolved is matched against the bots' rules; when a granted cancel rule matches, vanilla
 * does not run, so no use, attack, punch or destroy packet is sent, and a rejection from the
 * cancelling bot shows as a toast.
 *
 * <p>Holding attack on a block whose start was cancelled keeps cancelling without new events until
 * the crosshair moves to another block, which counts as a new interaction, as vanilla's own block
 * breaking does. The cancel permission itself is
 * asked through the batched permission prompt ({@code PermissionPrompts}). Client thread only.
 */
public final class InterceptClient {

    private final UiContext ui;
    private @Nullable BlockPos heldBlock;
    private boolean heldCancelled;

    /**
     * Creates the client.
     *
     * @param ui the UI services
     */
    public InterceptClient(final UiContext ui) {
        this.ui = ui;
    }

    /**
     * Called before vanilla's use (right click) runs.
     *
     * @return true when a rule cancelled it and vanilla must not run
     */
    public boolean beforeUse() {
        return intercept(InterceptControls.SECONDARY);
    }

    /**
     * Called before vanilla's attack (left click) runs.
     *
     * @return true when a rule cancelled it and vanilla must not run
     */
    public boolean beforeAttack() {
        final boolean cancelled = intercept(InterceptControls.PRIMARY);
        final HitResult hit = Minecraft.getInstance().hitResult;
        heldBlock = hit instanceof BlockHitResult b && hit.getType() == HitResult.Type.BLOCK ? b.getBlockPos() : null;
        heldCancelled = cancelled && heldBlock != null;
        return cancelled;
    }

    /**
     * Called every tick vanilla continues an attack, which breaks the block under the crosshair.
     *
     * @param down whether attack is still held
     * @return true when vanilla must not continue on this block
     */
    public boolean beforeContinueAttack(final boolean down) {
        if (!down) {
            heldBlock = null;
            heldCancelled = false;
            return false;
        }
        final HitResult hit = Minecraft.getInstance().hitResult;
        if (!(hit instanceof BlockHitResult b) || hit.getType() != HitResult.Type.BLOCK) {
            return false;
        }
        final BlockPos pos = b.getBlockPos();
        if (pos.equals(heldBlock)) {
            return heldCancelled;
        }
        heldBlock = pos.immutable();
        heldCancelled = intercept(InterceptControls.PRIMARY);
        return heldCancelled;
    }

    private boolean intercept(final String input) {
        final BridgeCore core = ui.core();
        final Minecraft minecraft = Minecraft.getInstance();
        final ClientLevel level = minecraft.level;
        final HitResult vanilla = minecraft.hitResult;
        if (core == null || level == null || minecraft.player == null || vanilla == null
                || core.declarations().intercepts().isEmpty()) {
            return false;
        }
        final Crosshair.Hit hit;
        switch (vanilla) {
            case BlockHitResult b when b.getType() == HitResult.Type.BLOCK ->
                    hit = new Crosshair.Hit(HitResult.Type.BLOCK, b.getBlockPos(), b.getDirection(), level.getBlockState(b.getBlockPos()),
                            null, b.getLocation());
            case EntityHitResult e -> hit = new Crosshair.Hit(HitResult.Type.ENTITY, null, null, null, e.getEntity(), e.getLocation());
            default -> {
                return false;
            }
        }
        final InterceptControls intercepts = core.intercepts();
        final List<InterceptDecl> rules = intercepts.matching(input, modifiers(minecraft), spec -> Crosshair.matches(spec, hit));
        if (rules.isEmpty()) {
            return false;
        }
        final InterceptOutcome outcome = intercepts.dispatch(rules, type -> Crosshair.target(hit, type));
        final InterceptDecl by = outcome.cancelledBy();
        final CompletableFuture<InvocationResult> result = outcome.result();
        if (by == null) {
            return false;
        }
        if (result != null) {
            result.whenComplete((r, error) -> {
                if (error != null) {
                    ui.toastError(by.ownerName(), BridgeUi.describe(error));
                } else if (!r.ok()) {
                    ui.toastError(by.ownerName(), r.message() == null || r.message().isEmpty() ? I18n.get("mineflayer_bridge.intercepts.blocked") : r.message());
                }
            });
        }
        return true;
    }

    /** Modifier bits as {@code element.event} uses them: bit 0 shift, bit 1 control, bit 2 alt. */
    private static int modifiers(final Minecraft minecraft) {
        return (minecraft.hasShiftDown() ? 1 : 0) | (minecraft.hasControlDown() ? 2 : 0) | (minecraft.hasAltDown() ? 4 : 0);
    }

    /** Flips the pause switch of every intercept, for the pause key. */
    public void togglePause() {
        final BridgeCore core = ui.core();
        if (core == null) {
            return;
        }
        final boolean paused = !core.intercepts().paused();
        core.intercepts().setPaused(paused);
        ui.toastInfo(Constants.MOD_NAME, I18n.get(paused ? "mineflayer_bridge.intercepts.paused" : "mineflayer_bridge.intercepts.resumed"));
    }
}
