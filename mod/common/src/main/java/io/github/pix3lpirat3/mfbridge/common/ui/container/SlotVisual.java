package io.github.pix3lpirat3.mfbridge.common.ui.container;

import io.github.pix3lpirat3.mfbridge.common.ui.logic.ItemCounts;
import io.github.pix3lpirat3.mfbridge.protocol.core.SlotItem;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.util.ARGB;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * How one bot slot item looks in a GUI: the stack, compact counts or
 * {@code countText}, a durability-style {@code bar} with {@code barColor}, animation {@code frames}
 * cycled every {@code frameMs}, {@code hideTooltip} and the {@code clickSound}. Built once per item;
 * drawing allocates nothing. Client thread only.
 */
public final class SlotVisual {

    private record Frame(ItemStack stack, @Nullable String count, float bar, int barArgb) {
    }

    private final SlotItem item;
    private final Frame[] frames;
    private final long frameMs;

    private SlotVisual(final SlotItem item, final Frame[] frames, final long frameMs) {
        this.item = item;
        this.frames = frames;
        this.frameMs = frameMs;
    }

    /**
     * Builds the visual of an item.
     *
     * @param item the bot's item
     * @return the visual
     */
    public static SlotVisual of(final SlotItem item) {
        final List<SlotItem> list = item.frames();
        if (list == null || list.isEmpty()) {
            return new SlotVisual(item, new Frame[] {frame(item)}, 0L);
        }
        final Frame[] frames = new Frame[list.size()];
        for (int i = 0; i < frames.length; i++) {
            frames[i] = frame(list.get(i));
        }
        return new SlotVisual(item, frames, Integer.toUnsignedLong(item.frameMs()));
    }

    private static Frame frame(final SlotItem item) {
        final Float bar = item.bar();
        final float value = bar == null ? -1f : Mth.clamp(bar, 0f, 1f);
        final Integer color = item.barColor();
        // Vanilla's durability colour runs from red at 0 to green at 1 (26.3 Item.getBarColor).
        final int argb = color != null ? ARGB.opaque(color) : ARGB.opaque(Mth.hsvToRgb(Math.max(0f, value) / 3f, 1f, 1f));
        return new Frame(SlotItems.stack(item), ItemCounts.text(item.count(), item.countText()), value, argb);
    }

    /**
     * Returns the item this visual shows.
     *
     * @return the bot's item
     */
    public SlotItem item() {
        return item;
    }

    /**
     * Returns the stack shown at a time, for tooltips.
     *
     * @param nowMs a monotonic clock in ms
     * @return the current frame's stack
     */
    public ItemStack stack(final long nowMs) {
        return frames[ItemCounts.frame(frames.length, frameMs, nowMs)].stack();
    }

    /**
     * Returns whether hovering shows no tooltip.
     *
     * @return the item's {@code hideTooltip}
     */
    public boolean hideTooltip() {
        return item.hideTooltip();
    }

    /**
     * Draws the item with its count and bar at a GUI position.
     *
     * @param graphics the frame's GUI extractor
     * @param font the font for counts
     * @param x left of the 16 by 16 item
     * @param y top of the item
     * @param seed the vanilla item model seed
     * @param nowMs a monotonic clock in ms, for frames
     */
    public void draw(final GuiGraphicsExtractor graphics, final Font font, final int x, final int y, final int seed, final long nowMs) {
        final Frame f = frames[ItemCounts.frame(frames.length, frameMs, nowMs)];
        graphics.item(f.stack(), x, y, seed);
        // An empty string draws nothing, so vanilla's own "1" logic stays out of the way.
        graphics.itemDecorations(font, f.stack(), x, y, f.count() == null ? "" : f.count());
        if (f.bar() >= 0f) {
            final int left = x + 2;
            final int top = y + 13;
            graphics.fill(left, top, left + 13, top + 2, 0xFF000000);
            graphics.fill(left, top, left + Math.round(f.bar() * 13f), top + 1, f.barArgb());
        }
    }

    /**
     * Shows the tooltip of the current frame, unless hidden.
     *
     * @param graphics the frame's GUI extractor
     * @param font the font
     * @param mouseX mouse x
     * @param mouseY mouse y
     * @param nowMs a monotonic clock in ms
     */
    public void tooltip(final GuiGraphicsExtractor graphics, final Font font, final int mouseX, final int mouseY, final long nowMs) {
        if (!item.hideTooltip()) {
            graphics.setTooltipForNextFrame(font, stack(nowMs), mouseX, mouseY);
        }
    }

    /** Plays the item's {@code clickSound} on the client, if it has one. */
    public void playClickSound() {
        final String sound = item.clickSound();
        if (sound == null) {
            return;
        }
        final Identifier id = Identifier.tryParse(sound);
        if (id != null) {
            Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvent.createVariableRangeEvent(id), 1f));
        }
    }
}
