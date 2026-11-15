package io.github.pix3lpirat3.mfbridge.common.ui.devices;

import io.github.pix3lpirat3.mfbridge.common.ui.screen.RowScreen;
import io.github.pix3lpirat3.mfbridge.common.ui.screen.UiColors;
import java.util.function.Consumer;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/** Asks for a device label; an empty label shows the name the device reports again. */
final class RenameScreen extends RowScreen {
    private final Consumer<String> onDone;
    private String value;

    RenameScreen(final Screen parent, final String current, final Consumer<String> onDone) {
        super(Component.translatable("screen.mineflayer_bridge.rename.title"), parent);
        this.onDone = onDone;
        this.value = current;
    }

    @Override
    protected int rows(final int left, final int top) {
        label(Component.translatable("screen.mineflayer_bridge.rename.hint"), left, top + 6, UiColors.LABEL);
        final EditBox box = new EditBox(font, left, top + ROW_STEP, columnWidth, ROW_HEIGHT,
                Component.translatable("screen.mineflayer_bridge.rename.name"));
        box.setMaxLength(64);
        box.setValue(value);
        box.setResponder(v -> value = v);
        addRenderableWidget(box);
        setInitialFocus(box);
        return top + 2 * ROW_STEP;
    }

    @Override
    protected void footer(final int y) {
        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> {
            onDone.accept(value);
            onClose();
        }).bounds(width / 2 - 102, y, 100, ROW_HEIGHT).build());
        addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, b -> onClose())
                .bounds(width / 2 + 2, y, 100, ROW_HEIGHT).build());
    }
}
