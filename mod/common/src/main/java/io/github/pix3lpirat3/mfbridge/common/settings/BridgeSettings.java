package io.github.pix3lpirat3.mfbridge.common.settings;

import java.util.Objects;

/**
 * The player's bridge settings. Immutable; the settings screen produces a new instance on save.
 *
 * @param bindAddress interface the endpoint listens on; not empty
 * @param port TCP port of the endpoint, {@code 1..65535}
 * @param attachPolicy which bots are attached automatically
 * @param showLabels whether labels and waypoint names are drawn
 * @param requireTlsOnLoopback whether local plugins must use TLS too; plain
 *     {@code ws://} on loopback is allowed by default
 * @param advertiseHost host put into pairing URIs; empty picks one from the network interfaces
 * @param sampleRateHz the most client samples per second any bot gets,
 *     {@code 1..20}
 * @param allowBotFocus whether bots may take focus with {@code focus.request}; on by
 *     default
 * @param showAllHud whether every bot's HUD panels and boss bars render in full, not only the
 *     focused bot's; off by default
 * @param askLocalApproval whether new local bots ask before they connect; off
 *     by default
 * @param botSoundVolume volume of every bot sound in percent, {@code 0..100}, on top of the vanilla
 *     category sliders
 * @param chatPrefix what starts a chat line for bots, {@code @} by default
 * @param commandName the client command's name, {@code bridge} by default; another name avoids a
 *     server command of the same name
 * @param joinTips when the short how-to shows in chat on joining a world
 * @param showAliases whether command completion offers aliases besides names; off by default
 * @param messagesInChat whether bot notifications and the mod's own messages go to chat rather than
 *     toasts; on by default. Notifications with buttons always go to chat.
 * @param awayMinutes minutes without input before bots hear the player is away, {@code 0..60}; 0 never
 */
