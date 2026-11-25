package io.github.pix3lpirat3.mfbridge.common.ui.input;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.pix3lpirat3.mfbridge.common.Constants;
import io.github.pix3lpirat3.mfbridge.common.ui.UiContext;
import io.github.pix3lpirat3.mfbridge.common.ui.logic.KeybindIndex;
import io.github.pix3lpirat3.mfbridge.common.ui.logic.DisplayText;
import io.github.pix3lpirat3.mfbridge.common.version.Game;
import io.github.pix3lpirat3.mfbridge.core.BridgeCore;
import io.github.pix3lpirat3.mfbridge.core.decl.DeclarationSnapshot;
import io.github.pix3lpirat3.mfbridge.core.decl.KeybindDecl;
import io.github.pix3lpirat3.mfbridge.core.decl.ToolDecl;
import io.github.pix3lpirat3.mfbridge.protocol.core.KeyActivation;
import io.github.pix3lpirat3.mfbridge.protocol.core.KeybindData;
import io.github.pix3lpirat3.mfbridge.protocol.core.ToolInput;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import org.jspecify.annotations.Nullable;

/**
 * Routes key events to bot keybinds. The key-to-binds index is rebuilt only when
 * the declarations version changes; conflicts are logged once each. A consumed press, its repeats
 * and its release never reach vanilla. Client thread only.
 */
public final class KeybindRouter {

    private static final String TOOL_PREFIX = "tool:";

    private final UiContext ui;
    private final ToolController tools;
    private final Set<String> loggedConflicts = new HashSet<>();
    private final Set<String> loggedContexts = new HashSet<>();
    private final Set<String> consumed = new HashSet<>();
    private final List<KeybindIndex.Bind> armed = new ArrayList<>();
    private final Map<String, InputConstants.Key> keys = new HashMap<>();
    private KeybindIndex index = KeybindIndex.EMPTY;
    private long version = -1L;

    /**
     * Creates the router.
     *
     * @param ui the UI services
     * @param tools tool mode, for {@code tool:<id>} contexts
     */
    public KeybindRouter(final UiContext ui, final ToolController tools) {
        this.ui = ui;
        this.tools = tools;
    }

    /**
     * Rebuilds the index when the declarations changed.
     *
     * @param decl the current declarations
     */
    public void sync(final DeclarationSnapshot decl) {
        if (decl.version() == version) {
            return;
        }
        version = decl.version();
        final List<KeybindIndex.Bind> binds = new ArrayList<>();
        keys.clear();
        final List<KeybindDecl> list = decl.keybinds();
        for (int i = 0; i < list.size(); i++) {
            final KeybindDecl k = list.get(i);
            final KeybindData d = k.data();
            if (d.keys().isEmpty()) {
                continue;
            }
            boolean known = true;
            for (final String name : d.keys()) {
                final InputConstants.Key key = parse(name);
                if (key == null) {
                    known = false;
                    logOnce(loggedContexts, "key:" + name, "Keybind {} of {} uses unknown key {}; it is ignored", k.key(), k.ownerName(), name);
                } else {
                    keys.put(name, key);
                }
            }
            if (!known) {
                continue;
            }
            if (!supportedContext(d.context())) {
                logOnce(loggedContexts, "ctx:" + d.context(), "Keybind context {} (used by {}) is not supported yet; such binds never trigger",
                        d.context(), k.key());
            }
            binds.add(new KeybindIndex.Bind(k.key(), d.keys(), d.context(), d.activation(), d.priority(), d.consumes(), i));
        }
        index = KeybindIndex.build(binds);
        armed.clear();
        for (final KeybindIndex.Conflict c : index.conflicts()) {
            final String id = c.winner().declKey() + "|" + c.loser().declKey();
            logOnce(loggedConflicts, id, "Keybind conflict on {}: {} wins over {}{}", String.join("+", c.winner().keys()),
                    c.winner().declKey(), c.loser().declKey(), c.tie() ? " (same priority, the later one wins)" : " (priority)");
        }
    }

