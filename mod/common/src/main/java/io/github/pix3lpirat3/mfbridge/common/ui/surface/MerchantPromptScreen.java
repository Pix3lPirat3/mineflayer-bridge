package io.github.pix3lpirat3.mfbridge.common.ui.surface;

import io.github.pix3lpirat3.mfbridge.common.ui.screen.PromptAnswers;
import io.github.pix3lpirat3.mfbridge.common.ui.container.SlotVisual;
import io.github.pix3lpirat3.mfbridge.core.input.PromptInput;
import io.github.pix3lpirat3.mfbridge.protocol.core.MerchantOffer;
import io.github.pix3lpirat3.mfbridge.protocol.core.SlotItem;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.util.Util;
import org.jspecify.annotations.Nullable;

/**
 * The {@code merchant} surface: the prompt's {@code offers} as the
 * trade list of 26.3 {@code MerchantScreen}, "pay these, get this". A click selects an offer and
 * fills the trade slots; a click on the result slot, on the selected offer again, or Enter answers
 * the offer id. Disabled offers show as out of stock and cannot be chosen. There is no {@code
 * MerchantMenu}, so no select-trade packet is built. Client thread only.
 */
public final class MerchantPromptScreen extends MenuSurfaceScreen {

    private static final Identifier BACKGROUND = Identifier.withDefaultNamespace("textures/gui/container/villager.png");
    private static final Identifier BUTTON = Identifier.withDefaultNamespace("widget/button");
    private static final Identifier BUTTON_HIGHLIGHTED = Identifier.withDefaultNamespace("widget/button_highlighted");
    private static final Identifier BUTTON_DISABLED = Identifier.withDefaultNamespace("widget/button_disabled");
    private static final Identifier ARROW = Identifier.withDefaultNamespace("container/villager/trade_arrow");
    private static final Identifier ARROW_OUT_OF_STOCK = Identifier.withDefaultNamespace("container/villager/trade_arrow_out_of_stock");
    private static final Identifier OUT_OF_STOCK = Identifier.withDefaultNamespace("container/villager/out_of_stock");
    private static final Identifier SCROLLER = Identifier.withDefaultNamespace("container/villager/scroller");
    private static final Identifier SCROLLER_DISABLED = Identifier.withDefaultNamespace("container/villager/scroller_disabled");
    private static final int VISIBLE = 7;

    private record Row(MerchantOffer offer, SlotVisual cost, @Nullable SlotVisual cost2, SlotVisual result) {
    }

    private final List<Row> rows = new ArrayList<>();
    private final Component trades = Component.translatable("merchant.trades");
    private int selected = -1;
    private int scroll;

    /**
     * Creates the screen.
     *
     * @param prompt a prompt with {@code offers}
     * @param controller where the answer goes: the prompt controller or a local editor
     */
    public MerchantPromptScreen(final PromptInput prompt, final PromptAnswers controller) {
        super(prompt, controller, "merchant", Component.literal(prompt.ownerName()), BACKGROUND, 276, 166, 512, 256);
        final List<MerchantOffer> offers = prompt.request().offers();
        if (offers != null) {
            for (final MerchantOffer o : offers) {
                final SlotItem cost2 = o.cost2();
                rows.add(new Row(o, SlotVisual.of(o.cost()), cost2 == null ? null : SlotVisual.of(cost2), SlotVisual.of(o.result())));
            }
        }
    }

    private int rowAt(final double mx, final double my) {
        for (int i = 0; i < VISIBLE && scroll + i < rows.size(); i++) {
            if (in(mx, my, 5, 18 + 20 * i, 88, 20)) {
                return scroll + i;
            }
        }
        return -1;
    }

    private void choose(final int index) {
        if (index >= 0 && !rows.get(index).offer().disabled()) {
            answer(rows.get(index).offer().id());
        }
    }

