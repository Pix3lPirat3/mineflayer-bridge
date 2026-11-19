package io.github.pix3lpirat3.mfbridge.common.ui.surface;

import io.github.pix3lpirat3.mfbridge.common.Constants;
import io.github.pix3lpirat3.mfbridge.common.ui.logic.CommandArgs;
import io.github.pix3lpirat3.mfbridge.common.ui.logic.DialogValues;
import io.github.pix3lpirat3.mfbridge.common.ui.logic.FieldSpec;
import io.github.pix3lpirat3.mfbridge.common.ui.logic.FormValues;
import io.github.pix3lpirat3.mfbridge.common.ui.logic.TextWrap;
import io.github.pix3lpirat3.mfbridge.common.ui.screen.BridgeScreen;
import io.github.pix3lpirat3.mfbridge.common.ui.screen.UiText;
import io.github.pix3lpirat3.mfbridge.protocol.core.PromptKind;
import io.github.pix3lpirat3.mfbridge.protocol.core.PromptRequest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.dialog.DialogConnectionAccess;
import net.minecraft.client.gui.screens.dialog.MultiButtonDialogScreen;
import net.minecraft.core.Holder;
import net.minecraft.nbt.ByteTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NumericTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.ServerLinks;
import net.minecraft.server.dialog.ActionButton;
import net.minecraft.server.dialog.CommonButtonData;
import net.minecraft.server.dialog.CommonDialogData;
import net.minecraft.server.dialog.Dialog;
import net.minecraft.server.dialog.DialogAction;
import net.minecraft.server.dialog.Input;
import net.minecraft.server.dialog.MultiActionDialog;
import net.minecraft.server.dialog.action.CustomAll;
import net.minecraft.server.dialog.body.DialogBody;
import net.minecraft.server.dialog.body.PlainMessage;
import net.minecraft.server.dialog.input.BooleanInput;
import net.minecraft.server.dialog.input.InputControl;
import net.minecraft.server.dialog.input.NumberRangeInput;
import net.minecraft.server.dialog.input.SingleOptionInput;
import net.minecraft.server.dialog.input.TextInput;
import org.jspecify.annotations.Nullable;

/**
 * The {@code dialog} surface: prompts and forms shown with vanilla
 * dialog screens. The dialogs are built here from the bot's request, not received from a server, and
 * their actions are {@code custom} actions in the mod's namespace. They run through {@link Access}, a
 * {@link DialogConnectionAccess} that hands them to the bridge instead of the network, so no custom
 * click action packet is sent. Client thread only.
 */
public final class BridgeDialogs {

    /** The action of submit, answer and choice buttons; the payload carries the inputs. */
    public static final Identifier SUBMIT = Constants.id("dialog/submit");
    /** The action of the exit button and of Esc. */
    public static final Identifier CANCEL = Constants.id("dialog/cancel");

    private static final int WIDTH = 250;
    private static final int BUTTON_WIDTH = 150;
    /** Room vanilla keeps beside a dialog title for its warning button. */
    private static final int TITLE_MARGIN = 60;
    private static final int TEXT_LIMIT = 1024;
    private static final int ERROR_RGB = 0xFF5555;

    private BridgeDialogs() {
    }

    /**
     * Returns whether a screen is one of the bridge's dialogs.
     *
     * @param screen any screen
     * @return true for a dialog made here
     */
    public static boolean owns(final Screen screen) {
        return screen instanceof Screen26;
    }

    /** What the player did with a bridge dialog. Called on the client thread. */
    public interface Listener {
        /**
         * The player pressed a submit, answer or choice button.
         *
         * @param payload dialog input key to value ({@code String}, {@code Boolean} or {@code Number}),
         *     plus {@link DialogValues#BUTTON}
         */
        void submitted(Map<String, Object> payload);

        /** The player pressed the exit button, Esc, or disconnect on the dialog warning screen. */
        void cancelled();
    }

