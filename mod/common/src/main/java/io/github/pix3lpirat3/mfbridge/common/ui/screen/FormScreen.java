package io.github.pix3lpirat3.mfbridge.common.ui.screen;

import io.github.pix3lpirat3.mfbridge.common.ui.command.BridgeCommands;
import io.github.pix3lpirat3.mfbridge.common.ui.logic.ButtonRows;
import io.github.pix3lpirat3.mfbridge.common.ui.logic.CommandArgs;
import io.github.pix3lpirat3.mfbridge.common.ui.logic.FieldEditors;
import io.github.pix3lpirat3.mfbridge.common.ui.logic.FieldSpec;
import io.github.pix3lpirat3.mfbridge.common.ui.logic.FormValues;
import io.github.pix3lpirat3.mfbridge.common.ui.logic.PanelLayout;
import io.github.pix3lpirat3.mfbridge.common.ui.logic.TextWrap;
import io.github.pix3lpirat3.mfbridge.common.version.Game;
import io.github.pix3lpirat3.mfbridge.protocol.core.Suggestion;
import io.github.pix3lpirat3.mfbridge.protocol.core.Vec3i;
import io.github.pix3lpirat3.mfbridge.common.version.Keys;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * A form built from vanilla widgets, used for bot forms and for command
 * arguments. Raw input is kept per field in the representation {@link FormValues} describes, so the
 * screen can be rebuilt (scrolling, resizing, returning from an in-world pick) without losing it.
 * Submit runs the caller's validation; Esc cancels.
 */
public final class FormScreen extends ChildScreen {

    private static final int MIN_PANEL = 200;
    private static final int MAX_PANEL = 340;
    private static final int ROW = 24;
    private static final int TITLE_LINES = 3;
    private static final int LABEL_LINES = 2;
    private static final int BUTTON_MIN = 100;
    private static final int BUTTON_GAP = 4;
    private static final int LABEL_ARGB = 0xFFE0E0E0;

    /** Validates and sends the input. */
    @FunctionalInterface
    public interface Submit {
        /**
         * Handles the submit button.
         *
         * @param raw field id to raw input; the map and its lists are owned by the screen
         * @return a problem to show, or null to close the screen
         */
        @Nullable String submit(Map<String, Object> raw);
    }

    /** Starts an in-world block pick for a {@code block_pos} field. */
    @FunctionalInterface
    public interface BlockPicker {
        /**
         * Closes the form, runs a local block pick and reopens the form with the result.
         *
         * @param form this screen, to reopen
         * @param fieldId the field to fill
         */
        void pick(FormScreen form, String fieldId);
    }

    /** Suggests values for a text field, through the command engine for command argument forms. */
    @FunctionalInterface
    public interface Suggester {
        /**
         * Suggests values while the player types.
         *
         * @param fieldId the field
         * @param text the field's current text
         * @return completes on the client thread with the suggestions; never fails
         */
        CompletableFuture<List<Suggestion>> suggest(String fieldId, String text);
    }

    private static final int SUGGESTION_ROWS = 8;
    private static final int SUGGESTION_ROW = 12;
    private static final int DROPDOWN_ARGB = 0xF0101010;
    private static final int DROPDOWN_HIGHLIGHT_ARGB = 0xFF303060;
    private static final int SUGGESTION_ARGB = 0xFFFFFF55;

    private final List<FieldSpec> fields;
    private final Map<String, EditBox> boxes = new LinkedHashMap<>();
    private @Nullable Suggester suggester;
    private @Nullable String suggestField;
    private List<Suggestion> suggestions = List.of();
    private int highlighted;
    private int suggestionScroll;
    private int requestGeneration;
    private final Component submitLabel;
    private final Submit submit;
    private final Runnable cancel;
    private List<ExtraButton> extras = List.of();
    private java.util.function.Consumer<String> press = id -> {
    };
    /** Where each extra button's icon goes, filled in {@link #init()}. */
    private final List<int[]> extraIcons = new ArrayList<>();

    /**
     * A button besides submit and cancel, such as "Remove container"; pressing it closes the form.
     *
     * @param id what the bot gets back
     * @param label the button text
     * @param icon an item to draw on it, or a sprite, or null
     * @param danger whether it removes or resets something: red text
     */
    public record ExtraButton(String id, String label, @Nullable Icon icon, boolean danger) {
    }

