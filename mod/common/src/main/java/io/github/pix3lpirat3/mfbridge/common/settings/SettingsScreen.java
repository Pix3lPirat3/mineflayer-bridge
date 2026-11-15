package io.github.pix3lpirat3.mfbridge.common.settings;

import io.github.pix3lpirat3.mfbridge.common.Constants;
import io.github.pix3lpirat3.mfbridge.common.ui.BridgeUi;
import io.github.pix3lpirat3.mfbridge.common.ui.logic.CommandArgs;
import io.github.pix3lpirat3.mfbridge.core.BridgeCore;
import io.github.pix3lpirat3.mfbridge.core.decl.InterceptDecl;
import io.github.pix3lpirat3.mfbridge.core.decl.SettingsDecl;
import io.github.pix3lpirat3.mfbridge.protocol.core.SettingDef;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import io.github.pix3lpirat3.mfbridge.common.ui.screen.RowScreen;
import io.github.pix3lpirat3.mfbridge.common.ui.screen.UiColors;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * Settings screen built from vanilla widgets: the mod's own settings, then one section per bot
 * settings group, shown under Bots, name. A later
 * milestone replaces it with the bridge UI toolkit; the stored format stays the same. The content
 * scrolls; changes apply when the player presses Done. Opened for one bot, it shows only that bot's
 * groups and leaves the mod settings alone.
 */
public final class SettingsScreen extends RowScreen {

    private static final int COLUMN_WIDTH = 260;
    private static final int LABEL_WIDTH = 110;

    /** Bind address presets: loopback by default, remote access is opt-in. */
    private enum BindPreset {
        LOCAL_ONLY("local_only"),
        LAN("lan"),
        CUSTOM("custom");

        final String key;

        BindPreset(final String key) {
            this.key = key;
        }

        static BindPreset of(final String address) {
            return switch (address) {
                case BridgeSettings.LOCAL_ONLY -> LOCAL_ONLY;
                case BridgeSettings.ALL_INTERFACES -> LAN;
                default -> CUSTOM;
            };
        }
    }

    private final Consumer<BridgeSettings> onSave;
    private final BridgeUi ui;
    private final @Nullable String owner;
    private final Map<String, Pending> botChanges = new LinkedHashMap<>();
    /** Pending intercept cancel permissions by bot identity, applied on Done. */
    private final Map<String, Boolean> interceptChanges = new LinkedHashMap<>();
    private final List<Swatch> swatches = new ArrayList<>();
    private String bindAddress;
    private BindPreset bindPreset;
    private boolean requireTls;
    private String advertiseHost;
    private String port;
    private AttachPolicy attachPolicy;
    private boolean showLabels;
    private int sampleRateHz;
    private int botSoundVolume;
    private String chatPrefix;
    private String commandName;
    private boolean allowBotFocus;
    private boolean showAllHud;
    private boolean askLocalApproval;
    private JoinTips joinTips;
    private boolean showAliases;
    private boolean messagesInChat;
    private int awayMinutes;

    private record Swatch(int x, int y, String key) {
    }

    /** A bot setting the player changed but has not applied yet. */
    private record Pending(String settingsKey, SettingDef def, Object raw) {
    }

    /**
     * Creates the screen.
     *
     * @param parent the screen to return to, or null to close to the game
     * @param current the settings shown initially
     * @param onSave called with validated settings when the player presses Done
     * @param ui the UI layer, which provides bot settings groups and applies their changes
     * @param owner an owner such as {@code bot:<identity>} to show only that bot's settings groups, or
     *     null for the mod settings and every bot
     */
    public SettingsScreen(final @Nullable Screen parent, final BridgeSettings current, final Consumer<BridgeSettings> onSave, final BridgeUi ui,
            final @Nullable String owner) {
        super(Component.translatable("screen." + Constants.MOD_ID + ".settings"), parent, COLUMN_WIDTH);
        this.onSave = onSave;
        this.ui = ui;
        this.owner = owner;
        this.bindAddress = current.bindAddress();
        this.bindPreset = BindPreset.of(current.bindAddress());
        this.requireTls = current.requireTlsOnLoopback();
        this.advertiseHost = current.advertiseHost();
        this.port = Integer.toString(current.port());
        this.attachPolicy = current.attachPolicy();
        this.showLabels = current.showLabels();
        this.sampleRateHz = current.sampleRateHz();
        this.botSoundVolume = current.botSoundVolume();
        this.chatPrefix = current.chatPrefix();
        this.commandName = current.commandName();
        this.allowBotFocus = current.allowBotFocus();
        this.showAllHud = current.showAllHud();
        this.askLocalApproval = current.askLocalApproval();
        this.joinTips = current.joinTips();
        this.showAliases = current.showAliases();
        this.messagesInChat = current.messagesInChat();
        this.awayMinutes = current.awayMinutes();
    }

