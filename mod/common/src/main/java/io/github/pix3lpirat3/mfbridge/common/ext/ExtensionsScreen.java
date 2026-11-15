package io.github.pix3lpirat3.mfbridge.common.ext;

import io.github.pix3lpirat3.mfbridge.common.ui.BridgeUi;
import io.github.pix3lpirat3.mfbridge.api.Setting;
import io.github.pix3lpirat3.mfbridge.api.SettingValue;
import io.github.pix3lpirat3.mfbridge.common.ui.declared.ScreenView;
import io.github.pix3lpirat3.mfbridge.common.ui.screen.UiColors;
import io.github.pix3lpirat3.mfbridge.core.BridgeCore;
import io.github.pix3lpirat3.mfbridge.core.ext.ExtensionResolver;
import io.github.pix3lpirat3.mfbridge.protocol.core.ScreenData;
import io.github.pix3lpirat3.mfbridge.protocol.core.ScreenItem;
import io.github.pix3lpirat3.mfbridge.protocol.core.ScreenRow;
import io.github.pix3lpirat3.mfbridge.protocol.core.ScreenTab;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * Tools > Extensions: every discovered mod extension with its version, whether
 * it loaded and why not, a switch that applies after the next restart of the game, and the settings
 * it registered, which apply and save at once.
 */
public final class ExtensionsScreen extends ScreenView {

    private static final String SWITCH = "switch:";
    private static final String SETTING = "setting:";

    private final ClientExtensions extensions;

    /**
     * Creates the screen.
     *
     * @param parent the screen to return to
     * @param extensions the client's extensions
     */
    public ExtensionsScreen(final @Nullable Screen parent, final ClientExtensions extensions) {
        super(Component.translatable("screen.mineflayer_bridge.extensions.title"), parent);
        this.extensions = extensions;
    }

    @Override
    protected ScreenData model() {
        final List<ScreenRow> rows = new ArrayList<>();
        final List<ExtensionResolver.Status> statuses = extensions.statuses();
        if (statuses.isEmpty()) {
            rows.add(row(new ScreenItem("text").withText(I18n.get("screen.mineflayer_bridge.extensions.empty")).withColor(UiColors.LABEL)));
        }
        for (final ExtensionResolver.Status s : statuses) {
            rows.add(row(new ScreenItem("heading").withText(s.id() + "  " + s.version())));
            final boolean loaded = s.state() == ExtensionResolver.State.LOADED;
            final String state = loaded ? "§a" + I18n.get("screen.mineflayer_bridge.extensions.state.loaded")
                    : "§e" + I18n.get(stateKey(s.state())) + "§7: " + s.reason();
            final boolean off = extensions.isDisabled(s.id());
            rows.add(row(new ScreenItem("text").withText(state).withColor(UiColors.LABEL),
                    new ScreenItem("toggle").withId(SWITCH + s.id()).withText(I18n.get("screen.mineflayer_bridge.extensions.enabled"))
                            .withValue(!off).withWidth(100)
                            .withEnabled(s.state() != ExtensionResolver.State.DUPLICATE)));
            if (loaded) {
                settingRows(s.id(), rows);
            }
        }
        rows.add(row(new ScreenItem("spacer")));
        rows.add(row(new ScreenItem("text").withText(I18n.get("screen.mineflayer_bridge.extensions.restart"))
                .withColor(UiColors.LABEL)));
        final String title = I18n.get("screen.mineflayer_bridge.extensions.title");
        return new ScreenData(title, List.of(new ScreenTab("extensions", title, rows)));
    }

    /** One row per setting the extension registered: a switch for booleans, a button that edits the rest. */
    private void settingRows(final String extensionId, final List<ScreenRow> rows) {
        final BridgeCore core = core();
        if (core == null) {
            return;
        }
        for (final SettingValue<?> v : core.extensionSettings(extensionId)) {
            final Setting<?> setting = v.setting();
            final String label = setting.description().isEmpty() ? setting.key() : setting.description();
            final String id = SETTING + extensionId + '/' + setting.key();
            if (setting.type() == Boolean.class) {
                rows.add(row(new ScreenItem("toggle").withId(id).withText(label).withValue(Boolean.TRUE.equals(v.get()))));
            } else {
                rows.add(row(new ScreenItem("button").withId(id).withText(label + ": " + v.get())
                        .withTooltip(I18n.get("screen.mineflayer_bridge.extensions.setting.edit"))));
            }
        }
    }

    private static String stateKey(final ExtensionResolver.State state) {
        return switch (state) {
            case LOADED -> "screen.mineflayer_bridge.extensions.state.loaded";
            case DISABLED -> "screen.mineflayer_bridge.extensions.state.disabled";
            case MISSING_DEPENDENCY -> "screen.mineflayer_bridge.extensions.state.missing_dependency";
            case CYCLE -> "screen.mineflayer_bridge.extensions.state.cycle";
            case DUPLICATE -> "screen.mineflayer_bridge.extensions.state.duplicate";
        };
    }

    private static ScreenRow row(final ScreenItem... items) {
        return new ScreenRow(List.of(items));
    }

    @Override
    protected void use(final ScreenItem item, final @Nullable Object value) {
        final String id = item.id();
        if (id != null && id.startsWith(SWITCH) && value instanceof Boolean on) {
            extensions.setDisabled(id.substring(SWITCH.length()), !on);
            invalidate();
        } else if (id != null && id.startsWith(SETTING)) {
            editSetting(id.substring(SETTING.length()), value);
        }
    }

    @SuppressWarnings("unchecked")
    private void editSetting(final String qualified, final @Nullable Object value) {
        final BridgeCore core = core();
        final int slash = qualified.lastIndexOf('/');
        if (core == null || slash < 0) {
            return;
        }
        final String key = qualified.substring(slash + 1);
        for (final SettingValue<?> v : core.extensionSettings(qualified.substring(0, slash))) {
            if (!v.setting().key().equals(key)) {
                continue;
            }
            if (v.setting().type() == Boolean.class && value instanceof Boolean on) {
                ((SettingValue<Boolean>) v).set(on);
                invalidate();
            } else if (v.setting().type() != Boolean.class) {
                minecraft.gui.setScreen(new SettingValueScreen(this, v));
            }
            return;
        }
    }

    /** This screen is the mod's own, so it reads the core itself; extensions only get the bridge view. */
    private static @Nullable BridgeCore core() {
        final BridgeUi ui = BridgeUi.current();
        return ui == null ? null : ui.core();
    }
}