    /** A vanilla multi-action dialog screen marked as a bridge screen, for input arbitration and the packet guard. */
    static final class Screen26 extends MultiButtonDialogScreen implements BridgeScreen {
        Screen26(final MultiActionDialog dialog, final DialogConnectionAccess access) {
            super(null, dialog, access);
        }
    }

    /**
     * Stands in for the server connection of a bridge dialog. Nothing here touches the network:
     * custom actions go to the listener, and the commands, dialogs and disconnect requests vanilla may
     * route through it are dropped.
     */
    static final class Access implements DialogConnectionAccess {
        private final Listener listener;

        Access(final Listener listener) {
            this.listener = listener;
        }

        @Override
        public void disconnect(final Component message) {
            // The dialog warning screen offers "disconnect"; for a bridge dialog that only closes it.
            listener.cancelled();
            Minecraft.getInstance().gui.setScreen(null);
        }

        @Override
        public void runCommand(final String command, final @Nullable Screen activeScreen) {
            Constants.LOG.warn("Bridge dialog tried to run a command; ignored");
            Minecraft.getInstance().gui.setScreen(activeScreen);
        }

        @Override
        public void openDialog(final Holder<Dialog> dialog, final @Nullable Screen activeScreen) {
            Constants.LOG.warn("Bridge dialog tried to open another dialog; ignored");
            Minecraft.getInstance().gui.setScreen(activeScreen);
        }

        @Override
        public void sendCustomAction(final Identifier id, final Optional<Tag> payload) {
            if (SUBMIT.equals(id)) {
                listener.submitted(payload.map(BridgeDialogs::values).orElseGet(Map::of));
            } else if (CANCEL.equals(id)) {
                listener.cancelled();
            } else {
                Constants.LOG.warn("Bridge dialog sent unknown action {}; ignored", id);
            }
        }

        @Override
        public ServerLinks serverLinks() {
            return ServerLinks.EMPTY;
        }
    }

    /** Converts an action payload: strings, bytes as booleans (vanilla checkboxes), other numbers. */
    static Map<String, Object> values(final Tag tag) {
        final Map<String, Object> out = new LinkedHashMap<>();
        if (tag instanceof CompoundTag compound) {
            for (final String key : compound.keySet()) {
                final Tag v = compound.get(key);
                switch (v) {
                    case StringTag(final String s) -> out.put(key, s);
                    case ByteTag(final byte b) -> out.put(key, b != 0);
                    case NumericTag n -> out.put(key, n.box());
                    case null -> {
                    }
                    default -> out.put(key, v.toString());
                }
            }
        }
        return out;
    }

    private static ActionButton button(final Component label, final String id) {
        final CompoundTag additions = new CompoundTag();
        additions.putString(DialogValues.BUTTON, id);
        // A label wider than the button is cut with an ellipsis and shown whole in the tooltip.
        final String full = label.getString();
        final String shown = TextWrap.ellipsize(full, BUTTON_WIDTH - 8, UiText.width(Minecraft.getInstance().font));
        final CommonButtonData data = shown.equals(full) ? new CommonButtonData(label, BUTTON_WIDTH)
                : new CommonButtonData(Component.literal(shown), Optional.of(Component.literal(full)), BUTTON_WIDTH);
        return new ActionButton(data, Optional.of(new CustomAll(SUBMIT, Optional.of(additions))));
    }

    private static ActionButton exit() {
        return new ActionButton(new CommonButtonData(CommonComponents.GUI_BACK, BUTTON_WIDTH), Optional.of(new CustomAll(CANCEL, Optional.empty())));
    }

