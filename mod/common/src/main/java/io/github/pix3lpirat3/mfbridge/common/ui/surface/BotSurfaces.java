package io.github.pix3lpirat3.mfbridge.common.ui.surface;

import io.github.pix3lpirat3.mfbridge.common.display.Displays;
import io.github.pix3lpirat3.mfbridge.common.ui.UiContext;
import io.github.pix3lpirat3.mfbridge.common.ui.container.ContainerViewScreen;
import io.github.pix3lpirat3.mfbridge.common.ui.declared.DeclaredScreen;
import io.github.pix3lpirat3.mfbridge.common.ui.input.PickController;
import io.github.pix3lpirat3.mfbridge.common.ui.input.PromptController;
import io.github.pix3lpirat3.mfbridge.common.ui.logic.Surfaces;
import io.github.pix3lpirat3.mfbridge.common.ui.screen.ChildScreen;
import io.github.pix3lpirat3.mfbridge.common.ui.screen.FormHost;
import io.github.pix3lpirat3.mfbridge.common.ui.screen.FormScreen;
import io.github.pix3lpirat3.mfbridge.common.ui.screen.PromptScreen;
import io.github.pix3lpirat3.mfbridge.common.version.Game;
import io.github.pix3lpirat3.mfbridge.core.BridgeCore;
import io.github.pix3lpirat3.mfbridge.core.decl.ContainerViewDecl;
import io.github.pix3lpirat3.mfbridge.core.decl.ScreenDecl;
import io.github.pix3lpirat3.mfbridge.core.input.FormInput;
import io.github.pix3lpirat3.mfbridge.core.input.InputRequest;
import io.github.pix3lpirat3.mfbridge.core.input.PickInput;
import io.github.pix3lpirat3.mfbridge.core.input.PromptInput;
import io.github.pix3lpirat3.mfbridge.core.input.WorldMenuInput;
import io.github.pix3lpirat3.mfbridge.protocol.core.PickKind;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.components.events.ContainerEventHandler;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import org.jspecify.annotations.Nullable;

/**
 * Everything bots put in front of the player, in one place: the active input request (pick, prompt,
 * world menu or form), declared screens and container views. Decides when what a bot asks for may
 * open (over the game, or over a {@link FormHost} when the player asked for it there), tells the
 * owners when their screens close, and lets the home key leave any of it for Bridge home.
 * Client thread only.
 */
public final class BotSurfaces {

    /** Makes the screen of a declaration. */
    @FunctionalInterface
    public interface DeclaredScreens {
        /**
         * Creates the screen.
         *
         * @param parent the screen to return to, or null to close to the game
         * @param decl the declaration
         * @param primary whether it opens as the primary menu
         * @return the screen
         */
        DeclaredScreen create(@Nullable Screen parent, ScreenDecl decl, boolean primary);
    }

    private final UiContext ui;
    private final PickController picks;
    private final PromptController prompts;
    private final Displays displays;
    private final BotForms forms;
    private final DeclaredScreens declared;
    private final Consumer<@Nullable Screen> openHome;
    /** Declared screens that left the screen stack this tick; each is closed unless it is still below the current screen. */
    private final List<DeclaredScreen> leftScreens = new ArrayList<>();
    private long currentId = -1L;
    private long handledId = -1L;
    private @Nullable FormScreen form;
    private BotForms.@Nullable Dialog formDialog;

    /**
     * Creates the controller.
     *
     * @param ui the core and toasts
     * @param picks the active pick
     * @param prompts the active prompt
     * @param displays the world menus
     * @param forms builds and answers forms
     * @param declared makes declared screens
     * @param openHome opens Bridge home over a parent screen, or over the game for null
     */
    public BotSurfaces(final UiContext ui, final PickController picks, final PromptController prompts, final Displays displays,
            final BotForms forms, final DeclaredScreens declared, final Consumer<@Nullable Screen> openHome) {
        this.ui = ui;
        this.picks = picks;
        this.prompts = prompts;
        this.displays = displays;
        this.forms = forms;
        this.declared = declared;
        this.openHome = openHome;
    }

    /**
     * Records that the UI answered or cancelled an input request, so it is not shown again while the
     * core catches up.
     *
     * @param id the input queue id
     */
    public void markHandled(final long id) {
        handledId = id;
    }

    /** Ends what is showing and forgets the active request, for a core that stopped or changed. */
    public void reset() {
        endCurrent();
        currentId = -1L;
    }

    /** Opens the view and the screen a bot asked for once they may open, and settles screens that left. */
    public void openRequested(final BridgeCore core) {
        openRequestedView(core);
        settleScreens(core);
        openRequestedScreen(core);
    }

