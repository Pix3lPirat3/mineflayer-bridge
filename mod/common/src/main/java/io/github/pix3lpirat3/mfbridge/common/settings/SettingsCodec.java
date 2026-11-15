package io.github.pix3lpirat3.mfbridge.common.settings;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads and writes {@code settings.json5}. Free of Minecraft types, so it is unit tested directly.
 *
 * <p>The writer emits a JSON5 subset: a flat object with quoted dotted keys, one {@code //} comment per
 * setting with its description and default, and only the values that differ from the defaults.
 * Gson's lenient reader accepts that subset plus hand edits such as unquoted keys and
 * single-quoted strings; trailing commas in objects are rejected.
 */
public final class SettingsCodec {

    /** Key of {@link BridgeSettings#bindAddress()}. */
    public static final String BIND_ADDRESS = "transport.bind_address";

    /** Key of {@link BridgeSettings#port()}. */
    public static final String PORT = "transport.port";

    /** Key of {@link BridgeSettings#attachPolicy()}. */
    public static final String ATTACH_POLICY = "attach.policy";

    /** Key of {@link BridgeSettings#requireTlsOnLoopback()}. */
    public static final String REQUIRE_TLS_LOOPBACK = "transport.require_tls_loopback";

    /** Key of {@link BridgeSettings#advertiseHost()}. */
    public static final String ADVERTISE_HOST = "transport.advertise_host";

    /** Key of {@link BridgeSettings#botSoundVolume()}. */
    public static final String BOT_SOUND_VOLUME = "sounds.bot_volume";

    /** Key of {@link BridgeSettings#sampleRateHz()}. */
    public static final String SAMPLE_RATE = "client_samples.max_rate_hz";

    /** Key of {@link BridgeSettings#allowBotFocus()}. */
    public static final String ALLOW_BOT_FOCUS = "focus.allow_bot_requests";

    /** Key of {@link BridgeSettings#showAllHud()}. */
    public static final String SHOW_ALL_HUD = "hud.show_all";

    /** Key of {@link BridgeSettings#askLocalApproval()}. */
    public static final String ASK_LOCAL = "transport.ask_local_approval";

    /** Key of {@link BridgeSettings#chatPrefix()}. */
    public static final String CHAT_PREFIX = "chat.prefix";

    /** Key of {@link BridgeSettings#commandName()}. */
    public static final String COMMAND_NAME = "commands.name";

    /** Key of {@link BridgeSettings#showAliases()}. */
    public static final String SHOW_ALIASES = "commands.show_aliases";

    /** Key of {@link BridgeSettings#messagesInChat()}. */
    public static final String MESSAGES_IN_CHAT = "ui.messages_in_chat";

    /** Key of {@link BridgeSettings#awayMinutes()}. */
    public static final String AWAY_MINUTES = "player.away_minutes";

    /** Key of {@link BridgeSettings#joinTips()}. */
    public static final String JOIN_TIPS = "ui.join_tips";

    /** Key of {@link BridgeSettings#showLabels()}. */
    public static final String SHOW_LABELS = "render.show_labels";

    private SettingsCodec() {
    }

    /**
     * Parses settings text. Unknown keys are ignored so newer files load in older versions.
     *
     * @param text the file contents
     * @return the settings, with defaults for missing keys
     * @throws IllegalArgumentException if the text is not a JSON5 object or a known key has a bad value
     */
    public static BridgeSettings decode(final String text) {
        final JsonElement root;
        try (JsonReader reader = new JsonReader(new StringReader(text))) {
            reader.setStrictness(Strictness.LENIENT);
            root = JsonParser.parseReader(reader);
        } catch (final IOException | JsonParseException e) {
            throw new IllegalArgumentException("not valid JSON5: " + e.getMessage(), e);
        }
        if (root.isJsonNull()) {
            return BridgeSettings.DEFAULTS;
        }
        if (!root.isJsonObject()) {
            throw new IllegalArgumentException("top level must be an object");
        }
        final JsonObject object = root.getAsJsonObject();
        final BridgeSettings d = BridgeSettings.DEFAULTS;
        final String bindAddress = string(object, BIND_ADDRESS, d.bindAddress());
        final int port = integer(object, PORT, d.port());
        final String policyKey = string(object, ATTACH_POLICY, d.attachPolicy().key());
        final AttachPolicy policy = AttachPolicy.fromKey(policyKey)
                .orElseThrow(() -> new IllegalArgumentException(ATTACH_POLICY + " has unknown value '" + policyKey + "'"));
        final boolean showLabels = bool(object, SHOW_LABELS, d.showLabels());
        final boolean requireTls = bool(object, REQUIRE_TLS_LOOPBACK, d.requireTlsOnLoopback());
        final String advertiseHost = string(object, ADVERTISE_HOST, d.advertiseHost());
        final int sampleRate = integer(object, SAMPLE_RATE, d.sampleRateHz());
        return new BridgeSettings(bindAddress, port, policy, showLabels, requireTls, advertiseHost, sampleRate,
                bool(object, ALLOW_BOT_FOCUS, d.allowBotFocus()), bool(object, SHOW_ALL_HUD, d.showAllHud()),
                bool(object, ASK_LOCAL, d.askLocalApproval()))
                .withBotSoundVolume(integer(object, BOT_SOUND_VOLUME, d.botSoundVolume()))
                .withCommands(string(object, CHAT_PREFIX, d.chatPrefix()), string(object, COMMAND_NAME, d.commandName()))
                .withJoinTips(joinTips(string(object, JOIN_TIPS, d.joinTips().key())))
                .withShowAliases(bool(object, SHOW_ALIASES, d.showAliases()))
                .withMessages(bool(object, MESSAGES_IN_CHAT, d.messagesInChat()), integer(object, AWAY_MINUTES, d.awayMinutes()));
    }

    /**
     * Formats settings as file text.
     *
     * @param settings the settings to write
     * @return JSON5 text ending with a newline
     */
    public static String encode(final BridgeSettings settings) {
        final BridgeSettings d = BridgeSettings.DEFAULTS;
        final List<Entry> entries = new ArrayList<>();
        entries.add(new Entry(BIND_ADDRESS, "Interface the bridge endpoint listens on. Use 0.0.0.0 for remote bots.",
                new JsonPrimitive(d.bindAddress()), new JsonPrimitive(settings.bindAddress())));
        entries.add(new Entry(PORT, "TCP port of the bridge endpoint.",
                new JsonPrimitive(d.port()), new JsonPrimitive(settings.port())));
        entries.add(new Entry(REQUIRE_TLS_LOOPBACK, "Require TLS from local plugins too; remote plugins always use TLS.",
                new JsonPrimitive(d.requireTlsOnLoopback()), new JsonPrimitive(settings.requireTlsOnLoopback())));
        entries.add(new Entry(ADVERTISE_HOST, "Host written into pairing links. Empty picks a LAN address.",
                new JsonPrimitive(d.advertiseHost()), new JsonPrimitive(settings.advertiseHost())));
        entries.add(new Entry(ATTACH_POLICY, "Bots attached automatically: world_match, always_paired or manual.",
                new JsonPrimitive(d.attachPolicy().key()), new JsonPrimitive(settings.attachPolicy().key())));
        entries.add(new Entry(SHOW_LABELS, "Draw labels and waypoint names.",
                new JsonPrimitive(d.showLabels()), new JsonPrimitive(settings.showLabels())));
        entries.add(new Entry(ASK_LOCAL, "Ask before allowing new local bots; off approves them automatically.",
                new JsonPrimitive(d.askLocalApproval()), new JsonPrimitive(settings.askLocalApproval())));
        entries.add(new Entry(ALLOW_BOT_FOCUS, "Let bots take focus when they ask, at most once per 5 seconds each.",
                new JsonPrimitive(d.allowBotFocus()), new JsonPrimitive(settings.allowBotFocus())));
        entries.add(new Entry(SHOW_ALL_HUD, "Show every bot's HUD panels and boss bars in full, not only the focused bot's.",
                new JsonPrimitive(d.showAllHud()), new JsonPrimitive(settings.showAllHud())));
        entries.add(new Entry(SAMPLE_RATE, "Most client samples per second a permitted bot gets, 1 to 20.",
                new JsonPrimitive(d.sampleRateHz()), new JsonPrimitive(settings.sampleRateHz())));
        entries.add(new Entry(BOT_SOUND_VOLUME, "Volume of every bot sound in percent, 0 to 100, on top of the game's sound sliders.",
                new JsonPrimitive(d.botSoundVolume()), new JsonPrimitive(settings.botSoundVolume())));
        entries.add(new Entry(CHAT_PREFIX, "What starts a chat line for bots, such as @miner come. 1 to 4 characters.",
                new JsonPrimitive(d.chatPrefix()), new JsonPrimitive(settings.chatPrefix())));
        entries.add(new Entry(JOIN_TIPS, "When the short how-to shows in chat on joining a world: first_time, every_join or off.",
                new JsonPrimitive(d.joinTips().key()), new JsonPrimitive(settings.joinTips().key())));
        entries.add(new Entry(MESSAGES_IN_CHAT, "Show bot notifications and the mod's own messages in chat; off shows them as toasts.",
                new JsonPrimitive(d.messagesInChat()), new JsonPrimitive(settings.messagesInChat())));
        entries.add(new Entry(AWAY_MINUTES, "Minutes without input before bots hear you are away, 0 to 60; 0 never.",
                new JsonPrimitive(d.awayMinutes()), new JsonPrimitive(settings.awayMinutes())));
        entries.add(new Entry(SHOW_ALIASES, "Offer command aliases (such as wp for waypoint) in completion, besides the names. Typed aliases work either way.",
                new JsonPrimitive(d.showAliases()), new JsonPrimitive(settings.showAliases())));
        entries.add(new Entry(COMMAND_NAME, "Name of the client command, /bridge by default. Change it when a server has its own /bridge.",
                new JsonPrimitive(d.commandName()), new JsonPrimitive(settings.commandName())));

        final StringBuilder out = new StringBuilder(512);
        out.append("// Mineflayer Bridge settings. Only values that differ from the defaults are written.\n");
        out.append("{\n");
        final List<Entry> changed = new ArrayList<>();
        for (final Entry entry : entries) {
            if (!entry.value().equals(entry.defaultValue())) {
                changed.add(entry);
            }
        }
        for (final Entry entry : entries) {
            out.append("  // ").append(entry.key()).append(": ").append(entry.description())
                    .append(" Default: ").append(entry.defaultValue()).append('\n');
        }
        for (int i = 0; i < changed.size(); i++) {
            final Entry entry = changed.get(i);
            out.append("  ").append(new JsonPrimitive(entry.key())).append(": ").append(entry.value());
            out.append(i + 1 < changed.size() ? ",\n" : "\n");
        }
        out.append("}\n");
        return out.toString();
    }

    private static String string(final JsonObject object, final String key, final String fallback) {
        final JsonElement value = object.get(key);
        if (value == null) {
            return fallback;
        }
        if (!(value instanceof JsonPrimitive primitive) || !primitive.isString()) {
            throw new IllegalArgumentException(key + " must be a string");
        }
        return primitive.getAsString();
    }

    private static int integer(final JsonObject object, final String key, final int fallback) {
        final JsonElement value = object.get(key);
        if (value == null) {
            return fallback;
        }
        if (!(value instanceof JsonPrimitive primitive) || !primitive.isNumber()) {
            throw new IllegalArgumentException(key + " must be a number");
        }
        final double number = primitive.getAsDouble();
        if (number != Math.rint(number) || number < Integer.MIN_VALUE || number > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(key + " must be an integer");
        }
        return (int) number;
    }

    private static boolean bool(final JsonObject object, final String key, final boolean fallback) {
        final JsonElement value = object.get(key);
        if (value == null) {
            return fallback;
        }
        if (!(value instanceof JsonPrimitive primitive) || !primitive.isBoolean()) {
            throw new IllegalArgumentException(key + " must be true or false");
        }
        return primitive.getAsBoolean();
    }

    private record Entry(String key, String description, JsonPrimitive defaultValue, JsonPrimitive value) {
    }

    private static JoinTips joinTips(final String key) {
        return JoinTips.fromKey(key).orElseThrow(() -> new IllegalArgumentException(JOIN_TIPS + " has unknown value '" + key + "'"));
    }
}
