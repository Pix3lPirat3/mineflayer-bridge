package io.github.pix3lpirat3.mfbridge.common.ext;

import io.github.pix3lpirat3.mfbridge.common.ui.BridgeUi;
import io.github.pix3lpirat3.mfbridge.api.client.BotButton;
import io.github.pix3lpirat3.mfbridge.api.client.HomeTab;
import io.github.pix3lpirat3.mfbridge.api.client.ClientBridge;
import io.github.pix3lpirat3.mfbridge.api.client.ClientRegistrar;
import io.github.pix3lpirat3.mfbridge.api.client.ClientExtension;
import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import com.mojang.blaze3d.platform.InputConstants;
import io.github.pix3lpirat3.mfbridge.api.BridgeExtension;
import io.github.pix3lpirat3.mfbridge.common.Constants;
import io.github.pix3lpirat3.mfbridge.common.seam.RenderFrame;
import io.github.pix3lpirat3.mfbridge.common.seam.WorldRenderListener;
import io.github.pix3lpirat3.mfbridge.common.ui.input.InputPipeline;
import io.github.pix3lpirat3.mfbridge.api.client.InputStage;
import io.github.pix3lpirat3.mfbridge.core.ext.ExtensionResolver;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.BiConsumer;
import java.util.function.Supplier;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * The mod extensions of this client: discovery, the player's switches, dependency
 * resolution, and every client hook they registered. Built once at client init; switching an
 * extension on or off is saved and applies after a restart, because key mappings and other hooks can
 * only be registered at start.
 */
public final class ClientExtensions {

    private static final Gson GSON = new Gson();

    /**
     * A Bridge home tab and its extension.
     *
     * @param extensionId the extension
     * @param label the tab label
     * @param tab its content
     */
    public record Tab(String extensionId, Component label, HomeTab tab) {}

    /**
     * A bot-row button and its extension.
     *
     * @param extensionId the extension
     * @param label the label
     * @param icon an item id, or null
     * @param button the button
     */
    public record Button(String extensionId, Component label, @Nullable String icon, BotButton button) {}

    private record Key(String extensionId, KeyMapping mapping, Runnable onPress) {}

    private record Hook<T>(String extensionId, T hook) {}

    private record FileFormat(@Nullable List<String> disabled) {}

    private final Path folder;
    private final Path file;
    private final InputPipeline input;
    private final ClientBridge bridge;
    private final Set<String> disabled;
    private final ExtensionResolver.Resolution resolution;
    private final List<Tab> tabs = new ArrayList<>();
    private final List<Button> buttons = new ArrayList<>();
    private final List<Key> keys = new ArrayList<>();
    private final List<Hook<Runnable>> ticks = new ArrayList<>();
    private final List<Hook<WorldRenderListener>> renders = new ArrayList<>();
    private final List<Hook<BiConsumer<GuiGraphicsExtractor, DeltaTracker>>> huds = new ArrayList<>();
    /** Extensions whose hook threw; their hooks stop for the rest of the session. */
    private final Set<String> failed = new HashSet<>();

    /**
     * Discovers, resolves and registers extensions.
     *
     * @param firstParty the bridge's own extensions, before third-party ones
     * @param discovered extensions other mods provide
     * @param configDir the game's config directory
     * @param input the in-game input chain extensions add stages to
     * @param bridge what extensions see of the running bridge
     */
    public ClientExtensions(final List<BridgeExtension> firstParty, final List<BridgeExtension> discovered, final Path configDir,
            final InputPipeline input, final ClientBridge bridge) {
        this.input = input;
        this.bridge = bridge;
        this.folder = configDir.resolve(Constants.MOD_ID);
        this.file = folder.resolve("extensions.json");
        this.disabled = load(file);
        final List<BridgeExtension> found = new ArrayList<>(firstParty);
        found.addAll(discovered);
        this.resolution = ExtensionResolver.resolve(found, disabled);
        for (final BridgeExtension e : resolution.load()) {
            if (e instanceof ClientExtension client) {
                final String id = e.id().toString();
                try {
                    client.registerClient(new Registrar(id));
                } catch (final RuntimeException ex) {
                    failed.add(id);
                    Constants.LOG.error("Bridge extension {} failed to register its client parts", id, ex);
                }
            }
        }
        for (final ExtensionResolver.Status s : resolution.statuses()) {
            if (s.state() != ExtensionResolver.State.LOADED) {
                Constants.LOG.warn("Bridge extension {} {}: {}", s.id(), s.state().name().toLowerCase(), s.reason());
            }
        }
    }