    @Override
    protected void init() {
        swatches.clear();
        super.init();
    }

    @Override
    protected int rows(final int left, final int top) {
        final int widgetX = left + LABEL_WIDTH;
        final int widgetWidth = COLUMN_WIDTH - LABEL_WIDTH;
        final int y = owner == null ? modRows(left, top, widgetX, widgetWidth) : top;
        return botRows(left, y, widgetX, widgetWidth);
    }

    /** The mod's own settings: endpoint, attach policy, labels and the sample rate cap. */
    private int modRows(final int left, final int top, final int widgetX, final int widgetWidth) {
        int y = top;
        if (visible(y)) {
            addRenderableWidget(CycleButton.builder(SettingsScreen::presetName, bindPreset)
                    .withValues(List.of(BindPreset.values()))
                    .create(left, y, COLUMN_WIDTH, ROW_HEIGHT, label("bind_address"), (button, value) -> {
                        bindPreset = value;
                        bindAddress = switch (value) {
                            case LOCAL_ONLY -> BridgeSettings.LOCAL_ONLY;
                            case LAN -> BridgeSettings.ALL_INTERFACES;
                            case CUSTOM -> bindAddress;
                        };
                        rebuildWidgets();
                    }));
        }
        y += 24;
        if (bindPreset == BindPreset.CUSTOM) {
            if (visible(y)) {
                label(label("bind_address.custom"), left, y + 6, UiColors.LABEL);
                final EditBox address = new EditBox(font, widgetX, y, widgetWidth, ROW_HEIGHT, label("bind_address"));
                address.setMaxLength(253);
                address.setValue(bindAddress);
                address.setResponder(value -> bindAddress = value);
                addRenderableWidget(address);
            }
            y += 24;
        }
        if (bindPreset != BindPreset.LOCAL_ONLY) {
            if (visible(y)) {
                label(label("bind_address.warning"), left, y + 6, UiColors.WARNING);
            }
            y += 18;
        }
        if (visible(y)) {
            label(label("port"), left, y + 6, UiColors.LABEL);
            final EditBox portBox = new EditBox(font, widgetX, y, widgetWidth, ROW_HEIGHT, label("port"));
            portBox.setMaxLength(5);
            portBox.setValue(port);
            portBox.setResponder(value -> port = value);
            addRenderableWidget(portBox);
        }
        y += 24;
        if (visible(y)) {
            addRenderableWidget(CycleButton.onOffBuilder(requireTls)
                    .create(left, y, COLUMN_WIDTH, ROW_HEIGHT, label("require_tls"), (button, value) -> requireTls = value));
        }
        y += 24;
        if (visible(y)) {
            label(label("advertise_host"), left, y + 6, UiColors.LABEL);
            final EditBox host = new EditBox(font, widgetX, y, widgetWidth, ROW_HEIGHT, label("advertise_host"));
            host.setMaxLength(253);
            host.setValue(advertiseHost);
            host.setResponder(value -> advertiseHost = value);
            addRenderableWidget(host);
        }
        y += 24;
        if (visible(y)) {
            label(label("chat_prefix"), left, y + 6, UiColors.LABEL);
            final EditBox prefix = new EditBox(font, widgetX, y, widgetWidth, ROW_HEIGHT, label("chat_prefix"));
            prefix.setMaxLength(4);
            prefix.setValue(chatPrefix);
            prefix.setResponder(value -> chatPrefix = value);
            addRenderableWidget(prefix);
        }
        y += 24;
        if (visible(y)) {
            label(label("command_name"), left, y + 6, UiColors.LABEL);
            final EditBox name = new EditBox(font, widgetX, y, widgetWidth, ROW_HEIGHT, label("command_name"));
            name.setMaxLength(32);
            name.setValue(commandName);
            name.setResponder(value -> commandName = value);
            name.setTooltip(net.minecraft.client.gui.components.Tooltip.create(label("command_name.tooltip")));
            addRenderableWidget(name);
        }
        y += 24;
        if (visible(y)) {
            addRenderableWidget(CycleButton.onOffBuilder(askLocalApproval).create(left, y, COLUMN_WIDTH, ROW_HEIGHT,
                    label("ask_local_approval"), (button, value) -> askLocalApproval = value));
        }
        y += 24;
        if (visible(y)) {
            addRenderableWidget(Button.builder(label("devices"), button -> ui.openDevices(this))
                    .bounds(left, y, COLUMN_WIDTH, ROW_HEIGHT)
                    .build());
        }
        y += 24;
        if (visible(y)) {
            addRenderableWidget(CycleButton.builder(SettingsScreen::policyName, attachPolicy)
                    .withValues(List.of(AttachPolicy.values()))
                    .create(left, y, COLUMN_WIDTH, ROW_HEIGHT, label("attach_policy"), (button, value) -> attachPolicy = value));
        }
        y += 24;
        if (visible(y)) {
            addRenderableWidget(CycleButton.onOffBuilder(showLabels)
                    .create(left, y, COLUMN_WIDTH, ROW_HEIGHT, label("show_labels"), (button, value) -> showLabels = value));
        }
        y += 24;
        if (visible(y)) {
            addRenderableWidget(CycleButton.builder((JoinTips v) -> label("join_tips." + v.key()), joinTips)
                    .withValues(List.of(JoinTips.values()))
                    .create(left, y, COLUMN_WIDTH, ROW_HEIGHT, label("join_tips"), (button, value) -> joinTips = value));
        }
        y += 24;
        if (visible(y)) {
            addRenderableWidget(CycleButton.builder((Boolean v) -> label(v ? "messages.chat" : "messages.toasts"), messagesInChat)
                    .withValues(List.of(true, false))
                    .create(left, y, COLUMN_WIDTH, ROW_HEIGHT, label("messages"), (button, value) -> messagesInChat = value));
        }
        y += 24;
        if (visible(y)) {
            final List<Integer> minutes = new ArrayList<>(List.of(0, 1, 2, 5, 10, 15, 30, BridgeSettings.MAX_AWAY_MINUTES));
            if (!minutes.contains(awayMinutes)) {
                minutes.add(awayMinutes);
                minutes.sort(null);
            }
            addRenderableWidget(CycleButton.builder((Integer v) -> v == 0
                            ? Component.translatable("screen.mineflayer_bridge.settings.away_minutes.never")
                            : Component.translatable("screen.mineflayer_bridge.settings.away_minutes.value", v), awayMinutes)
                    .withValues(minutes)
                    .create(left, y, COLUMN_WIDTH, ROW_HEIGHT, label("away_minutes"), (button, value) -> awayMinutes = value));
        }
        y += 24;
        if (visible(y)) {
            addRenderableWidget(CycleButton.onOffBuilder(showAliases).create(left, y, COLUMN_WIDTH, ROW_HEIGHT,
                    label("show_aliases"), (button, value) -> showAliases = value));
        }
        y += 24;
        if (visible(y)) {
            addRenderableWidget(CycleButton.onOffBuilder(allowBotFocus).create(left, y, COLUMN_WIDTH, ROW_HEIGHT,
                    label("allow_bot_focus"), (button, value) -> allowBotFocus = value));
        }
        y += 24;
        if (visible(y)) {
            addRenderableWidget(CycleButton.onOffBuilder(showAllHud).create(left, y, COLUMN_WIDTH, ROW_HEIGHT,
                    label("show_all_hud"), (button, value) -> showAllHud = value));
        }
        y += 24;
        if (visible(y)) {
            // The player's cap; bots also need the client.sample permissions.
            final List<Integer> rates = new ArrayList<>(List.of(1, 2, 4, 8, 10, 15, BridgeSettings.MAX_SAMPLE_RATE_HZ));
            if (!rates.contains(sampleRateHz)) {
                rates.add(sampleRateHz);
                rates.sort(null);
            }
            addRenderableWidget(CycleButton.builder(
                            (Integer v) -> Component.translatable("screen.mineflayer_bridge.settings.sample_rate.value", v), sampleRateHz)
                    .withValues(rates)
                    .create(left, y, COLUMN_WIDTH, ROW_HEIGHT, label("sample_rate"), (button, value) -> sampleRateHz = value));
        }
        y += 24;
        if (visible(y)) {
            // One scale for every bot sound, on top of the vanilla sliders.
            final List<Integer> volumes = new ArrayList<>();
            for (int v = 0; v <= BridgeSettings.MAX_BOT_SOUND_VOLUME; v += 10) {
                volumes.add(v);
            }
            if (!volumes.contains(botSoundVolume)) {
                volumes.add(botSoundVolume);
                volumes.sort(null);
            }
            addRenderableWidget(CycleButton.builder((Integer v) -> v == 0
                    ? Component.translatable("screen.mineflayer_bridge.settings.bot_sound_volume.off")
                    : Component.translatable("screen.mineflayer_bridge.settings.bot_sound_volume.value", v), botSoundVolume)
                    .withValues(volumes)
                    .create(left, y, COLUMN_WIDTH, ROW_HEIGHT, label("bot_sound_volume"), (button, value) -> botSoundVolume = value));
        }
        return y + 30;
    }