    /**
     * Opens the primary menu: the declared screen the player picked while its
     * owner still offers it, else Bridge home.
     *
     * @param parent the screen to return to, or null to close to the game
     */
    public void openPrimary(final @Nullable Screen parent) {
        final BridgeCore core = ui.core();
        final String key = core == null ? null : core.screens().primaryMenu();
        final ScreenDecl decl = key == null ? null : core.declarations().screen(key);
        if (decl == null) {
            openHome.accept(parent);
        } else {
            Game.setScreen(declared.create(parent, decl, true));
        }
    }

    /**
     * Opens a declared screen as the player would, from Bridge home or a bot's button.
     *
     * @param parent the screen to return to, or null to close to the game
     * @param screenKey object key of a {@code core:screen}
     * @return false when no attached session declares it
     */
    public boolean openScreen(final @Nullable Screen parent, final String screenKey) {
        final ScreenDecl decl = ui.declarations().screen(screenKey);
        if (decl == null) {
            return false;
        }
        Game.setScreen(declared.create(parent, decl, false));
        return true;
    }

    /**
     * A declared screen left the screen stack, to a child, back to its parent or away; it is closed on
     * the next tick unless it is still below the current screen.
     *
     * @param screen the screen
     */
    public void screenLeft(final DeclaredScreen screen) {
        leftScreens.add(screen);
    }

    private void settleScreens(final BridgeCore core) {
        if (leftScreens.isEmpty()) {
            return;
        }
        final Screen current = Game.screen();
        for (final DeclaredScreen left : leftScreens) {
            if (!below(current, left)) {
                core.screens().closed(left.key());
            }
        }
        leftScreens.clear();
    }

    /** Whether {@code screen} is {@code current} or one of its parents. */
    private static boolean below(final @Nullable Screen current, final Screen screen) {
        for (Screen s = current; s != null; s = s instanceof ChildScreen c ? c.parent() : null) {
            if (s == screen) {
                return true;
            }
        }
        return false;
    }

    /** A screen a bot opens waits, as container views do, for the player in game. */
    private void openRequestedScreen(final BridgeCore core) {
        final String requested = core.screens().requestedOpen();
        final Minecraft minecraft = Minecraft.getInstance();
        if (requested != null && mayReplace(Game.screen(), core.screens().askedFor(requested)) && minecraft.player != null
                && !picks.active() && Game.overlay() == null) {
            openScreen(Game.screen(), requested);
        }
    }

    /** A view a bot opens waits until the player is in game with no other screen open. */
    private void openRequestedView(final BridgeCore core) {
        final String requested = core.containers().requestedOpen();
        final Minecraft minecraft = Minecraft.getInstance();
        if (requested != null && mayReplace(Game.screen(), core.containers().askedFor(requested)) && minecraft.player != null
                && !picks.active() && Game.overlay() == null) {
            openContainerView(requested);
        }
    }

    /**
     * Opens a bot's container view or menu and reports it open to the core.
     *
     * @param viewKey full key of a declared {@code core:container-view}
     * @return whether the view opened
     */
    public boolean openContainerView(final String viewKey) {
        final BridgeCore core = ui.core();
        if (core == null) {
            return false;
        }
        final ContainerViewDecl view = core.declarations().containerView(viewKey);
        if (view == null) {
            core.containers().closed(viewKey);
            return false;
        }
        try {
            core.containers().opened(viewKey);
        } catch (final IllegalArgumentException e) {
            ui.toastError(view.ownerName(), String.valueOf(e.getMessage()));
            return false;
        }
        Game.setScreen(new ContainerViewScreen(ui, view));
        return true;
    }

    /** Follows the input queue: starts the request that became active and puts a waiting form in front. */
    public void sync(final BridgeCore core) {
        InputRequest active = core.input().active();
        if (active != null && active.id() == handledId) {
            active = null;
        }
        final long id = active == null ? -1L : active.id();
        if (id != currentId) {
            endCurrent();
            currentId = id;
            switch (active) {
                case null -> {
                }
                case PickInput p -> picks.begin(p);
                case PromptInput p -> {
                    prompts.begin(p);
                    if (Surfaces.forPrompt(p.request()) == Surfaces.Surface.WORLD) {
                        displays.menus().beginPrompt(p);
                    }
                }
                case WorldMenuInput w -> displays.menus().begin(w);
                case FormInput f -> {
                    if (Surfaces.forForm(f.request()) == Surfaces.Surface.DIALOG) {
                        formDialog = forms.dialog(f);
                    } else {
                        form = forms.screen(f);
                    }
                }
            }
        }
        final FormScreen f = form;
        final Minecraft minecraft = Minecraft.getInstance();
        final BotForms.Dialog d = formDialog;
        final Screen host = formReturn;
        if (host != null && Game.screen() == null && (f == null || f.finished()) && (d == null || d.finished())) {
            // The form is done: back to the screen it opened over.
            formReturn = null;
            Game.setScreen(host);
            return;
        }
        final boolean waiting = f != null && !f.finished() || d != null && !d.finished();
        if (!waiting || picks.active() || !mayReplace(Game.screen(), core.input().askedFor(currentId))) {
            return;
        }
        rememberHost(Game.screen());
        Game.setScreen(f != null && !f.finished() ? f : d.screen());
    }

