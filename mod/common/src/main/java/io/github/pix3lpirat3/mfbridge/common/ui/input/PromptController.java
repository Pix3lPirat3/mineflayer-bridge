package io.github.pix3lpirat3.mfbridge.common.ui.input;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.pix3lpirat3.mfbridge.common.Constants;
import io.github.pix3lpirat3.mfbridge.common.ui.UiContext;
import io.github.pix3lpirat3.mfbridge.common.ui.logic.DialogValues;
import io.github.pix3lpirat3.mfbridge.common.ui.logic.Surfaces;
import io.github.pix3lpirat3.mfbridge.common.ui.screen.PromptAnswers;
import io.github.pix3lpirat3.mfbridge.common.ui.screen.TextPromptScreen;
import io.github.pix3lpirat3.mfbridge.common.ui.surface.AnvilPromptScreen;
import io.github.pix3lpirat3.mfbridge.common.ui.surface.BookPromptScreen;
import io.github.pix3lpirat3.mfbridge.common.ui.surface.BridgeDialogs;
import io.github.pix3lpirat3.mfbridge.common.ui.surface.CraftingPromptScreen;
import io.github.pix3lpirat3.mfbridge.common.ui.surface.EnchantingPromptScreen;
import io.github.pix3lpirat3.mfbridge.common.ui.surface.MerchantPromptScreen;