    /** One section per bot settings group, then the intercept permissions. */
    private int botRows(final int left, final int top, final int widgetX, final int widgetWidth) {
        int y = top;
        for (final SettingsDecl group : groups()) {
            if (visible(y)) {
                final String title = group.data().title() != null ? group.data().title() : localId(group.key());
                label(Component.translatable("screen.mineflayer_bridge.settings.bot_group", group.ownerName(), title), left, y + 8, UiColors.HEADER);
            }
            y += 22;
            for (final SettingDef def : group.data().settings()) {
                if (visible(y)) {
                    label(Component.literal(def.label() != null ? def.label() : def.key()), left, y + 6, UiColors.LABEL);
                    addBotWidget(group, def, widgetX, y, widgetWidth);
                }
                y += 24;
            }
            y += 6;
        }
        if (owner != null && groups().isEmpty() && visible(y)) {
            label(Component.translatable("screen.mineflayer_bridge.settings.no_bot_settings"), left, y + 6, UiColors.LABEL);
            y += ROW_STEP;
        }
        return interceptToggles(left, y);
    }

    private List<SettingsDecl> groups() {
        final List<SettingsDecl> all = ui.botSettings();
        if (owner == null) {
            return all;
        }
        final List<SettingsDecl> mine = new ArrayList<>();
        for (final SettingsDecl group : all) {
            if (group.key().startsWith(owner + "/")) {
                mine.add(group);
            }
        }
        return mine;
    }