    /**
     * Builds the dialog screen. Vanilla draws the title on one line however long it is, so a title
     * too wide for the window is cut there and given whole as the first body line, which wraps.
     */
    private static Screen screen(final Component title, final List<DialogBody> body, final List<Input> inputs,
            final List<ActionButton> actions, final int columns, final Listener listener) {
        final Minecraft minecraft = Minecraft.getInstance();
        final String full = title.getString();
        final String shown = TextWrap.ellipsize(full, minecraft.getWindow().getGuiScaledWidth() - TITLE_MARGIN, UiText.width(minecraft.font));
        final Component heading = shown.equals(full) ? title : Component.literal(shown);
        final List<DialogBody> all = new ArrayList<>(body.size() + 1);
        if (!shown.equals(full)) {
            all.add(message(title));
        }
        all.addAll(body);
        // Two columns of buttons only while the window has room for them.
        final int fit = Math.max(1, (minecraft.getWindow().getGuiScaledWidth() - 16) / (BUTTON_WIDTH + 4));
        final CommonDialogData common = new CommonDialogData(heading, Optional.empty(), true, false, DialogAction.CLOSE, all, inputs);
        return new Screen26(new MultiActionDialog(common, actions, Optional.of(exit()), Math.min(columns, fit)), new Access(listener));
    }

    /** Width of the body and the inputs: vanilla's 250, less on a narrow window. */
    private static int width() {
        return Math.max(60, Math.min(WIDTH, Minecraft.getInstance().getWindow().getGuiScaledWidth() - 20));
    }

    private static PlainMessage message(final Component text) {
        return new PlainMessage(text, width());
    }

    /**
     * Builds the dialog for a prompt. Confirm shows yes and no buttons, choice one button per option,
     * number a slider when both bounds are given, lines and pages a multi-line input.
     *
     * @param ownerName the requesting bot, shown as the title
     * @param request the prompt
     * @param listener receives the answer
     * @return the screen to open
     */
    public static Screen prompt(final String ownerName, final PromptRequest request, final Listener listener) {
        final List<DialogBody> body = List.of(message(Component.literal(request.text())));
        final Component title = Component.literal(ownerName);
        final List<ActionButton> answers = new ArrayList<>();
        final List<Input> inputs = new ArrayList<>();
        final Object def = request.defaultValue();
        final PromptKind kind = request.kind();
        switch (kind) {
            case CONFIRM -> {
                answers.add(button(CommonComponents.GUI_YES, DialogValues.YES));
                answers.add(button(CommonComponents.GUI_NO, DialogValues.NO));
            }
            case CHOICE -> {
                final List<String> choices = request.choices() == null ? List.of() : request.choices();
                for (int i = 0; i < choices.size(); i++) {
                    answers.add(button(Component.literal(choices.get(i)), DialogValues.CHOICE + i));
                }
            }
            case NUMBER -> {
                if (DialogValues.slider(request)) {
                    final float min = request.min().floatValue();
                    final float max = request.max().floatValue();
                    final float initial = def instanceof Number n ? Math.clamp(n.floatValue(), min, max) : min;
                    final boolean whole = request.min() == Math.rint(request.min()) && request.max() == Math.rint(request.max())
                            && (def == null || def instanceof Long || def instanceof Integer);
                    inputs.add(new Input(DialogValues.VALUE, new NumberRangeInput(width(), Component.translatable("screen.mineflayer_bridge.dialog.value"), "options.generic_value",
                            new NumberRangeInput.RangeInfo(min, max, Optional.of(initial), whole ? Optional.of(1f) : Optional.empty()))));
                } else {
                    final String initial = def instanceof Number n ? CommandArgs.plain(n.doubleValue()) : "";
                    inputs.add(new Input(DialogValues.VALUE, text(Component.translatable("screen.mineflayer_bridge.dialog.value"), initial, 64, false)));
                }
                answers.add(button(CommonComponents.GUI_DONE, DialogValues.SUBMIT));
            }
            case TEXT, LINES, PAGES -> {
                final int limit = request.maxLength() == null ? TEXT_LIMIT : Math.max(1, request.maxLength());
                final String initial = def instanceof List<?> l ? joinLines(l) : def == null ? "" : def.toString();
                inputs.add(new Input(DialogValues.VALUE, text(Component.translatable("screen.mineflayer_bridge.dialog.answer"), initial,
                        kind == PromptKind.TEXT ? limit : TEXT_LIMIT, kind != PromptKind.TEXT)));
                answers.add(button(CommonComponents.GUI_DONE, DialogValues.SUBMIT));
            }
        }
        return screen(title, body, inputs, answers, kind == PromptKind.CHOICE && answers.size() > 4 ? 2 : 1, listener);
    }