    /** The screen a form opened over, to go back to when it closes; null for the game. */
    private @Nullable Screen formReturn;

    private void rememberHost(final @Nullable Screen screen) {
        if (screen instanceof FormHost) {
            formReturn = screen;
        }
    }

    /**
     * Whether a bot's form, screen or container view may open over the current screen: over the
     * game, or over a {@link FormHost} such as Bridge home or the map when the player asked for it
     * there, as with a bot manager's "Add bot" that answers with a form. What the player did not ask
     * for waits until they are back in the game, so a bot cannot cover Bridge home.
     *
     * @param asked whether the player ran something of the owner a moment ago
     */
    private static boolean mayReplace(final @Nullable Screen screen, final boolean asked) {
        return screen == null || asked && screen instanceof FormHost;
    }

    /**
     * The home key inside something a bot put in front of the player: dismisses it, which the bot
     * hears as the player cancelling, and opens Bridge home. Without this a bot that keeps a screen
     * up could keep the player out of the mod's own menu.
     *
     * @param screen the open screen, or null in game
     * @return whether the key was used; false leaves it to the key mapping or the screen
     */
    public boolean leaveForHome(final @Nullable Screen screen) {
        final Minecraft minecraft = Minecraft.getInstance();
        if (screen == null) {
            if (minecraft.player == null || Game.overlay() != null || !botInputActive()) {
                return false;
            }
        } else if (!botSurface(screen) || typing(screen)) {
            // A focused text box gets the letter; Esc still leaves, and then the key works.
            return false;
        }
        dismissBotSurfaces(screen);
        openHome.accept(null);
        return true;
    }

    /** Whether a bot's pick, prompt, world menu or form is waiting on the player. */
    private boolean botInputActive() {
        final FormScreen f = form;
        final BotForms.Dialog d = formDialog;
        return picks.active() && !picks.isLocal() || prompts.active() != null || displays.menus().active()
                || f != null && !f.finished() || d != null && !d.finished();
    }

    /** Whether a screen shows something a bot asked for or declared. */
    private boolean botSurface(final Screen screen) {
        final BotForms.Dialog d = formDialog;
        return screen == form || d != null && d.shown() == screen || screen instanceof DeclaredScreen
                || screen instanceof ContainerViewScreen || screen instanceof PromptScreen || screen instanceof SignPrompt.Editor
                || BridgeDialogs.owns(screen) || picks.kind() == PickKind.ITEM && screen instanceof AbstractContainerScreen<?>;
    }

    /** Whether keys are text for the screen right now. */
    private static boolean typing(final Screen screen) {
        if (screen instanceof SignPrompt.Editor) {
            return true;
        }
        GuiEventListener focused = screen.getFocused();
        while (focused instanceof ContainerEventHandler c && c.getFocused() != null) {
            focused = c.getFocused();
        }
        return focused instanceof EditBox box && box.canConsumeInput() || focused instanceof MultiLineEditBox;
    }

    /** Cancels every request waiting on the player and closes the bot's screens, telling their owners. */
    private void dismissBotSurfaces(final @Nullable Screen screen) {
        final BridgeCore core = ui.core();
        final FormScreen f = form;
        if (f != null) {
            f.cancel();
        }
        final BotForms.Dialog d = formDialog;
        if (d != null) {
            d.cancelled();
        }
        if (picks.active()) {
            picks.cancel();
        }
        if (prompts.active() != null) {
            prompts.cancel();
        }
        if (displays.menus().active()) {
            displays.menus().cancel();
        }
        formReturn = null;
        // A declared screen keeps its parents open below it; all of them close.
        for (Screen s = screen; s != null && core != null; s = s instanceof ChildScreen c ? c.parent() : null) {
            if (s instanceof DeclaredScreen declared) {
                core.screens().closed(declared.key());
            }
        }
    }

    private void endCurrent() {
        final BotForms.Dialog d = formDialog;
        formDialog = null;
        if (d != null) {
            d.end();
            if (d.shown() != null && Game.screen() == d.shown()) {
                Game.setScreen(null);
            }
        }
        final FormScreen f = form;
        form = null;
        if (f != null) {
            f.dismiss();
            if (Game.screen() == f) {
                Game.setScreen(null);
            }
        }
        if (picks.active() && (!picks.isLocal() || f != null)) {
            picks.end();
        }
        prompts.end();
        displays.menus().end();
    }
}
