package io.github.pix3lpirat3.mfbridge.common.ext;

import io.github.pix3lpirat3.mfbridge.api.Setting;
import io.github.pix3lpirat3.mfbridge.api.SettingValue;
import io.github.pix3lpirat3.mfbridge.common.ui.screen.ChildScreen;
import io.github.pix3lpirat3.mfbridge.common.ui.screen.UiColors;
import io.github.pix3lpirat3.mfbridge.core.ext.ExtensionSettingsFile;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/** Edits one number or text setting of a mod extension. Enter or Done saves; Esc or Back keeps the value. */
final class SettingValueScreen extends ChildScreen {

    private static final int WIDTH = 220;

    private final SettingValue<?> value;
    private String text;
    private Component error = CommonComponents.EMPTY;

    SettingValueScreen(final @Nullable Screen parent, final SettingValue<?> value) {
        super(Component.literal(value.setting().description().isEmpty() ? value.setting().key() : value.setting().description()), parent);
        this.value = value;
        this.text = String.valueOf(value.get());
    }

    @Override
    protected void init() {
        final int y = height / 2 - 20;
        final EditBox box = new EditBox(font, width / 2 - WIDTH / 2, y, WIDTH, 20, title);
        box.setMaxLength(256);
        box.setValue(text);
        box.setResponder(v -> text = v);
        addRenderableWidget(box);
        setInitialFocus(box);
        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> submit()).bounds(width / 2 - 102, y + 30, 100, 20).build());
        addRenderableWidget(Button.builder(CommonComponents.GUI_BACK, b -> onClose()).bounds(width / 2 + 2, y + 30, 100, 20).build());
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
        if (apply(value, text)) {
            onClose();
        } else {
            error = Component.translatable("screen.mineflayer_bridge.extensions.setting.invalid");
        }
    }

    private static <T> boolean apply(final SettingValue<T> value, final String text) {
        final Setting<T> setting = value.setting();
        final T parsed = ExtensionSettingsFile.parse(setting, text);
        if (parsed == null) {
            return false;
        }
        value.set(parsed);
        return true;
    }

    @Override
    public void extractRenderState(final GuiGraphicsExtractor graphics, final int mouseX, final int mouseY, final float a) {
        super.extractRenderState(graphics, mouseX, mouseY, a);
        final int y = height / 2 - 20;
        graphics.centeredText(font, title, width / 2, y - 24, UiColors.TEXT);
        graphics.centeredText(font, Component.literal(value.setting().key()), width / 2, y - 12, UiColors.LABEL);
        graphics.centeredText(font, error, width / 2, y + 58, UiColors.ERROR);
    }
}
