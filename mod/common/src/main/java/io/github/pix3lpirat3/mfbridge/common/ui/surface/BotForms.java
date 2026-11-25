package io.github.pix3lpirat3.mfbridge.common.ui.surface;

import io.github.pix3lpirat3.mfbridge.common.Constants;
import io.github.pix3lpirat3.mfbridge.common.ui.UiContext;
import io.github.pix3lpirat3.mfbridge.common.ui.input.Crosshair;
import io.github.pix3lpirat3.mfbridge.common.ui.input.PickController;
import io.github.pix3lpirat3.mfbridge.common.ui.logic.DialogValues;
import io.github.pix3lpirat3.mfbridge.common.ui.logic.FieldSpec;
import io.github.pix3lpirat3.mfbridge.common.ui.logic.FormValues;
import io.github.pix3lpirat3.mfbridge.common.ui.screen.FormScreen;
import io.github.pix3lpirat3.mfbridge.common.version.Game;
import io.github.pix3lpirat3.mfbridge.core.BridgeCore;
import io.github.pix3lpirat3.mfbridge.core.input.FormInput;
import io.github.pix3lpirat3.mfbridge.protocol.core.FormOpen;
import io.github.pix3lpirat3.mfbridge.protocol.core.PickResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * A bot's form on either surface, the bridge's own screen or a vanilla dialog: builds it, checks
 * what the player typed and answers or cancels the request. Client thread only.
 */
public final class BotForms {

    private static final Component SUBMIT = Component.translatable("screen.mineflayer_bridge.form.submit");

    private final UiContext ui;
    private final PickController picks;

    /**
     * Creates the form builder.
     *
     * @param ui the core, and where answered requests are marked handled
     * @param picks runs the block pick of a form field
     */
    public BotForms(final UiContext ui, final PickController picks) {
        this.ui = ui;
        this.picks = picks;
    }

    /** Wraps a bot's form for the dialog surface. */
    Dialog dialog(final FormInput input) {
        return new Dialog(input);
    }

    /** Builds the bridge's own screen for a bot's form. */
    FormScreen screen(final FormInput input) {
        final FormOpen request = input.request();
        final List<FieldSpec> fields = new ArrayList<>(request.fields().size());
        for (final var f : request.fields()) {
            fields.add(FieldSpec.of(f));
        }
        final FormScreen screen = new FormScreen(Component.literal(request.title()), formSubtitle(input), fields, submitLabel(request),
                raw -> answerForm(input, fields, raw),
                () -> cancelForm(input),
                this::pickBlock,
                () -> Crosshair.argContext().crosshairBlock());
        final var buttons = request.buttons();
        if (buttons != null && !buttons.isEmpty()) {
            final List<FormScreen.ExtraButton> extras = new ArrayList<>();
            for (final var b : buttons) {
                extras.add(new FormScreen.ExtraButton(b.id(), b.label(), formIcon(b.icon()), b.danger()));
            }
            screen.extraButtons(extras, id -> pressFormButton(input, id));
        }
        return screen;
    }

    /** The Bridge's own icons by name; anything else is an item id. */
    private static FormScreen.@Nullable Icon formIcon(final @Nullable String icon) {
        if (icon == null || icon.isBlank()) {
            return null;
        }
        if (icon.equals("mineflayer_bridge:trash")) {
            return new FormScreen.Icon.Sprite(Constants.id("icon/trash"));
        }
        final var id = net.minecraft.resources.Identifier.tryParse(icon);
        final var item = id == null ? null : net.minecraft.core.registries.BuiltInRegistries.ITEM.getOptional(id).orElse(null);
        return item == null ? null : new FormScreen.Icon.Item(new net.minecraft.world.item.ItemStack(item));
    }

    private void pressFormButton(final FormInput input, final String button) {
        ui.markHandled(input.id());
        final BridgeCore core = ui.core();
        if (core != null) {
            core.input().pressFormButton(input.id(), button);
        }
    }

    private static Component formSubtitle(final FormInput input) {
        return Component.translatable("mineflayer_bridge.from", input.ownerName());
    }

    private static Component submitLabel(final FormOpen request) {
        return request.submitLabel() == null ? SUBMIT : Component.literal(request.submitLabel());
    }

    /**
     * Validates a form's raw input and resolves the form with it.
     *
     * @return a problem to show the player, or null once the form is answered
     */
    private @Nullable String answerForm(final FormInput input, final List<FieldSpec> fields, final Map<String, Object> raw) {
        final FormValues.Result result = FormValues.validate(I18n::get, fields, raw);
        if (!result.ok()) {
            return result.error();
        }
        final BridgeCore core = ui.core();
        if (core == null) {
            return I18n.get("mineflayer_bridge.bridge.not_running");
        }
        try {
            core.input().resolveForm(input.id(), result.values());
        } catch (final IllegalArgumentException e) {
            return e.getMessage() == null ? I18n.get("mineflayer_bridge.rejected") : e.getMessage();
        }
        ui.markHandled(input.id());
        return null;
    }

    private void cancelForm(final FormInput input) {
        ui.markHandled(input.id());
        final BridgeCore core = ui.core();
        if (core != null) {
            core.input().cancel(input.id());
        }
    }

    /**
     * A form shown with the {@code dialog} surface. A dialog that fails
     * validation closes, as vanilla dialogs do after an action, and reopens on the next tick with the
     * problem shown.
     */
    final class Dialog implements BridgeDialogs.Listener {
        private final FormInput input;
        private final List<FieldSpec> fields = new ArrayList<>();
        private final List<DialogValues.Input> inputs;
        private @Nullable String error;
        private @Nullable Screen shown;
        private boolean finished;

        Dialog(final FormInput input) {
            this.input = input;
            for (final var field : input.request().fields()) {
                fields.add(FieldSpec.of(field));
            }
            this.inputs = DialogValues.formInputs(fields);
        }

        Screen screen() {
            final FormOpen request = input.request();
            final Screen screen = BridgeDialogs.form(Component.literal(request.title()), formSubtitle(input), submitLabel(request), inputs,
                    error, this);
            shown = screen;
            return screen;
        }

        boolean finished() {
            return finished;
        }

        /** The dialog screen shown last, or null before the first. */
        @Nullable Screen shown() {
            return shown;
        }

        /** Marks the form over without telling the bot, for a request that ended on its side. */
        void end() {
            finished = true;
        }

        @Override
        public void submitted(final Map<String, Object> payload) {
            if (finished) {
                return;
            }
            final String problem = answerForm(input, fields, DialogValues.formRaw(inputs, payload));
            if (problem == null) {
                finished = true;
            } else {
                error = problem;
            }
        }

        @Override
        public void cancelled() {
            if (finished) {
                return;
            }
            finished = true;
            cancelForm(input);
        }
    }

    /** Block-pos fields pick in the world; the form closes for the pick and reopens with the result. */
    public void pickBlock(final FormScreen screen, final String fieldId) {
        final Minecraft minecraft = Minecraft.getInstance();
        Game.setScreen(null);
        picks.beginLocal(I18n.get("mineflayer_bridge.pick.form_block"), (final @Nullable PickResult result) -> {
            if (result instanceof PickResult.Block block) {
                screen.setCoordinates(fieldId, block.value().pos());
            }
            if (!screen.finished()) {
                Game.setScreen(screen);
            }
        });
    }
}
