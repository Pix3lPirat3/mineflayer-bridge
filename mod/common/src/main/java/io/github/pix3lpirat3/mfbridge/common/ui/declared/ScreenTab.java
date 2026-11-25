package io.github.pix3lpirat3.mfbridge.common.ui.declared;

import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractScrollArea;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.ScrollableLayout;
import net.minecraft.client.gui.components.tabs.Tab;
import net.minecraft.client.gui.layouts.Layout;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.network.chat.Component;

/** One tab of a screen view: a vertical column of rows that scrolls inside the tab area. */
final class ScreenTab implements Tab {

    private final Component title;
    private final ScrollableLayout scroll;

    ScreenTab(final Minecraft minecraft, final Component title, final LinearLayout content) {
        this.title = title;
        this.scroll = new ScrollableLayout(minecraft, content, 0);
    }

    @Override
    public Component getTabTitle() {
        return title;
    }

    @Override
    public Component getTabExtraNarration() {
        return Component.empty();
    }

    @Override
    public void visitChildren(final Consumer<AbstractWidget> consumer) {
        scroll.visitWidgets(consumer);
    }

    @Override
    public void doLayout(final ScreenRectangle area) {
        // The limits must be set before arranging, or a fresh column is laid out unclipped.
        scroll.setMaxHeight(area.height());
        scroll.setMinHeight(area.height());
        scroll.arrangeElements();
        scroll.setX(area.left() + (area.width() - scroll.getWidth()) / 2);
        scroll.setY(area.top());
    }

    @Override
    public Layout getLayout() {
        return scroll;
    }

    /** Returns how far the column is scrolled, in GUI pixels. */
    double scrollAmount() {
        // The layout's one child is its scroll area, in every version; only 26.3 has a getter.
        final double[] amount = {0d};
        scroll.visitChildren(e -> {
            if (e instanceof AbstractScrollArea area) {
                amount[0] = area.scrollAmount();
            }
        });
        return amount[0];
    }

    /** Scrolls the column; call after {@link #doLayout}, which places it. */
    void setScrollAmount(final double amount) {
        scroll.visitChildren(child -> {
            if (child instanceof AbstractScrollArea area) {
                area.setScrollAmount(amount);
            }
        });
    }
}
