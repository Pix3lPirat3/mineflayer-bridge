package io.github.pix3lpirat3.mfbridge.common.mixin;

import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.suggestion.Suggestions;
import io.github.pix3lpirat3.mfbridge.common.ui.chat.ChatBridgeClient;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.CommandSuggestions;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.multiplayer.ClientSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Chat bridge: {@code @bot} lines get tab completion and validation from the same engine as
 * {@code /bridge}. In 26.3 {@code updateCommandInfo} completes plain chat from the server's custom
 * tab suggestions; for a line addressed to attached bots the bridge's suggestions replace them and a
 * parse error is shown in the usage line above the chat box. Other lines are left to vanilla.
 */
@Mixin(CommandSuggestions.class)
abstract class CommandSuggestionsMixin {

    @Shadow
    @Final
    private Minecraft minecraft;

    @Shadow
    @Final
    private EditBox input;

    @Shadow
    @Final
    private boolean commandsOnly;

    @Shadow
    @Final
    private List<FormattedCharSequence> commandUsage;

    @Shadow
    private int commandUsagePosition;

    @Shadow
    private @Nullable ParseResults<ClientSuggestionProvider> currentParse;

    @Shadow
    private @Nullable CompletableFuture<Suggestions> pendingSuggestions;

    @Shadow
    private boolean currentParseIsCommand;

    @Shadow
    private boolean currentParseIsMessage;

    @Shadow
    private boolean allowSuggestions;

    @Shadow
    private boolean keepSuggestions;

    @Shadow
    public abstract void showSuggestions(boolean immediateNarration);

    @Shadow
    public abstract void hide();

    @Shadow
    private void recomputeUsageBoxWidth() {
        throw new AssertionError("shadowed");
    }

    @Inject(method = "updateCommandInfo", at = @At("HEAD"), cancellable = true)
    private void mfbridge$completeBotLines(final CallbackInfo ci) {
        final String value = input.getValue();
        final ChatBridgeClient chat = ChatBridgeClient.current();
        if (commandsOnly || value.startsWith("/") || chat == null) {
            return;
        }
        final CompletableFuture<Suggestions> future = chat.suggestions(value, input.getCursorPosition());
        if (future == null) {
            return;
        }
        ci.cancel();
        if (!keepSuggestions) {
            input.setSuggestion(null);
            hide();
        }
        currentParse = null;
        currentParseIsCommand = false;
        currentParseIsMessage = true;
        commandUsage.clear();
        final String error = chat.validationError(value);
        if (error != null) {
            commandUsage.add(Component.literal(error).withStyle(ChatFormatting.RED).getVisualOrderText());
        }
        recomputeUsageBoxWidth();
        commandUsagePosition = 0;
        pendingSuggestions = future;
        future.thenRun(() -> {
            if (pendingSuggestions != future) {
                return;
            }
            // The bot's usage line takes the slot while the line parses; a parse error keeps it.
            final Component usage = error == null ? chat.usage() : null;
            if (usage != null) {
                commandUsage.clear();
                commandUsage.add(usage.getVisualOrderText());
                recomputeUsageBoxWidth();
            }
            if (allowSuggestions && minecraft.options.autoSuggestions().get()) {
                hide();
                showSuggestions(false);
            }
        });
    }
}
