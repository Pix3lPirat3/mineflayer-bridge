package io.github.pix3lpirat3.mfbridge.common.ui.devices;

import io.github.pix3lpirat3.mfbridge.common.ui.screen.RowScreen;
import io.github.pix3lpirat3.mfbridge.common.ui.screen.UiColors;
import io.github.pix3lpirat3.mfbridge.core.remote.PermissionBatch;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.network.chat.Component;

/**
 * One screen for every permission a remote device asked for within
 * the collection window, a checkbox each (all checked), with Allow selected and Deny all. It
 * cannot be closed with Esc, so an answer is never given by accident. Client thread only.
 */
final class PermissionBatchScreen extends RowScreen {
    private final PermissionBatch batch;
    private final Consumer<List<PermissionBatch.Item>> answer;
    private final Set<PermissionBatch.Item> checked;

    /**
     * Creates the screen.
     *
     * @param batch the batch to ask
     * @param answer receives the items the player allowed, empty for Deny all
     */
    PermissionBatchScreen(final PermissionBatch batch, final Consumer<List<PermissionBatch.Item>> answer) {
        super(Component.translatable("screen.mineflayer_bridge.permission_batch.title", batch.deviceName()), null);
        this.batch = batch;
        this.answer = answer;
        this.checked = new HashSet<>(batch.items());
    }

    @Override
    protected int rows(final int left, final int top) {
        int y = top;
        if (visible(y)) {
            label(Component.translatable("screen.mineflayer_bridge.permission_batch.intro"), left, y + 6, UiColors.TEXT);
        }
        y += ROW_STEP;
        for (final PermissionBatch.Item item : batch.items()) {
            if (visible(y)) {
                addRenderableWidget(Checkbox.builder(Component.translatable("screen.mineflayer_bridge.permission_batch.item",
                                PermissionText.item(item), item.key(), item.requestedBy()), font)
                        .pos(left, y)
                        .maxWidth(columnWidth)
                        .selected(checked.contains(item))
                        .onValueChange((box, v) -> {
                            if (v) {
                                checked.add(item);
                            } else {
                                checked.remove(item);
                            }
                        })
                        .build());
            }
            y += ROW_STEP;
        }
        if (visible(y)) {
            label(Component.translatable("screen.mineflayer_bridge.permission_batch.note.line1"), left, y + 6, UiColors.LABEL);
        }
        y += 12;
        if (visible(y)) {
            label(Component.translatable("screen.mineflayer_bridge.permission_batch.note.line2"), left, y + 6, UiColors.LABEL);
        }
        return y + ROW_STEP;
    }

    @Override
    protected void footer(final int y) {
        addRenderableWidget(Button.builder(Component.translatable("screen.mineflayer_bridge.permission_batch.allow"), b -> {
            final List<PermissionBatch.Item> allowed = new ArrayList<>();
            for (final PermissionBatch.Item item : batch.items()) {
                if (checked.contains(item)) {
                    allowed.add(item);
                }
            }
            answer.accept(allowed);
        }).bounds(width / 2 - 152, y, 150, ROW_HEIGHT).build());
        addRenderableWidget(Button.builder(Component.translatable("screen.mineflayer_bridge.permission_batch.deny"), b -> answer.accept(List.of()))
                .bounds(width / 2 + 2, y, 150, ROW_HEIGHT).build());
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }
}