    private static String joinLines(final List<?> lines) {
        final List<String> parts = new ArrayList<>(lines.size());
        for (final Object o : lines) {
            parts.add(String.valueOf(o));
        }
        return String.join("\n", parts);
    }

    private static TextInput text(final Component label, final String initial, final int maxLength, final boolean multiline) {
        final int limit = Math.max(maxLength, initial.length());
        return new TextInput(width(), label, true, initial, limit, multiline
                ? Optional.of(new TextInput.MultilineOptions(Optional.empty(), Optional.of(100)))
                : Optional.empty());
    }

    /**
     * Builds the dialog for a form: one input per field (positions are typed as {@code x y z}, multi-selects
     * get one checkbox per option), a submit button and a back button that cancels the form.
     *
     * @param title the form title
     * @param subtitle who asks, shown above the inputs
     * @param submitLabel the submit button label
     * @param inputs the planned inputs, from {@link DialogValues#formInputs}
     * @param error the last validation problem to show, or null
     * @param listener receives the values
     * @return the screen to open
     */
    public static Screen form(final Component title, final Component subtitle, final Component submitLabel,
            final List<DialogValues.Input> inputs, final @Nullable String error, final Listener listener) {
        final List<DialogBody> body = new ArrayList<>(2);
        body.add(message(subtitle));
        if (error != null) {
            body.add(message(Component.literal(error).withColor(ERROR_RGB)));
        }
        final List<Input> controls = new ArrayList<>(inputs.size());
        for (final DialogValues.Input in : inputs) {
            controls.add(new Input(in.key(), control(in)));
        }
        return screen(title, body, controls, List.of(button(submitLabel, DialogValues.SUBMIT)), 1, listener);
    }

    private static InputControl control(final DialogValues.Input in) {
        final FieldSpec f = in.field();
        final Component label = Component.literal(f.required() ? f.label() + " *" : f.label());
        return switch (in.control()) {
            case TEXT, MULTILINE -> text(label, DialogValues.initialText(f), 256, in.control() == DialogValues.Control.MULTILINE);
            case TOGGLE -> {
                final String option = in.option();
                if (option == null) {
                    yield new BooleanInput(label, Boolean.TRUE.equals(FormValues.initial(f)), "true", "false");
                }
                final Object initial = FormValues.initial(f);
                yield new BooleanInput(Component.literal(f.label() + ": " + option),
                        initial instanceof List<?> l && l.contains(option), "true", "false");
            }
            case SLIDER -> {
                final float min = f.min() == null ? 0f : f.min().floatValue();
                final float max = f.max() == null ? min : f.max().floatValue();
                final float initial = FormValues.initial(f) instanceof Number n ? n.floatValue() : min;
                final Double step = f.step();
                yield new NumberRangeInput(width(), label, "options.generic_value", new NumberRangeInput.RangeInfo(min, max,
                        Optional.of(Math.clamp(initial, Math.min(min, max), Math.max(min, max))),
                        step == null || step <= 0 ? Optional.empty() : Optional.of(step.floatValue())));
            }
            case OPTIONS -> {
                final String initial = String.valueOf(FormValues.initial(f));
                final List<SingleOptionInput.Entry> entries = new ArrayList<>(f.options().size());
                for (final String o : f.options()) {
                    entries.add(new SingleOptionInput.Entry(o, Optional.empty(), o.equals(initial)));
                }
                yield new SingleOptionInput(width(), entries, label, true);
            }
        };
    }
}