    /** An icon drawn on an extra button: an item or a GUI sprite. */
    public sealed interface Icon {
        /** An item icon. @param stack the item */
        record Item(net.minecraft.world.item.ItemStack stack) implements Icon {
        }

        /** A GUI sprite. @param sprite its id in the GUI atlas */
        record Sprite(net.minecraft.resources.Identifier sprite) implements Icon {
        }
    }
    private final @Nullable BlockPicker picker;
    private final @Nullable Supplier<@Nullable Vec3i> here;
    private final Map<String, Object> raw = new LinkedHashMap<>();
    private final List<Label> labels = new ArrayList<>();
    private final @Nullable Component subtitle;
    private int scroll;
    private int contentHeight;
    private boolean finished;
    private String error = "";
    private @Nullable String errorField;
    /** Layout of this pass, set by {@link #init()}. */
    private int panelLeft;
    private int columnWidth;
    private int labelWidth;
    private int widgetWidth;
    private int top;
    private int bottom;
    private TextWrap.Wrapped titleLines = new TextWrap.Wrapped(List.of(), false);
    private String subtitleLine = "";
    private boolean dragging;

    /** A field label, one or two lines, with the full text for a tooltip when it was cut. */
    private record Label(List<String> lines, int x, int y, int w, String fieldId, @Nullable String full) {
    }

    /**
     * Creates a form.
     *
     * @param title the form title
     * @param subtitle a line under the title, for example the requesting bot, or null
     * @param fields the fields in order
     * @param submitLabel the submit button text
     * @param submit validates and sends the input
     * @param cancel called once when the player dismisses the form with Esc or Cancel
     * @param picker starts in-world picks for {@code block_pos} fields, or null for no pick button
     * @param here the crosshair block for the Here button of position fields, or null for no button
     */
    public FormScreen(final Component title, final @Nullable Component subtitle, final List<FieldSpec> fields, final Component submitLabel,
            final Submit submit, final Runnable cancel, final @Nullable BlockPicker picker, final @Nullable Supplier<@Nullable Vec3i> here) {
        super(title, null);
        this.subtitle = subtitle;
        this.fields = List.copyOf(fields);
        this.submitLabel = submitLabel;
        this.submit = submit;
        this.cancel = cancel;
        this.picker = picker;
        this.here = here;
        for (final FieldSpec field : this.fields) {
            final Object initial = FormValues.initial(field);
            raw.put(field.id(), initial instanceof List<?> list ? new ArrayList<Object>(list) : initial);
        }
    }

    /**
     * Adds buttons besides submit and cancel, in a row of their own.
     *
     * @param buttons the buttons, in order
     * @param onPress called with the pressed button's id, after the form closed
     */
    public void extraButtons(final List<ExtraButton> buttons, final java.util.function.Consumer<String> onPress) {
        this.extras = List.copyOf(buttons);
        this.press = onPress;
    }

    /**
     * Fills a position field, for example with the result of an in-world pick.
     *
     * @param fieldId the field
     * @param pos the block position
     */
    public void setCoordinates(final String fieldId, final Vec3i pos) {
        final List<Object> coords = new ArrayList<>(3);
        coords.add(Integer.toString(pos.x()));
        coords.add(Integer.toString(pos.y()));
        coords.add(Integer.toString(pos.z()));
        raw.put(fieldId, coords);
    }

    /**
     * Shows a suggestion dropdown under text fields while the player types;
     * the argument form completes through the same engine as {@code /bridge}. Tab or a click
     * inserts the highlighted suggestion, the arrow keys move the highlight and Esc hides the list.
     *
     * @param suggester answers per field, or null for no dropdown
     */
    public void setSuggester(final @Nullable Suggester suggester) {
        this.suggester = suggester;
    }

    private void requestSuggestions(final String fieldId, final String text) {
        final Suggester s = suggester;
        if (s == null) {
            return;
        }
        final int generation = ++requestGeneration;
        s.suggest(fieldId, text).thenAccept(list -> {
            if (generation != requestGeneration) {
                return;
            }
            suggestField = fieldId;
            suggestions = List.copyOf(list);
            highlighted = 0;
            suggestionScroll = 0;
        });
    }

