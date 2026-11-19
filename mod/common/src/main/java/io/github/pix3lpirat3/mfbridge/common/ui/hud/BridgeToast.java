package io.github.pix3lpirat3.mfbridge.common.ui.hud;

import io.github.pix3lpirat3.mfbridge.common.ui.logic.ToastLayout;
import io.github.pix3lpirat3.mfbridge.common.ui.screen.UiText;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.toasts.Toast;
import net.minecraft.client.gui.components.toasts.ToastManager;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

/**
 * A toast for the mod's and bots' messages in vanilla's system toast style. Unlike the system toast
 * it wraps the body to a width that fits the window, cuts it to at most three toast slots so it is
 * always shown, and stacks with other messages instead of replacing them; the same message again
 * only shows the toast longer. Client thread only.
 */
public final class BridgeToast implements Toast {

    private static final Identifier BACKGROUND = Identifier.withDefaultNamespace("toast/system");
    private static final int TITLE_ARGB = 0xFFFFFF00;
    private static final int ERROR_TITLE_ARGB = 0xFFFF7070;
    private static final int BODY_ARGB = 0xFFFFFFFF;
    /** Time added for each body line past the first, so longer messages can be read. */
    private static final long PER_LINE_MS = 1_000L;

    /** What tells two toasts apart: the same title and text is the same toast. */
    private record Key(String title, String message) {
    }

    private final Key key;
    private final ToastLayout layout;
    private final long displayMs;
    private final boolean error;
    private long shownSince;
    private boolean changed;
    private Visibility wanted = Visibility.HIDE;

    private BridgeToast(final Key key, final ToastLayout layout, final long displayMs, final boolean error) {
        this.key = key;
        this.layout = layout;
        this.displayMs = displayMs + PER_LINE_MS * Math.max(0, layout.body().size() - 1);
        this.error = error;
    }

    /**
     * Shows a toast, or shows the same one longer when it is already up.
     *
     * @param title the title, usually a bot name
     * @param message the body, empty for none
     * @param displayMs how long it stays, before the player's notification time setting
     * @param error whether it reports a failure
     */
    public static void show(final String title, final String message, final long displayMs, final boolean error) {
        final Minecraft minecraft = Minecraft.getInstance();
        final ToastManager toasts = minecraft.gui.toastManager();
        final Key key = new Key(title, message);
        final BridgeToast shown = toasts.getToast(BridgeToast.class, key);
        if (shown != null) {
            shown.changed = true;
            return;
        }
        final ToastLayout layout = ToastLayout.of(title, message, minecraft.getWindow().getGuiScaledWidth(), UiText.width(minecraft.font));
        toasts.addToast(new BridgeToast(key, layout, displayMs, error));
    }

    @Override
    public Object getToken() {
        return key;
    }

    @Override
    public int width() {
        return layout.width();
    }

    @Override
    public int height() {
        return layout.height();
    }

    @Override
    public float yPos(final int firstSlotIndex) {
        // The default multiplies by this toast's own height, which puts a tall toast too low.
        return firstSlotIndex * (float) ToastLayout.SLOT_HEIGHT;
    }

    @Override
    public Visibility getWantedVisibility() {
        return wanted;
    }

    @Override
    public void update(final ToastManager manager, final long fullyVisibleForMs) {
        if (changed) {
            shownSince = fullyVisibleForMs;
            changed = false;
        }
        final double limit = displayMs * manager.getNotificationDisplayTimeMultiplier();
        wanted = fullyVisibleForMs - shownSince < limit ? Visibility.SHOW : Visibility.HIDE;
    }

    @Override
    public void extractRenderState(final GuiGraphicsExtractor graphics, final Font font, final long fullyVisibleForMs) {
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, BACKGROUND, 0, 0, width(), height());
        final int x = ToastLayout.PADDING_X;
        if (layout.body().isEmpty()) {
            graphics.text(font, layout.title(), x, 12, error ? ERROR_TITLE_ARGB : TITLE_ARGB, false);
            return;
        }
        graphics.text(font, layout.title(), x, ToastLayout.PADDING_Y, error ? ERROR_TITLE_ARGB : TITLE_ARGB, false);
        int y = ToastLayout.PADDING_Y + ToastLayout.LINE;
        for (final String line : layout.body()) {
            graphics.text(font, line, x, y, BODY_ARGB, false);
            y += ToastLayout.LINE;
        }
    }
}