    @Override
    protected void footer(final int y) {
        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, button -> save())
                .bounds(width / 2 - 102, y, 100, ROW_HEIGHT)
                .build());
        addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, button -> onClose())
                .bounds(width / 2 + 2, y, 100, ROW_HEIGHT)
                .build());
    }

    /** One toggle per bot that declares a cancel intercept: may its rules cancel interactions. */
    private int interceptToggles(final int left, final int top) {
        final BridgeCore core = ui.core();
        if (core == null) {
            return top;
        }
        final Map<String, String> bots = new LinkedHashMap<>();
        for (final InterceptDecl d : core.declarations().intercepts()) {
            final String identity = d.ownerIdentity();
            if (identity != null && d.wantsCancel() && (owner == null || owner.equals("bot:" + identity))) {
                bots.putIfAbsent(identity, d.ownerName());
            }
        }
        int y = top;
        for (final Map.Entry<String, String> bot : bots.entrySet()) {
            final String identity = bot.getKey();
            if (visible(y)) {
                final Boolean pending = interceptChanges.get(identity);
                final boolean value = pending != null ? pending : core.intercepts().mayCancel(identity);
                addRenderableWidget(CycleButton.onOffBuilder(value).create(left, y, COLUMN_WIDTH, ROW_HEIGHT,
                        Component.translatable("screen.mineflayer_bridge.settings.intercept_cancel", bot.getValue()),
                        (button, v) -> interceptChanges.put(identity, v)));
            }
            y += 24;
        }
        return y;
    }

    private static String localId(final String key) {
        final int slash = key.lastIndexOf('/');
        return slash < 0 ? key : key.substring(slash + 1);
    }

    private static String id(final SettingsDecl group, final SettingDef def) {
        return group.key() + '\n' + def.key();
    }

    private @Nullable Object currentRaw(final SettingsDecl group, final SettingDef def) {
        final Pending pending = botChanges.get(id(group, def));
        if (pending != null) {
            return pending.raw();
        }
        return group.values().get(def.key());
    }

    private void addBotWidget(final SettingsDecl group, final SettingDef def, final int x, final int y, final int w) {
        final String id = id(group, def);
        final Object current = currentRaw(group, def);
        final Component name = Component.literal(def.label() != null ? def.label() : def.key());
        final String type = def.type().toLowerCase(Locale.ROOT);
        switch (type) {
            case "bool", "boolean" -> addRenderableWidget(CycleButton.onOffBuilder(Boolean.TRUE.equals(current))
                    .displayOnlyValue()
                    .create(x, y, w, ROW_HEIGHT, name, (b, v) -> botChanges.put(id, new Pending(group.key(), def, v))));
            case "choice", "enum" -> {
                final List<String> options = def.options() == null || def.options().isEmpty()
                        ? List.of(String.valueOf(current)) : def.options();
                final String value = options.contains(String.valueOf(current)) ? String.valueOf(current) : options.getFirst();
                addRenderableWidget(CycleButton.builder((String s) -> Component.literal(s), value)
                        .withValues(options)
                        .displayOnlyValue()
                        .create(x, y, w, ROW_HEIGHT, name, (b, v) -> botChanges.put(id, new Pending(group.key(), def, v))));
            }
            default -> {
                final boolean color = type.equals("color") || type.equals("colour");
                final int boxWidth = color ? w - 24 : w;
                final EditBox box = new EditBox(font, x, y, boxWidth, ROW_HEIGHT, name);
                box.setMaxLength(1024);
                box.setValue(text(current, color));
                box.setResponder(v -> botChanges.put(id, new Pending(group.key(), def, v)));
                addRenderableWidget(box);
                if (color) {
                    swatches.add(new Swatch(x + w - 20, y, id));
                }
            }
        }
    }

    private static String text(final @Nullable Object value, final boolean color) {
        if (value == null) {
            return "";
        }
        if (color && value instanceof Number n) {
            return colorText(n.longValue());
        }
        if (value instanceof Number n) {
            return CommandArgs.plain(n.doubleValue());
        }
        return value.toString();
    }

    /** Packed 0xAARRGGBB on the wire; shown as #rrggbb, or #rrggbbaa when not opaque. */
    private static String colorText(final long argb) {
        final long alpha = (argb >>> 24) & 0xFF;
        final long rgb = argb & 0xFFFFFF;
        return alpha == 0xFF ? String.format(Locale.ROOT, "#%06x", rgb) : String.format(Locale.ROOT, "#%06x%02x", rgb, alpha);
    }

    private int swatchColor(final String id) {
        final Pending pending = botChanges.get(id);
        Object value = pending != null ? pending.raw() : null;
        if (value == null) {
            for (final SettingsDecl group : groups()) {
                for (final SettingDef def : group.data().settings()) {
                    if (id(group, def).equals(id)) {
                        value = group.values().get(def.key());
                    }
                }
            }
        }
        if (value instanceof Number n) {
            return (int) n.longValue();
        }
        if (value instanceof String s && s.startsWith("#") && (s.length() == 7 || s.length() == 9)) {
            try {
                final int rgb = Integer.parseInt(s.substring(1, 7), 16);
                final int alpha = s.length() == 9 ? Integer.parseInt(s.substring(7, 9), 16) : 0xFF;
                return (alpha << 24) | rgb;
            } catch (final NumberFormatException e) {
                return 0;
            }
        }
        return 0;
    }

    @Override
    public void extractRenderState(final GuiGraphicsExtractor graphics, final int mouseX, final int mouseY, final float a) {
        super.extractRenderState(graphics, mouseX, mouseY, a);
        for (final Swatch swatch : swatches) {
            graphics.fill(swatch.x(), swatch.y(), swatch.x() + 20, swatch.y() + ROW_HEIGHT, 0xFF000000);
            graphics.fill(swatch.x() + 1, swatch.y() + 1, swatch.x() + 19, swatch.y() + ROW_HEIGHT - 1, swatchColor(swatch.key()));
        }
    }

    private void save() {
        final String address = bindAddress.trim();
        final int parsedPort;
        try {
            parsedPort = Integer.parseInt(port.trim());
        } catch (final NumberFormatException e) {
            status(Component.translatable("screen." + Constants.MOD_ID + ".settings.error.port"), UiColors.ERROR);
            return;
        }
        if (parsedPort < BridgeSettings.MIN_PORT || parsedPort > BridgeSettings.MAX_PORT) {
            status(Component.translatable("screen." + Constants.MOD_ID + ".settings.error.port"), UiColors.ERROR);
            return;
        }
        if (address.isEmpty()) {
            status(Component.translatable("screen." + Constants.MOD_ID + ".settings.error.bind_address"), UiColors.ERROR);
            return;
        }
        if (!BridgeSettings.validChatPrefix(chatPrefix.strip())) {
            status(label("error.chat_prefix"), UiColors.ERROR);
            return;
        }
        if (!BridgeSettings.validCommandName(commandName.strip())) {
            status(label("error.command_name"), UiColors.ERROR);
            return;
        }
        final String botProblem = applyBotChanges();
        if (botProblem != null) {
            status(Component.literal(botProblem), UiColors.ERROR);
            return;
        }
        final BridgeCore core = ui.core();
        if (core != null) {
            for (final Map.Entry<String, Boolean> e : interceptChanges.entrySet()) {
                core.intercepts().setCancelPermission(e.getKey(), e.getValue());
            }
        }
        interceptChanges.clear();
        if (owner != null) {
            onClose();
            return;
        }
        onSave.accept(new BridgeSettings(address, parsedPort, attachPolicy, showLabels, requireTls, advertiseHost, sampleRateHz,
                allowBotFocus, showAllHud, askLocalApproval).withBotSoundVolume(botSoundVolume).withCommands(chatPrefix, commandName)
                .withJoinTips(joinTips).withShowAliases(showAliases).withMessages(messagesInChat, awayMinutes));
        onClose();
    }

    /** Sends each changed bot setting; stops at the first rejected value and keeps the rest pending. */
    private @Nullable String applyBotChanges() {
        for (final var it = botChanges.entrySet().iterator(); it.hasNext();) {
            final Pending p = it.next().getValue();
            final String name = "'" + (p.def().label() != null ? p.def().label() : p.def().key()) + "'";
            final Object value;
            try {
                value = convert(p.def(), p.raw(), name);
                ui.setBotSetting(p.settingsKey(), p.def().key(), value);
            } catch (final IllegalArgumentException | IllegalStateException e) {
                return e.getMessage();
            }
            it.remove();
        }
        return null;
    }

    private static Object convert(final SettingDef def, final Object raw, final String name) {
        if (!(raw instanceof String s)) {
            return raw;
        }
        final String text = s.strip();
        return switch (def.type().toLowerCase(Locale.ROOT)) {
            case "int", "integer" -> {
                try {
                    yield Long.parseLong(text);
                } catch (final NumberFormatException e) {
                    throw new IllegalArgumentException(I18n.get("screen.mineflayer_bridge.settings.error.whole_number", name), e);
                }
            }
            case "float", "number", "double" -> {
                try {
                    yield Double.parseDouble(text);
                } catch (final NumberFormatException e) {
                    throw new IllegalArgumentException(I18n.get("screen.mineflayer_bridge.settings.error.number", name), e);
                }
            }
            default -> text;
        };
    }

    private static Component label(final String key) {
        return Component.translatable("screen." + Constants.MOD_ID + ".settings." + key);
    }

    private static Component presetName(final BindPreset preset) {
        return Component.translatable("screen." + Constants.MOD_ID + ".settings.bind_address." + preset.key);
    }

    private static Component policyName(final AttachPolicy policy) {
        return Component.translatable("screen." + Constants.MOD_ID + ".settings.attach_policy." + policy.key());
    }
}