    @Override
    public boolean mouseClicked(final MouseButtonEvent event, final boolean doubleClick) {
        final int index = rowAt(event.x(), event.y());
        if (index >= 0) {
            if (index == selected || doubleClick) {
                choose(index);
            } else {
                selected = index;
            }
            return true;
        }
        if (selected >= 0 && in(event.x(), event.y(), 219, 36, 18, 18)) {
            choose(selected);
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseScrolled(final double x, final double y, final double scrollX, final double scrollY) {
        scroll = Mth.clamp(scroll - (int) Math.signum(scrollY), 0, Math.max(0, rows.size() - VISIBLE));
        return true;
    }

    @Override
    public boolean keyPressed(final KeyEvent event) {
        if (event.isConfirmation() && selected >= 0) {
            choose(selected);
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public void extractBackground(final GuiGraphicsExtractor graphics, final int mouseX, final int mouseY, final float a) {
        super.extractBackground(graphics, mouseX, mouseY, a);
        if (selected >= 0 && rows.get(selected).offer().disabled()) {
            graphics.blitSprite(RenderPipelines.GUI_TEXTURED, OUT_OF_STOCK, left + 83 + 99, top + 35, 28, 21);
        }
        final int hovered = rowAt(mouseX, mouseY);
        for (int i = 0; i < VISIBLE && scroll + i < rows.size(); i++) {
            final int index = scroll + i;
            final Identifier sprite = rows.get(index).offer().disabled() ? BUTTON_DISABLED
                    : index == hovered || index == selected ? BUTTON_HIGHLIGHTED : BUTTON;
            graphics.blitSprite(RenderPipelines.GUI_TEXTURED, sprite, left + 5, top + 18 + 20 * i, 88, 20);
        }
        final int extra = rows.size() - VISIBLE;
        if (extra > 0) {
            final int y = top + 18 + Math.min(139 - 27, scroll * (139 - 27) / extra);
            graphics.blitSprite(RenderPipelines.GUI_TEXTURED, SCROLLER, left + 94, y, 6, 27);
        } else {
            graphics.blitSprite(RenderPipelines.GUI_TEXTURED, SCROLLER_DISABLED, left + 94, top + 18, 6, 27);
        }
    }

    @Override
    public void extractRenderState(final GuiGraphicsExtractor graphics, final int mouseX, final int mouseY, final float a) {
        super.extractRenderState(graphics, mouseX, mouseY, a);
        final long now = Util.getMillis();
        drawContainerLabel(graphics, title, left + 49 + imageWidth / 2 - font.width(title) / 2, top + 6);
        drawContainerLabel(graphics, trades, left + 53 - font.width(trades) / 2, top + 6);
        drawContainerLabel(graphics, INVENTORY_TITLE, left + 107, top + imageHeight - 94);
        for (int i = 0; i < VISIBLE && scroll + i < rows.size(); i++) {
            final Row r = rows.get(scroll + i);
            final int y = top + 19 + 20 * i;
            r.cost().draw(graphics, font, left + 10, y, i, now);
            if (r.cost2() != null) {
                r.cost2().draw(graphics, font, left + 40, y, i, now);
            }
            graphics.blitSprite(RenderPipelines.GUI_TEXTURED, r.offer().disabled() ? ARROW_OUT_OF_STOCK : ARROW, left + 60, y + 3, 10, 9);
            r.result().draw(graphics, font, left + 73, y, i, now);
        }
        if (selected >= 0) {
            final Row r = rows.get(selected);
            r.cost().draw(graphics, font, left + 136, top + 37, 0, now);
            if (r.cost2() != null) {
                r.cost2().draw(graphics, font, left + 162, top + 37, 0, now);
            }
            r.result().draw(graphics, font, left + 220, top + 37, 0, now);
        }
        drawInventory(graphics, left + 108, top + 84);
        tooltip(graphics, mouseX, mouseY, now);
    }

    private void tooltip(final GuiGraphicsExtractor graphics, final int mouseX, final int mouseY, final long now) {
        final int index = rowAt(mouseX, mouseY);
        if (index >= 0) {
            final Row r = rows.get(index);
            final int rx = mouseX - left;
            final SlotVisual under = rx < 36 ? r.cost() : rx < 66 ? r.cost2() : r.result();
            if (under != null) {
                under.tooltip(graphics, font, mouseX, mouseY, now);
            }
            return;
        }
        if (selected >= 0) {
            final Row r = rows.get(selected);
            if (in(mouseX, mouseY, 135, 36, 18, 18)) {
                r.cost().tooltip(graphics, font, mouseX, mouseY, now);
            } else if (r.cost2() != null && in(mouseX, mouseY, 161, 36, 18, 18)) {
                r.cost2().tooltip(graphics, font, mouseX, mouseY, now);
            } else if (in(mouseX, mouseY, 219, 36, 18, 18)) {
                r.result().tooltip(graphics, font, mouseX, mouseY, now);
            }
        }
    }
}
