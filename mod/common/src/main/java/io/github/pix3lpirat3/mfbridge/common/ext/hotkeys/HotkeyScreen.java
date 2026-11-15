package io.github.pix3lpirat3.mfbridge.common.ext.hotkeys;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.pix3lpirat3.mfbridge.common.Constants;
import io.github.pix3lpirat3.mfbridge.common.ui.screen.ChildScreen;
import io.github.pix3lpirat3.mfbridge.common.ui.screen.UiColors;
import java.util.function.Consumer;
import java.util.function.Function;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/** Adds or edits one hotkey: the line it runs and the key, which the player presses to set. */
final class HotkeyScreen extends ChildScreen {

    private static final int WIDTH = 300;

    private final @Nullable Hotkey existing;
    private final Function<Hotkey, @Nullable String> save;
    private final Consumer<Hotkey> remove;
    private String line;
    private String key;
    private boolean ctrl;
    private boolean shift;
    private boolean alt;
    private boolean capturing;
    private String error = "";
    private @Nullable Button keyButton;

    /**
     * Creates the screen.
     *
     * @param parent the screen to return to
     * @param existing the hotkey to edit, or null to add one
     * @param save checks and stores the hotkey; returns a problem to show, or null when saved
     * @param remove removes the edited hotkey
     */
    HotkeyScreen(final @Nullable Screen parent, final @Nullable Hotkey existing, final Function<Hotkey, @Nullable String> save,
            final Consumer<Hotkey> remove) {
        super(Component.translatable(existing == null ? "mineflayer_bridge_hotkeys.add" : "mineflayer_bridge_hotkeys.edit"), parent);
        this.existing = existing;
        this.save = save;
        this.remove = remove;
        this.line = existing == null ? "" : existing.line();
        this.key = existing == null ? "" : existing.key();
        if (existing != null) {
            ctrl = existing.ctrl();
            shift = existing.shift();
            alt = existing.alt();
        }
    }

    private int top() {
        return height / 2 - 50;
    }

    private int third() {
        return (WIDTH - 8) / 3;
    }

    @Override
    protected void init() {
        final int left = width / 2 - WIDTH / 2;
        int y = top();
        final EditBox box = new EditBox(font, left, y, WIDTH, 20, Component.translatable("mineflayer_bridge_hotkeys.line"));
        box.setMaxLength(256);
        box.setValue(line);
        box.setHint(Component.translatable("mineflayer_bridge_hotkeys.line.hint").withStyle(ChatFormatting.DARK_GRAY));
        box.setResponder(v -> line = v);
        addRenderableWidget(box);
        y += 34;
        keyButton = addRenderableWidget(Button.builder(keyText(), b -> {
            capturing = true;
            b.setMessage(keyText());
        }).bounds(left, y, WIDTH, 20).build());
        y += 34;
        final int third = third();
        addRenderableWidget(Button.builder(Component.translatable("mineflayer_bridge_hotkeys.save"), b -> trySave())
                .bounds(left, y, third, 20).build());
        addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, b -> onClose()).bounds(left + third + 4, y, third, 20).build());
        final Hotkey edited = existing;
        if (edited != null) {
            addRenderableWidget(Button.builder(Component.translatable("mineflayer_bridge_hotkeys.remove").withStyle(ChatFormatting.RED), b -> {
                remove.accept(edited);
                onClose();
            }).bounds(left + 2 * (third + 4), y, third, 20).build());
        }
        setInitialFocus(box);
    }

    private Component keyText() {
        if (capturing) {
            return Component.translatable("mineflayer_bridge_hotkeys.press").withStyle(ChatFormatting.YELLOW);
        }
        return key.isEmpty() ? Component.translatable("mineflayer_bridge_hotkeys.key.none")
                : Component.translatable("mineflayer_bridge_hotkeys.key", Hotkey.label(key, ctrl, shift, alt));
    }

    @Override
    public boolean keyPressed(final KeyEvent event) {
        if (!capturing) {
            return super.keyPressed(event);
        }
        if (event.key() == InputConstants.KEY_ESCAPE) {
            capturing = false;
        } else if (!modifier(event.key())) {
            key = InputConstants.getKey(event).getName();
            ctrl = event.hasControlDown();
            shift = event.hasShiftDown();
            alt = event.hasAltDown();
            capturing = false;
        }
        if (keyButton != null) {
            keyButton.setMessage(keyText());
        }
        return true;
    }

    private static boolean modifier(final int code) {
        return code == InputConstants.KEY_LCONTROL || code == InputConstants.KEY_RCONTROL || code == InputConstants.KEY_LSHIFT
                || code == InputConstants.KEY_RSHIFT || code == InputConstants.KEY_LALT || code == InputConstants.KEY_RALT;
    }

    private void trySave() {
        if (key.isEmpty()) {
            error = I18n.get("mineflayer_bridge_hotkeys.need_key");
            return;
        }
        final String problem = save.apply(new Hotkey(key, ctrl, shift, alt, line.strip()));
        if (problem == null) {
            onClose();
        } else {
            error = problem;
        }
    }

    @Override
    public void extractRenderState(final GuiGraphicsExtractor graphics, final int mouseX, final int mouseY, final float a) {
        super.extractRenderState(graphics, mouseX, mouseY, a);
        final int left = width / 2 - WIDTH / 2;
        final int top = top();
        graphics.centeredText(font, title, width / 2, top - 30, 0xFFFFFFFF);
        graphics.text(font, Component.translatable("mineflayer_bridge_hotkeys.line"), left, top - 11, UiColors.LABEL);
        graphics.text(font, Component.translatable("mineflayer_bridge_hotkeys.key.label"), left, top + 23, UiColors.LABEL);
        if (existing != null) {
            graphics.blitSprite(RenderPipelines.GUI_TEXTURED, Constants.id("icon/trash"), left + 2 * (third() + 4) + 3, top + 70, 16, 16);
        }
        if (!error.isEmpty()) {
            graphics.centeredText(font, Component.literal(error).withStyle(ChatFormatting.RED), width / 2, top + 96, 0xFFFFFFFF);
        }
    }
}