    private void hideSuggestions() {
        requestGeneration++;
        suggestField = null;
        suggestions = List.of();
    }

    private @Nullable EditBox dropdownBox() {
        final String id = suggestField;
        final EditBox box = id == null ? null : boxes.get(id);
        return box != null && box.isFocused() && !suggestions.isEmpty() ? box : null;
    }

    private void accept(final int index) {
        final EditBox box = dropdownBox();
        final String id = suggestField;
        if (box == null || id == null || index < 0 || index >= suggestions.size()) {
            return;
        }
        final String text = suggestions.get(index).text();
        box.setValue(text);
        box.moveCursorToEnd(false);
        raw.put(id, text);
        hideSuggestions();
    }

    /** Marks the form as handled elsewhere so that closing it does not cancel the request. */
    public void dismiss() {
        finished = true;
    }

    /**
     * Returns whether the form was submitted, cancelled or dismissed.
     *
     * @return whether the form is done
     */
    public boolean finished() {
        return finished;
    }

    private static int rows(final FieldSpec field) {
        return field.kind() == FieldSpec.Kind.MULTI_SELECT ? Math.max(1, field.options().size()) : 1;
    }

    private PanelLayout.Scroll scrolling() {
        return new PanelLayout.Scroll(contentHeight, bottom - top);
    }