    /**
     * Handles a key or mouse button event.
     *
     * @param keyName the vanilla key name, for example {@code key.keyboard.g}
     * @param action {@link InputConstants#PRESS}, {@link InputConstants#RELEASE} or a repeat
     * @return whether vanilla must not see the event
     */
    public boolean onKey(final String keyName, final int action) {
        if (action == InputConstants.RELEASE) {
            release(keyName);
            return consumed.remove(keyName);
        }
        if (action != InputConstants.PRESS) {
            return consumed.contains(keyName);
        }
        if (!index.uses(keyName)) {
            return false;
        }
        final KeybindIndex.Bind bind = index.match(keyName, this::isDown, this::contextActive);
        if (bind == null) {
            return false;
        }
        armed.add(bind);
        if (bind.activation() != KeyActivation.RELEASE) {
            fire(bind, true);
        }
        if (bind.consumes()) {
            consumed.add(keyName);
            return true;
        }
        return false;
    }

    private void release(final String keyName) {
        for (int i = armed.size() - 1; i >= 0; i--) {
            final KeybindIndex.Bind bind = armed.get(i);
            if (bind.keys().contains(keyName)) {
                armed.remove(i);
                if (bind.activation() != KeyActivation.PRESS) {
                    fire(bind, false);
                }
            }
        }
    }

    private void fire(final KeybindIndex.Bind bind, final boolean pressed) {
        final BridgeCore core = ui.core();
        if (core == null) {
            return;
        }
        // A tool's own keybinds (context tool:<id>) reach its owner as tool.event.
        if (bind.context().startsWith(TOOL_PREFIX) && tools.declares(ToolInput.KEY)) {
            if (pressed) {
                tools.fire(ToolInput.KEY, bind.trigger());
            }
            return;
        }
        core.keybindEvent(bind.declKey(), pressed);
    }

    private boolean isDown(final String keyName) {
        final InputConstants.Key key = keys.get(keyName);
        if (key == null) {
            return false;
        }
        if (key.getType() == Game.KEYBOARD) {
            return Game.keyDown(key.getValue());
        }
        final var options = Minecraft.getInstance().options;
        return Game.matches(options.keyAttack, key) && options.keyAttack.isDown()
                || Game.matches(options.keyUse, key) && options.keyUse.isDown();
    }

    private boolean contextActive(final KeybindIndex.Bind bind) {
        final String context = bind.context();
        final Screen screen = Game.screen();
        return switch (context) {
            case "any" -> true;
            case "ingame" -> screen == null;
            case "gui" -> screen != null;
            // Bot-selected binds belong to the focused bot only.
            case "bot-selected" -> screen == null && focused(bind.declKey());
            default -> {
                if (!context.startsWith(TOOL_PREFIX) || screen != null) {
                    yield false;
                }
                final ToolDecl tool = tools.active();
                if (tool == null) {
                    yield false;
                }
                // tool:<id> names a tool in the bind's own owner and extension scope.
                final String declKey = bind.declKey();
                final int scopeEnd = declKey.indexOf("/~key/");
                final String scope = scopeEnd < 0 ? "" : declKey.substring(0, scopeEnd);
                yield tool.key().equals(scope + "/~tool/" + context.substring(TOOL_PREFIX.length()));
            }
        };
    }

    private boolean focused(final String declKey) {
        final BridgeCore core = ui.core();
        final String focused = core == null ? null : core.focus().focused();
        return focused != null && DisplayText.ownedBy(declKey, focused);
    }

    private static boolean supportedContext(final String context) {
        return context.equals("any") || context.equals("ingame") || context.equals("gui") || context.equals("bot-selected")
                || context.startsWith(TOOL_PREFIX);
    }

    private static InputConstants.@Nullable Key parse(final String name) {
        try {
            final InputConstants.Key key = InputConstants.getKey(name);
            return key == InputConstants.UNKNOWN ? null : key;
        } catch (final RuntimeException e) {
            return null;
        }
    }

    private static void logOnce(final Set<String> seen, final String id, final String format, final Object... args) {
        if (seen.add(id)) {
            Constants.LOG.warn(format, args);
        }
    }
}
