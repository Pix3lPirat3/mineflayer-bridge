package io.github.pix3lpirat3.mfbridge.common.ext.console;

import io.github.pix3lpirat3.mfbridge.api.ExtensionId;
import io.github.pix3lpirat3.mfbridge.api.ExtensionRegistrar;
import io.github.pix3lpirat3.mfbridge.api.MessageAccess;
import io.github.pix3lpirat3.mfbridge.api.MessageContext;
import io.github.pix3lpirat3.mfbridge.api.SemVer;
import io.github.pix3lpirat3.mfbridge.api.client.BotButton;
import io.github.pix3lpirat3.mfbridge.api.client.ClientBridge;
import io.github.pix3lpirat3.mfbridge.api.client.ClientExtension;
import io.github.pix3lpirat3.mfbridge.api.client.ClientRegistrar;
import io.github.pix3lpirat3.mfbridge.common.ext.console.wire.BridgeConsoleInput;
import io.github.pix3lpirat3.mfbridge.common.ext.console.wire.ConsoleLine;
import io.github.pix3lpirat3.mfbridge.common.ext.console.wire.ConsoleWire;
import io.github.pix3lpirat3.mfbridge.protocol.core.CoreKinds;
import io.github.pix3lpirat3.mfbridge.protocol.core.Vec3d;
import io.github.pix3lpirat3.mfbridge.protocol.core.WaypointData;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * {@code bridge:console}, the mod half: keeps the last {@link #MAX_LINES} lines
 * each bot sent as {@code bridge:console.lines}, and opens a console from the bot's row on Bridge home
 * with search, a level filter, positions the player can mark in the world, and an input line sent back
 * as {@code bridge:console.input}. Only attached sessions with the {@code notify} permission may
 * send lines, and a bot's lines are dropped a while after its session ended. Client thread only.
 */
public final class ConsoleExtension implements ClientExtension {

    /** The extension id. */
    public static final ExtensionId ID = new ExtensionId("bridge", "console");
    /** Lines kept per bot. */
    public static final int MAX_LINES = 2000;

    /** Longest line kept, in characters; longer ones are cut. */
    public static final int MAX_LINE_CHARS = 4096;

    private static final String MARK_KEY = "player/bridge:console/mark";
    /** Ticks between looks for bots whose session ended. */
    private static final int SWEEP_TICKS = 100;

    private final Map<String, ArrayDeque<ConsoleLine>> lines = new HashMap<>();
    /** When each bot with lines was first seen without a session, bridge clock ms. */
    private final Map<String, Long> endedMs = new HashMap<>();
    private int sweepIn = SWEEP_TICKS;
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
    public void register(final ExtensionRegistrar r) {
        r.message(ConsoleWire.CONSOLE_LINES, MessageAccess.ATTACHED.withPermission("notify"), (ctx, m) -> append(owner(ctx), m.lines()));
        // Sent by the mod only; registered so connections negotiate it.
        r.message(ConsoleWire.CONSOLE_INPUT, (ctx, m) -> { });
    }

    @Override
    public void registerClient(final ClientRegistrar r) {
        this.bridge = r.bridge();
        r.tick(this::sweep);
        r.botButton(Component.translatable("mineflayer_bridge_console.button"), "minecraft:writable_book", new BotButton() {
            @Override
            public boolean shows(final String botOwner, final boolean online) {
                return online || lines.containsKey(botOwner);
            }

            @Override
            public void press(final String botOwner) {
                open(botOwner);
            }
        });
    }

    private static String owner(final MessageContext ctx) {
        final String p = ctx.ownerPrefix();
        return p.endsWith("/") ? p.substring(0, p.length() - 1) : p;
    }

    /** Drops the lines of bots whose session ended longer ago than the bridge keeps extension buffers. */
    private void sweep() {
        if (--sweepIn > 0 || lines.isEmpty()) {
            return;
        }
        sweepIn = SWEEP_TICKS;
        final ClientBridge b = bridge;
        if (b == null || !b.running()) {
            // The endpoint is down and every session with it, so nothing can be timed.
            endedMs.clear();
            return;
        }
        final long now = System.nanoTime() / 1_000_000L;
        final long keepMs = b.bufferMs();
        final Iterator<String> owners = lines.keySet().iterator();
        while (owners.hasNext()) {
            final String owner = owners.next();
            if (b.session(owner) != null) {
                endedMs.remove(owner);
                continue;
            }
            final Long ended = endedMs.putIfAbsent(owner, now);
            if (ended != null && now - ended >= keepMs) {
                owners.remove();
                endedMs.remove(owner);
            }
        }
    }

    private void append(final String owner, final List<ConsoleLine> added) {
        final ArrayDeque<ConsoleLine> ring = lines.computeIfAbsent(owner, k -> new ArrayDeque<>());
        // Only the newest lines can stay, so older ones of a large batch are not even looked at.
        for (final ConsoleLine line : added.subList(Math.max(0, added.size() - MAX_LINES), added.size())) {
            if (ring.size() == MAX_LINES) {
                ring.removeFirst();
            }
            final ConsoleLine l = line.text().length() <= MAX_LINE_CHARS ? line
                    : new ConsoleLine(line.time(), line.level(), line.text().substring(0, MAX_LINE_CHARS), line.pos());
            ring.addLast(l);
        }
        if (Minecraft.getInstance().gui.screen() instanceof ConsoleScreen s && s.owner().equals(owner)) {
            s.linesChanged();
        }
    }

    /** Opens a bot's console. */
    void open(final String owner) {
        final ClientBridge b = bridge;
        if (b == null) {
            return;
        }
        final Minecraft mc = Minecraft.getInstance();
        mc.gui.setScreen(new ConsoleScreen(this, owner, b.botName(owner), mc.gui.screen()));
    }

    /** Returns a bot's lines, oldest first. */
    List<ConsoleLine> lines(final String owner) {
        final ArrayDeque<ConsoleLine> ring = lines.get(owner);
        return ring == null ? List.of() : List.copyOf(ring);
    }

    /** Forgets a bot's lines. */
    void clear(final String owner) {
        lines.remove(owner);
        endedMs.remove(owner);
    }

    /**
     * Sends a typed line to the bot.
     *
     * @return null when sent, or why not
     */
    @Nullable String input(final String owner, final String text) {
        final ClientBridge b = bridge;
        final MessageContext session = b == null ? null : b.session(owner);
        if (b == null || session == null) {
            return I18n.get("mineflayer_bridge_console.not_sent.offline");
        }
        try {
            session.send(ConsoleWire.CONSOLE_INPUT, new BridgeConsoleInput(text));
            b.playerAsked(owner);
            return null;
        } catch (final RuntimeException e) {
            return I18n.get("mineflayer_bridge_console.not_sent.unsupported");
        }
    }

    /** Marks a position from a line as a waypoint with a tracer, replacing the previous mark. */
    void mark(final Vec3d pos, final String label) {
        final ClientBridge b = bridge;
        if (b == null || !b.running()) {
            return;
        }
        b.putPlayerObject(MARK_KEY, CoreKinds.WAYPOINT, "console", new WaypointData(pos, label).withBeam(true).withTracer(true));
        b.toast(I18n.get("mineflayer_bridge_console.toast"), I18n.get("mineflayer_bridge_console.marked", label));
    }

    /** Removes the mark, if any. */
    void unmark() {
        final ClientBridge b = bridge;
        if (b != null) {
            b.removePlayerObject(MARK_KEY);
        }
    }
}
