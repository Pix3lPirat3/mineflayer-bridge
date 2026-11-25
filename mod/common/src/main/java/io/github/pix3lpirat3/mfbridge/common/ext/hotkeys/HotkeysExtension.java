package io.github.pix3lpirat3.mfbridge.common.ext.hotkeys;

import io.github.pix3lpirat3.mfbridge.api.client.InputOrder;
import io.github.pix3lpirat3.mfbridge.api.ExtensionId;
import io.github.pix3lpirat3.mfbridge.api.ExtensionRegistrar;
import io.github.pix3lpirat3.mfbridge.api.SemVer;
import io.github.pix3lpirat3.mfbridge.api.client.ClientBridge;
import io.github.pix3lpirat3.mfbridge.api.client.ClientExtension;
import io.github.pix3lpirat3.mfbridge.api.client.ClientRegistrar;
import io.github.pix3lpirat3.mfbridge.api.client.HomeTab;
import io.github.pix3lpirat3.mfbridge.api.client.InputStage;
import io.github.pix3lpirat3.mfbridge.common.ui.screen.UiColors;
import io.github.pix3lpirat3.mfbridge.common.version.Game;
import io.github.pix3lpirat3.mfbridge.protocol.core.ScreenItem;
import io.github.pix3lpirat3.mfbridge.protocol.core.ScreenRow;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * {@code bridge:hotkeys}: the player binds keys to chat lines for bots, such as F6 for
 * {@code @farmers stop}, on the Hotkeys tab of Bridge home. A press in game runs the line as if typed
 * in chat. Only lines the bridge takes run; a line that would go to the server is never sent.
 * Client only; client thread.
 */
public final class HotkeysExtension implements ClientExtension {

    /** The extension id. */
    public static final ExtensionId ID = new ExtensionId("bridge", "hotkeys");

    /** Runs after the bridge's own stages, so picks and tools keep their keys. */
    private static final int PRIORITY = InputOrder.TOOLS + 100;

    private @Nullable ClientBridge bridge;

    @Override
    public ExtensionId id() {
        return ID;
    }

    @Override
    public SemVer version() {
        return SemVer.parse("1.0.0");
    }

    @Override
    public void register(final ExtensionRegistrar registrar) {
        // Client only: no kinds or messages.
    }

    @Override
    public void registerClient(final ClientRegistrar r) {
        bridge = r.bridge();
        final HotkeyStore store = new HotkeyStore(r.configDir().resolve("hotkeys.json"));
        r.inputStage(PRIORITY, new InputStage() {
            @Override
            public Outcome key(final KeyEvent event, final String name) {
                final ClientBridge b = bridge;
                if (b == null || !b.inPlay()) {
                    return Outcome.PASS;
                }
                for (final Hotkey h : store.all()) {
                    if (h.matches(name, event.hasControlDown(), event.hasShiftDown(), event.hasAltDown())) {
                        run(h);
                        return Outcome.CONSUMED;
                    }
                }
                return Outcome.PASS;
            }
        });
        r.homeTab(Component.translatable("mineflayer_bridge_hotkeys.tab"), new HomeTab() {
            @Override
            public List<ScreenRow> rows() {
                return tabRows(store);
            }

            @Override
            public void use(final String itemId, final @Nullable Object value) {
                final Screen here = Game.screen();
                if (itemId.equals("add")) {
                    Game.setScreen(new HotkeyScreen(here, null, h -> save(store, -1, h), h -> { }));
                } else if (itemId.startsWith("edit/")) {
                    final int index = Integer.parseInt(itemId.substring("edit/".length()));
                    final List<Hotkey> all = store.all();
                    if (index < all.size()) {
                        Game.setScreen(new HotkeyScreen(here, all.get(index), h -> save(store, index, h),
                                h -> store.remove(index)));
                    }
                }
            }
        });
    }

    private static List<ScreenRow> tabRows(final HotkeyStore store) {
        final List<ScreenRow> rows = new ArrayList<>();
        rows.add(new ScreenRow(List.of(new ScreenItem("text").withColor(UiColors.LABEL).withText(I18n.get("mineflayer_bridge_hotkeys.help")))));
        final List<Hotkey> all = store.all();
        for (int i = 0; i < all.size(); i++) {
            final Hotkey h = all.get(i);
            rows.add(new ScreenRow(List.of(
                    new ScreenItem("text").withText(h.label()).withWidth(90),
                    new ScreenItem("text").withText(h.line()).withWidth(220),
                    new ScreenItem("button").withId("edit/" + i).withText(I18n.get("mineflayer_bridge_hotkeys.edit.button")).withWidth(60))));
        }
        if (all.isEmpty()) {
            rows.add(new ScreenRow(List.of(new ScreenItem("text").withColor(UiColors.LABEL).withText(I18n.get("mineflayer_bridge_hotkeys.none")))));
        }
        rows.add(new ScreenRow(List.of(new ScreenItem("button").withId("add").withText(I18n.get("mineflayer_bridge_hotkeys.add"))
                .withIcon("minecraft:tripwire_hook").withWidth(160))));
        return rows;
    }

    /** Checks a hotkey before it is stored: its line must start with the chat prefix. */
    private @Nullable String save(final HotkeyStore store, final int index, final Hotkey h) {
        if (h.line().isEmpty()) {
            return I18n.get("mineflayer_bridge_hotkeys.need_line");
        }
        final ClientBridge b = bridge;
        final String prefix = b == null ? "@" : b.chatPrefix();
        if (!h.line().startsWith(prefix)) {
            return I18n.get("mineflayer_bridge_hotkeys.need_prefix", prefix);
        }
        store.put(index, h);
        return null;
    }

    /** Runs a hotkey's line through the chat bridge; a line the bridge would pass to the server is dropped. */
    private void run(final Hotkey h) {
        final ClientBridge b = bridge;
        if (b == null) {
            return;
        }
        if (!b.runChatLine(h.line())) {
            b.toastError(h.label(), I18n.get("mineflayer_bridge_hotkeys.nobody", h.line()));
        }
    }
}