    private static Set<String> load(final Path file) {
        try {
            final FileFormat f = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), FileFormat.class);
            return f == null || f.disabled() == null ? new TreeSet<>() : new TreeSet<>(f.disabled());
        } catch (final NoSuchFileException e) {
            return new TreeSet<>();
        } catch (final IOException | JsonParseException e) {
            Constants.LOG.warn("Ignoring unreadable {}: {}", file, e.toString());
            return new TreeSet<>();
        }
    }

    /** Returns what extensions see of the running bridge. */
    public ClientBridge bridge() {
        return bridge;
    }

    /** Returns the extensions that loaded, dependencies first, for the core to register. */
    public List<BridgeExtension> loaded() {
        return resolution.load();
    }

    /** Returns every discovered extension's state, for Tools > Extensions. */
    public List<ExtensionResolver.Status> statuses() {
        return resolution.statuses();
    }

    /**
     * Returns whether the player switched an extension off.
     *
     * @param id the extension id
     * @return true when off
     */
    public boolean isDisabled(final String id) {
        return disabled.contains(id);
    }

    /**
     * Switches an extension on or off for the next start, and saves the choice.
     *
     * @param id the extension id
     * @param off true to switch it off
     */
    public void setDisabled(final String id, final boolean off) {
        if (!(off ? disabled.add(id) : disabled.remove(id))) {
            return;
        }
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(new FileFormat(List.copyOf(disabled))), StandardCharsets.UTF_8);
        } catch (final IOException e) {
            Constants.LOG.warn("Could not save {}: {}", file, e.toString());
        }
    }

    /** Returns the key mappings extensions added, for the loaders to register. */
    public List<KeyMapping> keyMappings() {
        return keys.stream().map(Key::mapping).toList();
    }

    /** Returns the Bridge home tabs extensions added, in registration order. */
    public List<Tab> tabs() {
        return tabs.stream().filter(t -> !failed.contains(t.extensionId())).toList();
    }

    /** Returns the bot-row buttons extensions added, in registration order. */
    public List<Button> botButtons() {
        return buttons.stream().filter(b -> !failed.contains(b.extensionId())).toList();
    }

    /**
     * Runs a call for an extension; a throw switches the extension's hooks off for the session.
     *
     * @param extensionId the extension
     * @param call the call
     */
    public void guard(final String extensionId, final Runnable call) {
        if (failed.contains(extensionId)) {
            return;
        }
        try {
            call.run();
        } catch (final RuntimeException e) {
            failed.add(extensionId);
            Constants.LOG.error("Bridge extension {} failed and is stopped until the game restarts", extensionId, e);
        }
    }

    /** Client tick: presses of extension keys in game, then tick hooks. */
    public void tick(final boolean inGame) {
        for (final Key k : keys) {
            while (k.mapping().consumeClick()) {
                if (inGame) {
                    guard(k.extensionId(), k.onPress());
                }
            }
        }
        for (final Hook<Runnable> h : ticks) {
            guard(h.extensionId(), h.hook());
        }
    }

    /** World pass: extension drawing, on the render thread. */
    public void worldRender(final RenderFrame frame) {
        for (final Hook<WorldRenderListener> h : renders) {
            guard(h.extensionId(), () -> h.hook().onWorldRender(frame));
        }
    }

    /** HUD pass: extension drawing after the bridge's HUD. */
    public void hud(final GuiGraphicsExtractor graphics, final DeltaTracker delta) {
        for (final Hook<BiConsumer<GuiGraphicsExtractor, DeltaTracker>> h : huds) {
            guard(h.extensionId(), () -> h.hook().accept(graphics, delta));
        }
    }

    private final class Registrar implements ClientRegistrar {
        private final String id;

        Registrar(final String id) {
            this.id = id;
        }

        @Override
        public ClientBridge bridge() {
            return bridge;
        }

        @Override
        public Path configDir() {
            return folder;
        }

        @Override
        public void homeTab(final Component label, final HomeTab tab) {
            tabs.add(new Tab(id, label, tab));
        }

        @Override
        public void botButton(final Component label, final @Nullable String icon, final BotButton button) {
            buttons.add(new Button(id, label, icon, button));
        }

        @Override
        public KeyMapping keybind(final String name, final int defaultKey, final Runnable onPress) {
            final KeyMapping mapping = BridgeUi.keyMapping(name, defaultKey);
            keys.add(new Key(id, mapping, onPress));
            return mapping;
        }

        @Override
        public void keybind(final KeyMapping mapping, final Runnable onPress) {
            keys.add(new Key(id, mapping, onPress));
        }

        @Override
        public void inputStage(final int priority, final InputStage stage) {
            input.add(priority, new InputStage() {
                @Override
                public Outcome key(final KeyEvent event, final String name) {
                    return guarded(() -> stage.key(event, name));
                }

                @Override
                public Outcome button(final InputConstants.Key key, final String name) {
                    return guarded(() -> stage.button(key, name));
                }

                @Override
                public Outcome scroll(final double amount) {
                    return guarded(() -> stage.scroll(amount));
                }

                private Outcome guarded(final Supplier<Outcome> call) {
                    final Outcome[] out = {Outcome.PASS};
                    guard(id, () -> out[0] = call.get());
                    return out[0];
                }
            });
        }

        @Override
        public void tick(final Runnable hook) {
            ticks.add(new Hook<>(id, hook));
        }

        @Override
        public void worldRender(final WorldRenderListener listener) {
            renders.add(new Hook<>(id, listener));
        }

        @Override
        public void hud(final BiConsumer<GuiGraphicsExtractor, DeltaTracker> hud) {
            huds.add(new Hook<>(id, hud));
        }
    }
}