public record BridgeSettings(String bindAddress, int port, AttachPolicy attachPolicy, boolean showLabels,
        boolean requireTlsOnLoopback, String advertiseHost, int sampleRateHz, boolean allowBotFocus, boolean showAllHud,
        boolean askLocalApproval, int botSoundVolume, String chatPrefix, String commandName, JoinTips joinTips, boolean showAliases,
        boolean messagesInChat, int awayMinutes) {

    /** Default chat prefix that addresses bots. */
    public static final String DEFAULT_CHAT_PREFIX = "@";

    /** Default name of the client command. */
    public static final String DEFAULT_COMMAND_NAME = "bridge";

    /** What a command name may be: lower-case letters, digits, {@code _} and {@code -}. */
    private static final java.util.regex.Pattern COMMAND_NAME = java.util.regex.Pattern.compile("[a-z0-9_-]{1,32}");

    /** Default and highest bot sound volume, percent. */
    public static final int MAX_BOT_SOUND_VOLUME = 100;

    /** Default minutes without input before the player counts as away. */
    public static final int DEFAULT_AWAY_MINUTES = 5;

    /** Most minutes the away time can be set to. */
    public static final int MAX_AWAY_MINUTES = 60;

    /** Default client sample cap in samples per second. */
    public static final int DEFAULT_SAMPLE_RATE_HZ = 4;

    /** Highest client sample cap in samples per second. */
    public static final int MAX_SAMPLE_RATE_HZ = 20;

    /** Lowest valid port. */
    public static final int MIN_PORT = 1;

    /** Highest valid port. */
    public static final int MAX_PORT = 65_535;

    /** Bind address that accepts loopback peers only. */
    public static final String LOCAL_ONLY = "127.0.0.1";

    /** Bind address that accepts every interface. */
    public static final String ALL_INTERFACES = "0.0.0.0";

    /**
     * The defaults: loopback only, port 25590, attach on world match, labels on,
     * plain loopback allowed, advertised host picked automatically.
     */
    public static final BridgeSettings DEFAULTS =
            new BridgeSettings(LOCAL_ONLY, 25_590, AttachPolicy.WORLD_MATCH, true, false, "", DEFAULT_SAMPLE_RATE_HZ, true, false, false,
                    MAX_BOT_SOUND_VOLUME, DEFAULT_CHAT_PREFIX, DEFAULT_COMMAND_NAME, JoinTips.FIRST_TIME, false, true, DEFAULT_AWAY_MINUTES);

    /**
     * Creates settings with the transport security defaults.
     *
     * @param bindAddress interface the endpoint listens on
     * @param port TCP port
     * @param attachPolicy auto-attach policy
     * @param showLabels whether labels are drawn
     */
    public BridgeSettings(String bindAddress, int port, AttachPolicy attachPolicy, boolean showLabels) {
        this(bindAddress, port, attachPolicy, showLabels, false, "", DEFAULT_SAMPLE_RATE_HZ, true, false, false);
    }

    /**
     * Creates settings with the default client sample cap.
     *
     * @param bindAddress interface the endpoint listens on
     * @param port TCP port
     * @param attachPolicy auto-attach policy
     * @param showLabels whether labels are drawn
     * @param requireTlsOnLoopback whether local plugins must use TLS too
     * @param advertiseHost host put into pairing URIs
     */
    public BridgeSettings(String bindAddress, int port, AttachPolicy attachPolicy, boolean showLabels,
            boolean requireTlsOnLoopback, String advertiseHost) {
        this(bindAddress, port, attachPolicy, showLabels, requireTlsOnLoopback, advertiseHost, DEFAULT_SAMPLE_RATE_HZ);
    }

    /**
     * Creates settings with the default focus, HUD and approval settings.
     *
     * @param bindAddress interface the endpoint listens on
     * @param port TCP port
     * @param attachPolicy auto-attach policy
     * @param showLabels whether labels are drawn
     * @param requireTlsOnLoopback whether local plugins must use TLS too
     * @param advertiseHost host put into pairing URIs
     * @param sampleRateHz client sample cap
     */
    public BridgeSettings(String bindAddress, int port, AttachPolicy attachPolicy, boolean showLabels,
            boolean requireTlsOnLoopback, String advertiseHost, int sampleRateHz) {
        this(bindAddress, port, attachPolicy, showLabels, requireTlsOnLoopback, advertiseHost, sampleRateHz, true, false, false);
    }

    /**
     * Validates the values.
     *
     * @throws IllegalArgumentException if the address is blank, or the port or sample rate is out of range
     */
    public BridgeSettings {
        Objects.requireNonNull(bindAddress, "bindAddress");
        Objects.requireNonNull(attachPolicy, "attachPolicy");
        Objects.requireNonNull(advertiseHost, "advertiseHost");
        advertiseHost = advertiseHost.strip();
        if (bindAddress.isBlank()) {
            throw new IllegalArgumentException("bind address must not be blank");
        }
        if (port < MIN_PORT || port > MAX_PORT) {
            throw new IllegalArgumentException("port must be in " + MIN_PORT + ".." + MAX_PORT + ": " + port);
        }
        if (sampleRateHz < 1 || sampleRateHz > MAX_SAMPLE_RATE_HZ) {
            throw new IllegalArgumentException("sample rate must be in 1.." + MAX_SAMPLE_RATE_HZ + ": " + sampleRateHz);
        }
        if (botSoundVolume < 0 || botSoundVolume > MAX_BOT_SOUND_VOLUME) {
            throw new IllegalArgumentException("bot sound volume must be in 0.." + MAX_BOT_SOUND_VOLUME + ": " + botSoundVolume);
        }
        if (awayMinutes < 0 || awayMinutes > MAX_AWAY_MINUTES) {
            throw new IllegalArgumentException("away minutes must be in 0.." + MAX_AWAY_MINUTES + ": " + awayMinutes);
        }
        Objects.requireNonNull(chatPrefix, "chatPrefix");
        Objects.requireNonNull(commandName, "commandName");
        Objects.requireNonNull(joinTips, "joinTips");
        chatPrefix = chatPrefix.strip();
        commandName = commandName.strip();
        if (!validChatPrefix(chatPrefix)) {
            throw new IllegalArgumentException("chat prefix must be 1 to 4 characters, without spaces or a leading /: " + chatPrefix);
        }
        if (!validCommandName(commandName)) {
            throw new IllegalArgumentException("command name must be 1 to 32 of a-z, 0-9, _ and -: " + commandName);
        }
    }

    /**
     * Returns whether a chat prefix is usable: 1 to 4 characters, no spaces, not starting with {@code /}.
     *
     * @param prefix the prefix
     * @return true when valid
     */
    public static boolean validChatPrefix(final String prefix) {
        return !prefix.isEmpty() && prefix.length() <= 4 && !prefix.startsWith("/") && prefix.chars().noneMatch(Character::isWhitespace);
    }

    /**
     * Returns whether a client command name is usable.
     *
     * @param name the name, without the slash
     * @return true when valid
     */
    public static boolean validCommandName(final String name) {
        return COMMAND_NAME.matcher(name).matches();
    }

    /**
     * Creates settings with the default chat prefix and command name.
     *
     * @param bindAddress interface the endpoint listens on
     * @param port TCP port
     * @param attachPolicy auto-attach policy
     * @param showLabels whether labels are drawn
     * @param requireTlsOnLoopback whether local plugins must use TLS too
     * @param advertiseHost host put into pairing URIs
     * @param sampleRateHz client sample cap
     * @param allowBotFocus whether bots may take focus
     * @param showAllHud whether every bot's HUD renders in full
     * @param askLocalApproval whether new local bots ask before they connect
     * @param botSoundVolume bot sound volume in percent
     */
    public BridgeSettings(String bindAddress, int port, AttachPolicy attachPolicy, boolean showLabels, boolean requireTlsOnLoopback,
            String advertiseHost, int sampleRateHz, boolean allowBotFocus, boolean showAllHud, boolean askLocalApproval, int botSoundVolume) {
        this(bindAddress, port, attachPolicy, showLabels, requireTlsOnLoopback, advertiseHost, sampleRateHz, allowBotFocus, showAllHud,
                askLocalApproval, botSoundVolume, DEFAULT_CHAT_PREFIX, DEFAULT_COMMAND_NAME, JoinTips.FIRST_TIME, false, true,
                DEFAULT_AWAY_MINUTES);
    }

    /**
     * Returns a copy with another chat prefix and command name.
     *
     * @param prefix the chat prefix
     * @param name the client command name
     * @return the copy
     * @throws IllegalArgumentException when either is not valid
     */
    public BridgeSettings withCommands(final String prefix, final String name) {
        return new BridgeSettings(bindAddress, port, attachPolicy, showLabels, requireTlsOnLoopback, advertiseHost, sampleRateHz, allowBotFocus,
                showAllHud, askLocalApproval, botSoundVolume, prefix, name, joinTips, showAliases, messagesInChat, awayMinutes);
    }

    /**
     * Returns a copy with another join tips choice.
     *
     * @param tips when the how-to shows
     * @return the copy
     */
    public BridgeSettings withJoinTips(final JoinTips tips) {
        return new BridgeSettings(bindAddress, port, attachPolicy, showLabels, requireTlsOnLoopback, advertiseHost, sampleRateHz, allowBotFocus,
                showAllHud, askLocalApproval, botSoundVolume, chatPrefix, commandName, tips, showAliases, messagesInChat, awayMinutes);
    }

    /**
     * Returns a copy with aliases offered in command completion or not.
     *
     * @param show whether completion offers aliases
     * @return the copy
     */
    public BridgeSettings withShowAliases(final boolean show) {
        return new BridgeSettings(bindAddress, port, attachPolicy, showLabels, requireTlsOnLoopback, advertiseHost, sampleRateHz, allowBotFocus,
                showAllHud, askLocalApproval, botSoundVolume, chatPrefix, commandName, joinTips, show, messagesInChat, awayMinutes);
    }

    /**
     * Creates settings with full bot sound volume.
     *
     * @param bindAddress interface the endpoint listens on
     * @param port TCP port
     * @param attachPolicy auto-attach policy
     * @param showLabels whether labels are drawn
     * @param requireTlsOnLoopback whether local plugins must use TLS too
     * @param advertiseHost host put into pairing URIs
     * @param sampleRateHz client sample cap
     * @param allowBotFocus whether bots may take focus
     * @param showAllHud whether every bot's HUD renders in full
     * @param askLocalApproval whether new local bots ask before they connect
     */
    public BridgeSettings(String bindAddress, int port, AttachPolicy attachPolicy, boolean showLabels, boolean requireTlsOnLoopback,
            String advertiseHost, int sampleRateHz, boolean allowBotFocus, boolean showAllHud, boolean askLocalApproval) {
        this(bindAddress, port, attachPolicy, showLabels, requireTlsOnLoopback, advertiseHost, sampleRateHz, allowBotFocus, showAllHud,
                askLocalApproval, MAX_BOT_SOUND_VOLUME);
    }

    /**
     * Returns a copy with another bot sound volume.
     *
     * @param percent volume in percent, {@code 0..100}
     * @return the copy
     */
    public BridgeSettings withBotSoundVolume(final int percent) {
        return new BridgeSettings(bindAddress, port, attachPolicy, showLabels, requireTlsOnLoopback, advertiseHost, sampleRateHz, allowBotFocus,
                showAllHud, askLocalApproval, percent, chatPrefix, commandName, joinTips, showAliases, messagesInChat, awayMinutes);
    }

    /**
     * Returns a copy with other message and away settings.
     *
     * @param inChat whether messages go to chat rather than toasts
     * @param minutes minutes without input before the player counts as away, {@code 0..60}
     * @return the copy
     */
    public BridgeSettings withMessages(final boolean inChat, final int minutes) {
        return new BridgeSettings(bindAddress, port, attachPolicy, showLabels, requireTlsOnLoopback, advertiseHost, sampleRateHz, allowBotFocus,
                showAllHud, askLocalApproval, botSoundVolume, chatPrefix, commandName, joinTips, showAliases, inChat, minutes);
    }

    /**
     * Returns whether the endpoint has to be restarted to apply {@code other}.
     *
     * @param other the new settings
     * @return true when the bind address, port or transport security differ
     */
    public boolean transportDiffers(final BridgeSettings other) {
        return !bindAddress.equals(other.bindAddress) || port != other.port
                || requireTlsOnLoopback != other.requireTlsOnLoopback || !advertiseHost.equals(other.advertiseHost);
    }
}
