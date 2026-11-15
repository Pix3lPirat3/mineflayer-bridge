package io.github.pix3lpirat3.mfbridge.common.mixin;

import io.github.pix3lpirat3.mfbridge.common.ui.BridgeUi;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Keeps attack, use and pick-block from running while a pick, world menu, hovered interactive element
 * or tool owns the mouse:
 * their clicks never reach vanilla, so no attack, use or punch packet is sent. Raw clicks are
 * already consumed in {@link MouseHandlerMixin}; this also covers buttons held since before the pick
 * started and keyboard-bound attack or use, which 26.3 runs from {@code Minecraft.handleKeybinds}.
 *
 * <p>The same points run interaction intercepts before vanilla
 * builds any use, attack, punch or destroy packet; a cancelled interaction returns as vanilla does
 * when it has nothing to do.
 */
@Mixin(Minecraft.class)
abstract class MinecraftMixin {

    @Shadow
    private int rightClickDelay;

    @Shadow
    protected int missTime;

    @Shadow
    public @Nullable MultiPlayerGameMode gameMode;

    @Shadow
    public @Nullable LocalPlayer player;

    @Unique
    private static boolean mfbridge$captured() {
        final BridgeUi ui = BridgeUi.current();
        return ui != null && ui.capturesGameInput();
    }

    /** Whether vanilla would act on this click at all; intercepts only hear clicks that would do something. */
    @Unique
    private boolean mfbridge$canAct() {
        final MultiPlayerGameMode mode = gameMode;
        final LocalPlayer p = player;
        return mode != null && p != null && !mode.isSpectator() && !p.isHandsBusy();
    }

    @Inject(method = "startAttack", at = @At("HEAD"), cancellable = true)
    private void mfbridge$startAttack(final CallbackInfoReturnable<Boolean> cir) {
        if (mfbridge$captured()) {
            cir.setReturnValue(false);
            return;
        }
        final BridgeUi ui = BridgeUi.current();
        if (ui != null && missTime <= 0 && mfbridge$canAct() && ui.intercepts().beforeAttack()) {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "continueAttack", at = @At("HEAD"), cancellable = true)
    private void mfbridge$continueAttack(final boolean down, final CallbackInfo ci) {
        if (mfbridge$captured()) {
            ci.cancel();
            return;
        }
        final BridgeUi ui = BridgeUi.current();
        if (ui != null && missTime <= 0 && mfbridge$canAct() && ui.intercepts().beforeContinueAttack(down)) {
            ci.cancel();
        }
    }

    @Inject(method = "startUseItem", at = @At("HEAD"), cancellable = true)
    private void mfbridge$startUseItem(final CallbackInfo ci) {
        if (mfbridge$captured()) {
            ci.cancel();
            return;
        }
        final BridgeUi ui = BridgeUi.current();
        final MultiPlayerGameMode mode = gameMode;
        if (ui != null && mode != null && !mode.isDestroying() && mfbridge$canAct() && ui.intercepts().beforeUse()) {
            // 26.3 startUseItem sets this delay first; without it a held use key would fire every tick.
            rightClickDelay = 4;
            ci.cancel();
        }
    }

    /**
     * The bridge raycast runs right after vanilla's crosshair pick and
     * replaces its target with a miss when an interactive element is in front, so attack and use can
     * never act on what is behind the element.
     */
    @Inject(method = "pick", at = @At("TAIL"))
    private void mfbridge$afterPick(final float partialTicks, final CallbackInfo ci) {
        final BridgeUi ui = BridgeUi.current();
        if (ui != null) {
            ui.afterPick();
        }
    }

    @Inject(method = "pickBlockOrEntity", at = @At("HEAD"), cancellable = true)
    private void mfbridge$pickBlockOrEntity(final CallbackInfo ci) {
        if (mfbridge$captured()) {
            ci.cancel();
        }
    }
}
