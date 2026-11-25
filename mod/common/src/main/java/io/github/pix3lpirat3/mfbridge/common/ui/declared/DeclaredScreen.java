package io.github.pix3lpirat3.mfbridge.common.ui.declared;

import io.github.pix3lpirat3.mfbridge.common.ui.BridgeUi;
import io.github.pix3lpirat3.mfbridge.common.version.Game;
import io.github.pix3lpirat3.mfbridge.core.BridgeCore;
import io.github.pix3lpirat3.mfbridge.core.decl.ScreenDecl;
import io.github.pix3lpirat3.mfbridge.protocol.core.ScreenData;
import io.github.pix3lpirat3.mfbridge.protocol.core.ScreenItem;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * A screen a bot or controller declared. Clicks and value changes go to
 * the owner as {@code screen.event}; a failure shows as a toast and puts the declared value back. It
 * closes when its owner closes it, leaves or detaches. As the primary menu it carries a Bridge home
 * button, so the player always reaches Bridge home.
 */
public final class DeclaredScreen extends ScreenView {

    private final BridgeUi ui;
    private final String key;
    private final boolean primary;
    private ScreenDecl last;
    private boolean reported;

    /**
     * Creates the screen.
     *
     * @param parent the screen to return to, or null to close to the game
     * @param ui the UI layer
     * @param decl the declaration to show
     * @param primary whether it opens as the primary menu
     */
    public DeclaredScreen(final @Nullable Screen parent, final BridgeUi ui, final ScreenDecl decl, final boolean primary) {
        super(Component.literal(decl.data().title()), parent);
        this.ui = ui;
        this.key = decl.key();
        this.primary = primary;
        this.last = decl;
    }

    /** Returns the object key of the screen shown. */
    public String key() {
        return key;
    }

    @Override
    protected ScreenData model() {
        final ScreenDecl d = ui.declarations().screen(key);
        if (d != null) {
            last = d;
        }
        return last.data();
    }

    @Override
    protected void init() {
        super.init();
        if (reported) {
            return;
        }
        reported = true;
        final BridgeCore core = ui.core();
        try {
            if (core != null) {
                core.screens().opened(key);
            }
        } catch (final IllegalArgumentException e) {
            ui.toastError(last.ownerName(), String.valueOf(e.getMessage()));
        }
    }

    @Override
    public void tick() {
        super.tick();
        final BridgeCore core = ui.core();
        if (core == null || !core.screens().isOpen(key)) {
            Game.setScreen(parent());
        }
    }

    @Override
    public void removed() {
        super.removed();
        ui.surfaces().screenLeft(this);
    }

    @Override
    protected void footerButtons(final LinearLayout row) {
        if (primary) {
            row.addChild(Button.builder(Component.translatable("mineflayer_bridge.home"), b -> ui.openHome(this)).width(150).build());
        }
    }

    @Override
    protected void use(final ScreenItem item, final @Nullable Object value) {
        if (item.open() != null && value == null) {
            final ScreenDecl child = ui.declarations().screen(item.open());
            if (child == null) {
                ui.toastError(last.ownerName(), I18n.get("mineflayer_bridge.screen.gone"));
            } else {
                Game.setScreen(new DeclaredScreen(this, ui, child, false));
            }
        }
        send(ui, last.ownerName(), key, item, value, this::invalidate);
    }

    /**
     * Sends a control's use to the screen's owner. A failure shows as a toast and runs {@code refused},
     * a result message as an info toast; a plain success says nothing.
     *
     * @param ui the UI layer
     * @param owner the owner's display name, for toasts
     * @param screenKey object key of the screen
     * @param item the control
     * @param value null for a click, else the new value
     * @param refused runs when the owner refused or could not be reached
     */
    public static void send(final BridgeUi ui, final String owner, final String screenKey, final ScreenItem item, final @Nullable Object value,
            final Runnable refused) {
        ui.sounds().ui(item.sound());
        final BridgeCore core = ui.core();
        if (core == null || item.id() == null) {
            return;
        }
        core.screens().event(screenKey, item.id(), value).whenComplete((r, error) -> {
            if (error != null || !r.ok()) {
                final String message = error != null ? BridgeUi.describe(error) : r.message();
                ui.toastError(owner, message == null || message.isEmpty() ? I18n.get("mineflayer_bridge.action.failed", String.valueOf(item.text())) : message);
                refused.run();
            } else if (r.message() != null && !r.message().isEmpty()) {
                ui.toastInfo(owner, r.message());
            }
        });
    }
}
