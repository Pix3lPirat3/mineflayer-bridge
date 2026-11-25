package io.github.pix3lpirat3.mfbridge.common.spatial;

import io.github.pix3lpirat3.mfbridge.common.ui.screen.ChildScreen;
import io.github.pix3lpirat3.mfbridge.common.ui.screen.UiColors;
import io.github.pix3lpirat3.mfbridge.common.version.Game;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/**
 * The text prompt that names a new zone, point or route: a name and optional comma-separated tags.
 * Enter or Done creates the object; Esc or Back cancels and creates nothing.
 */
public final class NamePromptScreen extends ChildScreen {

    /**
     * What the player entered.
     *
     * @param name the trimmed name, not empty
     * @param tags the trimmed, lower-case, non-empty tags in input order
     */
    public record Result(String name, List<String> tags) {
    }

    private static final int WIDTH = 220;
    private static final Component NAME = Component.translatable("screen.mineflayer_bridge.name_prompt.name");
    private static final Component TAGS = Component.translatable("screen.mineflayer_bridge.name_prompt.tags");
    private static final Component TAGS_HINT = Component.translatable("screen.mineflayer_bridge.name_prompt.tags.hint");

    private final Consumer<Result> done;
    private String name;
    private String tags = "";
    private Component error = CommonComponents.EMPTY;
    private boolean finished;

    /**
     * Creates the prompt.
     *
     * @param title what is being named, for example "New zone"
     * @param initial the suggested name
     * @param done receives the answer; not called when the player cancels
     */
    public NamePromptScreen(final Component title, final String initial, final Consumer<Result> done) {
        super(title, null);
        this.name = initial;
        this.done = done;
    }

    /**
     * Splits a comma-separated tag list.
     *
     * @param text what the player typed
     * @return the tags, trimmed and lower case, without empty entries or repeats
     */
    public static List<String> parseTags(final String text) {
        final List<String> out = new ArrayList<>();
        for (final String part : text.split(",")) {
            final String tag = part.strip().toLowerCase(Locale.ROOT);
            if (!tag.isEmpty() && !out.contains(tag)) {
                out.add(tag);
            }
        }
        return List.copyOf(out);
    }

    @Override
    protected void init() {
        final int left = width / 2 - WIDTH / 2;
        final int y = height / 2 - 30;
        final EditBox nameBox = new EditBox(font, left, y, WIDTH, 20, NAME);
        nameBox.setMaxLength(64);
        nameBox.setValue(name);
        nameBox.setResponder(v -> name = v);
        addRenderableWidget(nameBox);
        final EditBox tagBox = new EditBox(font, left, y + 34, WIDTH, 20, TAGS);
        tagBox.setMaxLength(256);
        tagBox.setValue(tags);
        tagBox.setResponder(v -> tags = v);
        addRenderableWidget(tagBox);
        setInitialFocus(nameBox);
        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> submit()).bounds(width / 2 - 102, y + 64, 100, 20).build());
        addRenderableWidget(Button.builder(CommonComponents.GUI_BACK, b -> onClose()).bounds(width / 2 + 2, y + 64, 100, 20).build());
    }

    @Override
    public boolean keyPressed(final KeyEvent event) {
        if (event.isConfirmation()) {
            submit();
            return true;
        }
        return super.keyPressed(event);
    }

    private void submit() {
        final String trimmed = name.strip();
        if (trimmed.isEmpty()) {
            error = Component.translatable("screen.mineflayer_bridge.name_prompt.empty");
            return;
        }
        if (finished) {
            return;
        }
        finished = true;
        Game.setScreen(null);
        done.accept(new Result(trimmed, parseTags(tags)));
    }

    @Override
    public void extractRenderState(final GuiGraphicsExtractor graphics, final int mouseX, final int mouseY, final float a) {
        super.extractRenderState(graphics, mouseX, mouseY, a);
        final int y = height / 2 - 30;
        graphics.centeredText(font, title, width / 2, y - 24, UiColors.TEXT);
        graphics.text(font, NAME, width / 2 - WIDTH / 2, y - 11, UiColors.LABEL);
        graphics.text(font, TAGS_HINT, width / 2 - WIDTH / 2, y + 23, UiColors.LABEL);
        graphics.centeredText(font, error, width / 2, y + 92, UiColors.ERROR);
    }
}