import io.github.pix3lpirat3.mfbridge.common.ui.surface.StonecutterPromptScreen;
import io.github.pix3lpirat3.mfbridge.common.version.Game;
import io.github.pix3lpirat3.mfbridge.common.version.Versions;
import io.github.pix3lpirat3.mfbridge.core.BridgeCore;
import io.github.pix3lpirat3.mfbridge.core.input.PromptInput;
import io.github.pix3lpirat3.mfbridge.protocol.core.PromptKind;
import io.github.pix3lpirat3.mfbridge.protocol.core.PromptRequest;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * Runs the active prompt as a non-modal HUD card. Confirm answers with
 * Y or N, choice with number keys or the arrows and Enter, text and number open a small input screen
 * on Enter. Esc cancels. Only those keys are taken; the player can keep moving. Confirm and choice
 * prompts with surface or placement {@code world} are shown as a world menu by {@code WorldMenus}
 * instead of the card.
 *
 * <p>A prompt with surface {@code dialog}, {@code anvil}, {@code sign}
 * or {@code book} opens that screen as soon as no other screen is open, and Enter on the card reopens
 * it after Esc. Surface {@code chat} keeps the card, shows an action-bar hint and takes the next chat
 * line (routed by the core's chat bridge). A surface that cannot open falls back to the bridge input
 * screen. Every answer reports the surface that was showing in {@code prompt.result}, and the core
 * is told whenever it changes so a timeout reports it too. Client thread only.
 */
public final class PromptController implements PromptAnswers {

    private static final long CHAT_HINT_MS = 2_500L;

    private final UiContext ui;
    private @Nullable PromptInput input;
    private int selected;
    private boolean autoOpen;
    private long hintAtMs;
    private String shown = "hud";

    /**
     * Creates the controller.
     *
     * @param ui the UI services
     */
    public PromptController(final UiContext ui) {
        this.ui = ui;
    }

    /**
     * Returns the active prompt.
     *
     * @return the prompt, or null
     */
    @Override
    public @Nullable PromptInput active() {
        return input;
    }

    /**
     * Returns the highlighted choice index for choice prompts.
     *
     * @return the index into the choices
     */
    public int selected() {
        return selected;
    }

    /**
     * Shows a prompt, replacing any other without answering it.
     *
     * @param prompt the prompt from the input queue
     */
    public void begin(final PromptInput prompt) {
        final String problem = Surfaces.unavailable(prompt.request());
        if (problem != null) {
            input = prompt;
            unsupported(problem);
            return;
        }
        input = prompt;
        selected = 0;
        final PromptRequest r = prompt.request();
        autoOpen = Surfaces.opensScreen(Surfaces.forPrompt(r));
        hintAtMs = Long.MIN_VALUE;
        final Surfaces.Surface surface = Surfaces.forPrompt(r);
        shown(prompt, surface == Surfaces.Surface.CHAT || surface == Surfaces.Surface.WORLD ? Surfaces.wireName(surface) : "hud");
        final List<String> choices = r.choices();
        if (r.kind() == PromptKind.CHOICE && choices != null && r.defaultValue() != null) {
            final int index = choices.indexOf(r.defaultValue().toString());
            selected = Math.max(index, 0);
        }
    }

    private void shown(final PromptInput prompt, final String surface) {
        shown = surface;
        final BridgeCore core = ui.core();
        if (core != null) {
            core.input().surfaceShown(prompt.id(), surface);
        }
    }

    /**
     * Returns whether the active prompt shows as a world menu rather than the card.
     *
     * @return true for surface {@code world}
     */
    public boolean inWorld() {
        return input != null && "world".equals(shown);
    }

    /** Client tick: opens a vanilla surface once the player is free, and repeats the chat hint. */
    public void tick() {
        final PromptInput in = input;
        if (in == null) {
            return;
        }
        final Minecraft minecraft = Minecraft.getInstance();
        final Surfaces.Surface surface = Surfaces.forPrompt(in.request());
        if (surface == Surfaces.Surface.CHAT) {
            final long now = ui.nowMs();
            if (hintAtMs == Long.MIN_VALUE || now - hintAtMs >= CHAT_HINT_MS) {
                hintAtMs = now;
                Game.overlayMessage(Component.translatable("mineflayer_bridge.prompt.chat_hint", in.ownerName(),
                        in.request().text()), false);
            }
        }
        if (autoOpen && UiContext.inPlay()) {
            autoOpen = false;
            openInput();
        }
    }

    /** Hides the prompt without answering it. */
    public void end() {
        input = null;
        autoOpen = false;
    }

    /**
     * Handles a key press while no screen is open.
     *
     * @param event the key
     * @return whether the key answered or navigated the prompt
     */
    public boolean keyPressed(final KeyEvent event) {
        final PromptInput in = input;
        if (in == null) {
            return false;
        }
        if (event.isEscape()) {
            cancel();
            return true;
        }
        final PromptRequest r = in.request();
        final Surfaces.Surface menu = Surfaces.forPrompt(r);
        if (event.isConfirmation() && Surfaces.menuStyled(menu)) {
            openInput();
            return true;
        }
        return switch (r.kind()) {
            case CONFIRM -> {
                final int key = Game.shortcutKey(event);
                if (key == 'y') {
                    answer(Boolean.TRUE, cardSurface());
                    yield true;
                }
                if (key == 'n') {
                    answer(Boolean.FALSE, cardSurface());
                    yield true;
                }
                if (event.isConfirmation() && r.defaultValue() instanceof Boolean b) {
                    answer(b, cardSurface());
                    yield true;
                }
                yield false;
            }
            case CHOICE -> choiceKey(event, r.choices() == null ? List.of() : r.choices());
            case TEXT, NUMBER, LINES, PAGES -> {
                if (event.isConfirmation()) {
                    openInput();
                    yield true;
                }
                yield false;
            }
        };
    }

    /** Keys answer on the card, except for world prompts, where the menu stays the surface shown. */
    private String cardSurface() {
        return "world".equals(shown) ? "world" : "hud";
    }

    private boolean choiceKey(final KeyEvent event, final List<String> choices) {
        if (choices.isEmpty()) {
            return false;
        }
        if (event.isUp()) {
            selected = Math.floorMod(selected - 1, choices.size());
            return true;
        }
        if (event.isDown()) {
            selected = Math.floorMod(selected + 1, choices.size());
            return true;
        }
        if (event.isConfirmation()) {
            answer(choices.get(Math.min(selected, choices.size() - 1)), cardSurface());
            return true;
        }
        final OptionalInt digit = InputConstants.getKey(event).getNumericKeyValue();
        if (digit.isPresent() && digit.getAsInt() >= 1 && digit.getAsInt() <= Math.min(9, choices.size())) {
            answer(choices.get(digit.getAsInt() - 1), cardSurface());
            return true;
        }
        return false;
    }

    /** Opens the input screen of the active prompt's surface, or the bridge text input screen. */
    public void openInput() {
        final PromptInput in = input;
        if (in == null) {
            return;
        }
        final Minecraft minecraft = Minecraft.getInstance();
        final PromptKind kind = in.request().kind();
        final boolean typed = kind != PromptKind.CONFIRM && kind != PromptKind.CHOICE;
        Screen screen;
        final Surfaces.Surface surface = Surfaces.forPrompt(in.request());
        String used = Surfaces.wireName(surface);
        try {
            screen = switch (surface) {
                case DIALOG -> BridgeDialogs.prompt(in.ownerName(), in.request(), new DialogAnswer(in));
                case ANVIL -> new AnvilPromptScreen(in, this);
                case SIGN -> Versions.CURRENT.signPrompt(in, this);
                case BOOK -> new BookPromptScreen(in, this);
                case CHAT -> new ChatScreen("", false);
                case STONECUTTER -> new StonecutterPromptScreen(in, this);
                case ENCHANTING -> new EnchantingPromptScreen(in, this);
                case MERCHANT -> new MerchantPromptScreen(in, this);
                case CRAFTING -> new CraftingPromptScreen(in, this, minecraft.player == null ? null : minecraft.player.getInventory());
                case HUD, WORLD -> typed ? new TextPromptScreen(in, this) : null;
            };
        } catch (final RuntimeException e) {
            if (Surfaces.rejectsFallback(surface)) {
                // Crafting and merchant answers fit no card, so the bot hears unsupported instead of a fallback.
                Constants.LOG.warn("Prompt surface {} failed to open; rejecting the prompt", in.request().surface(), e);
                unsupported("the " + Surfaces.wireName(surface) + " surface could not open");
                return;
            }
            // An unavailable surface falls back to the bridge's own screen.
            Constants.LOG.warn("Prompt surface {} failed to open; using the bridge input", in.request().surface(), e);
            screen = typed ? new TextPromptScreen(in, this) : null;
            used = "hud";
        }
        if (screen instanceof TextPromptScreen) {
            used = "hud";
        }
        if (screen != null && !(screen instanceof ChatScreen)) {
            shown(in, used);
        }
        if (screen != null) {
            Game.setScreen(screen);
        }
    }

    /** Answers from a bridge dialog; Esc or Back leaves the prompt on the card. */
    private final class DialogAnswer implements BridgeDialogs.Listener {
        private final PromptInput prompt;

        DialogAnswer(final PromptInput prompt) {
            this.prompt = prompt;
        }

        @Override
        public void submitted(final Map<String, Object> payload) {
            if (input != prompt) {
                return;
            }
            try {
                answer(DialogValues.promptAnswer(I18n::get, prompt.request(), payload));
            } catch (final IllegalArgumentException e) {
                ui.toastError(prompt.ownerName(), String.valueOf(e.getMessage()));
            }
        }

        @Override
        public void cancelled() {
            // The card stays; Enter reopens the dialog.
        }
    }

    /**
     * Answers the active prompt from the surface last shown.
     *
     * @param value a {@code Boolean}, a choice, a {@code String} or a {@code Number}, matching the kind
     */
    @Override
    public void answer(final Object value) {
        answer(value, shown);
    }

    /**
     * Answers the active prompt, reporting the surface that took the answer.
     *
     * @param value a {@code Boolean}, a choice, a {@code String} or a {@code Number}, matching the kind
     * @param surface the surface's wire name, for example {@code hud}
     */
    @Override
    public void answer(final Object value, final String surface) {
        final PromptInput in = input;
        final BridgeCore core = ui.core();
        input = null;
        if (in == null || core == null) {
            return;
        }
        ui.markHandled(in.id());
        try {
            core.input().resolvePrompt(in.id(), value, surface);
        } catch (final IllegalArgumentException e) {
            ui.toastError(in.ownerName(), I18n.get("mineflayer_bridge.prompt.rejected", e.getMessage()));
        }
    }

    /** Rejects the active prompt because its surface cannot be shown; the bot gets {@code unsupported}. */
    private void unsupported(final String message) {
        final PromptInput in = input;
        final BridgeCore core = ui.core();
        input = null;
        autoOpen = false;
        if (in != null && core != null) {
            ui.markHandled(in.id());
            core.input().unsupported(in.id(), message);
        }
    }

    /** Dismisses the active prompt; the bot gets {@code cancelled}. */
    public void cancel() {
        final PromptInput in = input;
        final BridgeCore core = ui.core();
        input = null;
        if (in != null && core != null) {
            ui.markHandled(in.id());
            core.input().cancel(in.id());
        }
    }

    /**
     * Returns the time left before the prompt times out with its default.
     *
     * @return milliseconds
     */
    @Override
    public long remainingMs() {
        final PromptInput in = input;
        return in == null ? 0L : Math.max(0L, in.deadlineMs() - ui.nowMs());
    }
}