    @Override
    protected void init() {
        labels.clear();
        boxes.clear();
        extraIcons.clear();
        final ToIntFunction<String> measure = UiText.width(font);
        // The panel follows the window; the scrollbar sits in its right edge.
        final int panel = PanelLayout.width(width, MIN_PANEL, MAX_PANEL, 0.9);
        panelLeft = width / 2 - panel / 2;
        columnWidth = panel - PanelLayout.SCROLLBAR_WIDTH - 4;
        labelWidth = columnWidth * 3 / 8;
        widgetWidth = columnWidth - labelWidth;

        final int textWidth = width - 2 * PanelLayout.MARGIN;
        titleLines = TextWrap.clamp(title.getString(), textWidth, TITLE_LINES, measure);
        final Component sub = subtitle;
        subtitleLine = sub == null ? "" : TextWrap.ellipsize(sub.getString(), textWidth, measure);
        top = 8 + Math.max(1, titleLines.size()) * 10 + (sub == null ? 4 : 14);

        // Extra buttons get rows of their own, apart from Submit and Cancel, so a remove is never
        // pressed by mistake. Rows that would take over a third of the window scroll with the fields.
        final int buttonY = height - 26;
        List<ButtonRows.Placed> placed = ButtonRows.layout(extraWidths(), textWidth, BUTTON_GAP, BUTTON_MIN);
        int extraRows = ButtonRows.rows(placed);
        final boolean extrasInFooter = extraRows * ROW <= Math.max(ROW, height / 3);
        if (!extrasInFooter) {
            placed = ButtonRows.layout(extraWidths(), columnWidth, BUTTON_GAP, BUTTON_MIN);
            extraRows = ButtonRows.rows(placed);
        }
        final int footerTop = buttonY - (extrasInFooter ? extraRows * ROW : 0);
        bottom = Math.max(top + ROW, footerTop - 16);

        contentHeight = 0;
        for (final FieldSpec field : fields) {
            contentHeight += rows(field) * ROW;
        }
        final int extrasGap = fields.isEmpty() ? 0 : ROW / 2;
        if (!extrasInFooter) {
            contentHeight += extraRows * ROW + extrasGap;
        }
        scroll = scrolling().clamp(scroll);

        final int widgetX = panelLeft + labelWidth;
        int y = top - scroll;
        for (final FieldSpec field : fields) {
            if (visible(y)) {
                addLabel(field, panelLeft, y, measure);
                addField(field, widgetX, y);
            } else if (field.kind() == FieldSpec.Kind.MULTI_SELECT) {
                addField(field, widgetX, y);
            }
            y += rows(field) * ROW;
        }
        if (extrasInFooter) {
            addExtras(placed, PanelLayout.MARGIN, footerTop, false);
        } else {
            addExtras(placed, panelLeft, y + extrasGap, true);
        }
        addRenderableWidget(Button.builder(submitLabel, b -> trySubmit()).bounds(width / 2 - 102, buttonY, 100, 20).build());
        addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, b -> onClose()).bounds(width / 2 + 2, buttonY, 100, 20).build());
    }

    private int[] extraWidths() {
        final int[] widths = new int[extras.size()];
        for (int i = 0; i < extras.size(); i++) {
            final ExtraButton e = extras.get(i);
            widths[i] = font.width(e.label()) + (e.icon() == null ? 16 : 44);
        }
        return widths;
    }

    private void addExtras(final List<ButtonRows.Placed> placed, final int left, final int y, final boolean scrolled) {
        for (final ButtonRows.Placed p : placed) {
            final ExtraButton e = extras.get(p.index());
            final int bx = left + p.x();
            final int by = y + p.row() * ROW;
            if (scrolled && !visible(by)) {
                continue;
            }
            final String shown = TextWrap.ellipsize(e.label(), p.width() - (e.icon() == null ? 8 : 36), UiText.width(font));
            final Component text = e.danger() ? Component.literal(shown).withStyle(net.minecraft.ChatFormatting.RED) : Component.literal(shown);
            final Button button = Button.builder(text, b -> pressExtra(e.id())).bounds(bx, by, p.width(), 20).build();
            if (!shown.equals(e.label())) {
                button.setTooltip(UiText.tooltip(e.label(), null));
            }
            addRenderableWidget(button);
            if (e.icon() != null) {
                extraIcons.add(new int[] {p.index(), bx + 4, by + 2});
            }
        }
    }

    private void addLabel(final FieldSpec field, final int x, final int y, final ToIntFunction<String> measure) {
        final String full = field.label() + (field.required() ? " *" : "");
        final TextWrap.Wrapped lines = TextWrap.clamp(full, labelWidth - 6, LABEL_LINES, measure);
        final int textY = lines.size() > 1 ? y + 1 : y + 6;
        labels.add(new Label(lines.lines(), x, textY, labelWidth - 4, field.id(), lines.clamped() ? full : null));
    }

    private boolean visible(final int y) {
        return y >= top - 2 && y + 20 <= bottom;
    }

    @SuppressWarnings("unchecked")
    private void addField(final FieldSpec field, final int x, final int y) {
        final String id = field.id();
        final Component label = Component.literal(field.label());
        switch (field.kind()) {
            case TEXT, ITEM, BLOCK, ZONE, NUMBER, INTEGER -> {
                final EditBox box = new EditBox(font, x, y, widgetWidth, 20, label);
                box.setMaxLength(1024);
                box.setValue(String.valueOf(raw.get(id)));
                box.moveCursorToStart(false);
                box.setResponder(v -> {
                    raw.put(id, v);
                    requestSuggestions(id, v);
                });
                boxes.put(id, box);
                addRenderableWidget(box);
            }
            case SLIDER -> addRenderableWidget(new FieldSlider(x, y, widgetWidth, field, raw));
            case TOGGLE -> addRenderableWidget(CycleButton.onOffBuilder(Boolean.TRUE.equals(raw.get(id)))
                    .displayOnlyValue()
                    .create(x, y, widgetWidth, 20, label, (b, v) -> raw.put(id, v)));
            case SELECT -> {
                final List<String> options = field.options().isEmpty() ? List.of(String.valueOf(raw.get(id))) : field.options();
                final String current = options.contains(String.valueOf(raw.get(id))) ? String.valueOf(raw.get(id)) : options.getFirst();
                raw.put(id, current);
                // Long options are cut on the button and shown whole in its tooltip.
                final int room = widgetWidth - 8;
                final ToIntFunction<String> measure = UiText.width(font);
                addRenderableWidget(CycleButton.builder((String s) -> Component.literal(TextWrap.ellipsize(s, room, measure)), current)
                        .withValues(options)
                        .withTooltip(s -> TextWrap.overflows(s, room, measure) ? UiText.tooltip(s, null) : null)
                        .displayOnlyValue()
                        .create(x, y, widgetWidth, 20, label, (b, v) -> raw.put(id, v)));
            }
            case MULTI_SELECT -> {
                final List<Object> picked = (List<Object>) raw.get(id);
                int rowY = y;
                for (final String option : field.options()) {
                    if (visible(rowY)) {
                        final boolean cut = font.width(option + ": " + CommonComponents.OPTION_OFF.getString()) > widgetWidth - 8;
                        addRenderableWidget(CycleButton.onOffBuilder(picked.contains(option))
                                .withTooltip(v -> cut ? UiText.tooltip(option, null) : null)
                                .create(x, rowY, widgetWidth, 20, Component.literal(option), (b, v) -> {
                                    picked.remove(option);
                                    if (v) {
                                        picked.add(option);
                                    }
                                }));
                    }
                    rowY += ROW;
                }
            }
            case BLOCK_POS, VEC3 -> addCoordinates(field, x, y);
        }
    }

    @SuppressWarnings("unchecked")
    private void addCoordinates(final FieldSpec field, final int x, final int y) {
        final String id = field.id();
        final List<Object> coords = (List<Object>) raw.get(id);
        final boolean pickButton = field.kind() == FieldSpec.Kind.BLOCK_POS && picker != null;
        final boolean hereButton = here != null;
        final int buttons = (pickButton ? 1 : 0) + (hereButton ? 1 : 0);
        final int buttonWidth = 40;
        final int boxWidth = (widgetWidth - buttons * (buttonWidth + 2) - 4) / 3;
        final String[] axes = {"x", "y", "z"};
        for (int i = 0; i < 3; i++) {
            final int index = i;
            final EditBox box = new EditBox(font, x + i * (boxWidth + 2), y, boxWidth, 20, Component.literal(field.label() + " " + axes[i]));
            box.setMaxLength(32);
            box.setValue(String.valueOf(coords.get(i)));
            box.moveCursorToStart(false);
            box.setResponder(v -> coords.set(index, v));
            addRenderableWidget(box);
        }
        int bx = x + 3 * (boxWidth + 2);
        final BlockPicker p = picker;
        if (pickButton && p != null) {
            addRenderableWidget(Button.builder(Component.translatable("screen.mineflayer_bridge.form.pick"), b -> p.pick(this, id)).bounds(bx, y, buttonWidth, 20).build());
            bx += buttonWidth + 2;
        }
        final Supplier<@Nullable Vec3i> h = here;
        if (hereButton && h != null) {
            addRenderableWidget(Button.builder(Component.translatable("screen.mineflayer_bridge.form.here"), b -> {
                final Vec3i pos = h.get();
                if (pos == null) {
                    error = I18n.get("screen.mineflayer_bridge.form.here.none");
                    errorField = id;
                    return;
                }
                setCoordinates(id, pos);
                rebuildWidgets();
            }).bounds(bx, y, buttonWidth, 20).build());
        }
    }

    private void pressExtra(final String id) {
        finished = true;
        Game.setScreen(null);
        press.accept(id);
    }

    private void trySubmit() {
        final String problem = submit.submit(raw);
        if (problem != null) {
            error = problem;
            errorField = null;
            for (final FieldSpec field : fields) {
                if (problem.contains("'" + field.label() + "'") || problem.contains("'" + field.id() + "'")) {
                    errorField = field.id();
                    break;
                }
            }
            return;
        }
        finished = true;
        Game.setScreen(null);
    }

    @Override
    public boolean keyPressed(final KeyEvent event) {
        if (dropdownBox() != null) {
            if (event.isCycleFocus()) {
                accept(highlighted);
                return true;
            }
            if (event.isUp() || event.isDown()) {
                highlighted = Math.floorMod(highlighted + (event.isUp() ? -1 : 1), suggestions.size());
                suggestionScroll = Math.clamp(suggestionScroll, highlighted - SUGGESTION_ROWS + 1, highlighted);
                return true;
            }
            if (event.isEscape()) {
                hideSuggestions();
                return true;
            }
        } else if (event.isCycleFocus() && getFocused() instanceof EditBox box && suggester != null) {
            for (final Map.Entry<String, EditBox> e : boxes.entrySet()) {
                if (e.getValue() == box && suggestField == null) {
                    requestSuggestions(e.getKey(), box.getValue());
                    return true;
                }
            }
        }
        if (event.isConfirmation() && (getFocused() == null || getFocused() instanceof EditBox)) {
            trySubmit();
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean mouseScrolled(final double x, final double y, final double scrollX, final double scrollY) {
        for (final FieldSpec field : fields) {
            final EditBox box = boxes.get(field.id());
            final boolean number = field.kind() == FieldSpec.Kind.NUMBER || field.kind() == FieldSpec.Kind.INTEGER;
            if (number && box != null && box.isMouseOver(x, y) && scrollY != 0) {
                // Number fields also take scroll steps.
                box.setValue(FieldEditors.scrolled(box.getValue(), (int) Math.signum(scrollY), field.kind() == FieldSpec.Kind.INTEGER));
                return true;
            }
        }
        final PanelLayout.Scroll s = scrolling();
        if (!s.scrolls()) {
            return super.mouseScrolled(x, y, scrollX, scrollY);
        }
        scrollTo(s.clamp(scroll - (int) Math.signum(scrollY) * ROW));
        return true;
    }

    private void scrollTo(final int offset) {
        if (offset != scroll) {
            scroll = offset;
            rebuildWidgets();
        }
    }

    private int scrollbarX() {
        return panelLeft + columnWidth + 4;
    }

    private boolean onScrollbar(final double x, final double y) {
        return scrolling().scrolls() && UiText.inside(x, y, scrollbarX() - 1, top, PanelLayout.SCROLLBAR_WIDTH + 2, bottom - top);
    }

    @Override
    public boolean mouseDragged(final MouseButtonEvent event, final double dx, final double dy) {
        if (dragging) {
            scrollTo(scrolling().offsetAt(event.y() - top));
            return true;
        }
        return super.mouseDragged(event, dx, dy);
    }

    @Override
    public boolean mouseReleased(final MouseButtonEvent event) {
        if (dragging) {
            dragging = false;
            return true;
        }
        return super.mouseReleased(event);
    }

    /** Cancels the form as the player's Esc does, without touching the open screen. Does nothing once it is done. */
    public void cancel() {
        if (!finished) {
            finished = true;
            cancel.run();
        }
    }

    @Override
    public void onClose() {
        cancel();
        Game.setScreen(null);
    }

    @Override
    public void extractRenderState(final GuiGraphicsExtractor graphics, final int mouseX, final int mouseY, final float a) {
        super.extractRenderState(graphics, mouseX, mouseY, a);
        int ty = 8;
        for (final String line : titleLines.lines()) {
            graphics.centeredText(font, line, width / 2, ty, UiColors.TEXT);
            ty += 10;
        }
        ty = 8 + Math.max(1, titleLines.size()) * 10;
        if (titleLines.clamped() && UiText.inside(mouseX, mouseY, PanelLayout.MARGIN, 8, width - 2 * PanelLayout.MARGIN, ty - 8)) {
            UiText.showTooltip(graphics, font, title.getString(), mouseX, mouseY);
        }
        for (final int[] at : extraIcons) {
            switch (extras.get(at[0]).icon()) {
                case Icon.Item item -> graphics.fakeItem(item.stack(), at[1], at[2]);
                case Icon.Sprite sprite -> Game.blitSprite(graphics, sprite.sprite(), at[1], at[2], 16, 16);
                case null -> {
                }
            }
        }
        final Component sub = subtitle;
        if (sub != null) {
            graphics.centeredText(font, subtitleLine, width / 2, ty + 2, UiColors.LABEL);
            if (!subtitleLine.equals(sub.getString()) && UiText.inside(mouseX, mouseY, PanelLayout.MARGIN, ty + 2, width - 2 * PanelLayout.MARGIN, 9)) {
                UiText.showTooltip(graphics, font, sub.getString(), mouseX, mouseY);
            }
        }
        for (final Label label : labels) {
            int ly = label.y();
            for (final String line : label.lines()) {
                graphics.text(font, line, label.x(), ly, label.fieldId().equals(errorField) ? UiColors.ERROR : LABEL_ARGB);
                ly += 9;
            }
            final String full = label.full();
            if (full != null && UiText.inside(mouseX, mouseY, label.x(), label.y(), label.w(), ly - label.y())) {
                UiText.showTooltip(graphics, font, full, mouseX, mouseY);
            }
        }
        UiText.scrollbar(graphics, scrollbarX(), top, scrolling(), scroll);
        if (!error.isEmpty()) {
            final int room = width - 2 * PanelLayout.MARGIN;
            final String shown = TextWrap.ellipsize(error, room, UiText.width(font));
            final int ey = bottom + 4;
            graphics.centeredText(font, shown, width / 2, ey, UiColors.ERROR);
            if (!shown.equals(error) && UiText.inside(mouseX, mouseY, PanelLayout.MARGIN, ey, room, 9)) {
                UiText.showTooltip(graphics, font, error, mouseX, mouseY);
            }
        }
        renderSuggestions(graphics, mouseX, mouseY);
    }

    private void renderSuggestions(final GuiGraphicsExtractor graphics, final int mouseX, final int mouseY) {
        final EditBox box = dropdownBox();
        if (box == null) {
            return;
        }
        final int x = box.getX();
        final int y = box.getY() + box.getHeight();
        final int rows = Math.min(SUGGESTION_ROWS, suggestions.size());
        graphics.fill(x, y, x + box.getWidth(), y + rows * SUGGESTION_ROW + 2, DROPDOWN_ARGB);
        for (int i = 0; i < rows; i++) {
            final int index = suggestionScroll + i;
            if (index >= suggestions.size()) {
                break;
            }
            final Suggestion s = suggestions.get(index);
            final int rowY = y + 1 + i * SUGGESTION_ROW;
            if (index == highlighted) {
                graphics.fill(x, rowY, x + box.getWidth(), rowY + SUGGESTION_ROW, DROPDOWN_HIGHLIGHT_ARGB);
            }
            final String display = s.display();
            final String label = display == null ? s.text() : display + "  " + s.text();
            graphics.text(font, font.plainSubstrByWidth(label, box.getWidth() - 4), x + 2, rowY + 2, index == highlighted ? SUGGESTION_ARGB : BridgeCommands.kindArgb(s, LABEL_ARGB));
            final String tooltip = s.tooltip();
            if (tooltip != null && mouseX >= x && mouseX < x + box.getWidth() && mouseY >= rowY && mouseY < rowY + SUGGESTION_ROW) {
                graphics.setTooltipForNextFrame(Component.literal(tooltip), mouseX, mouseY);
            }
        }
    }

    @Override
    public boolean mouseClicked(final MouseButtonEvent event, final boolean doubleClick) {
        if (event.button() == Keys.MOUSE_BUTTON_LEFT && onScrollbar(event.x(), event.y())) {
            dragging = true;
            scrollTo(scrolling().offsetAt(event.y() - top));
            return true;
        }
        final EditBox box = dropdownBox();
        if (box != null) {
            final int x = box.getX();
            final int y = box.getY() + box.getHeight() + 1;
            final int rows = Math.min(SUGGESTION_ROWS, suggestions.size());
            if (event.x() >= x && event.x() < x + box.getWidth() && event.y() >= y && event.y() < y + rows * SUGGESTION_ROW) {
                accept(suggestionScroll + (int) ((event.y() - y) / SUGGESTION_ROW));
                return true;
            }
            hideSuggestions();
        }
        return super.mouseClicked(event, doubleClick);
    }

    /** A slider over a field's range, snapped to its step. */
    private static final class FieldSlider extends AbstractSliderButton {
        private final FieldSpec field;
        private final Map<String, Object> raw;
        private final double min;
        private final double max;

        FieldSlider(final int x, final int y, final int width, final FieldSpec field, final Map<String, Object> raw) {
            super(x, y, width, 20, Component.literal(field.label()), 0d);
            this.field = field;
            this.raw = raw;
            this.min = field.min() == null ? 0d : field.min();
            this.max = field.max() == null || field.max() <= min ? min + 1d : field.max();
            final double current = raw.get(field.id()) instanceof Number n ? n.doubleValue() : min;
            this.value = (Math.clamp(current, min, max) - min) / (max - min);
            updateMessage();
        }

        private double current() {
            double v = min + value * (max - min);
            final Double step = field.step();
            if (step != null && step > 0d) {
                v = min + Math.round((v - min) / step) * step;
            }
            return Math.clamp(v, min, max);
        }

        @Override
        protected void updateMessage() {
            setMessage(Component.literal(field.label() + ": " + CommandArgs.plain(current())));
        }

        @Override
        protected void applyValue() {
            raw.put(field.id(), current());
        }
    }
}
