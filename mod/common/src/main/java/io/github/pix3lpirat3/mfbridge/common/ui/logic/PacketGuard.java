package io.github.pix3lpirat3.mfbridge.common.ui.logic;

import java.util.ArrayDeque;
import java.util.Iterator;
import org.jspecify.annotations.Nullable;

/**
 * Defence in depth for vanilla input surfaces and container views: the bridge's surfaces and
 * container views never build the packets their vanilla counterparts send, and this guard drops those
 * packets anyway if anything tries to send one while a bridge screen is open. It also drops a chat
 * packet whose text the chat bridge captured. The decision is game-free; the connection mixin maps
 * packets to a {@link Kind}.
 *
 * <p>Thread-safe: packets may be sent from any thread.
 */
public final class PacketGuard {

    /** How long a captured chat line stays blocked, in milliseconds. */
    public static final long CAPTURE_TTL_MS = 2_000L;

    private static final int MAX_CAPTURED = 32;

    private record Captured(String line, long atMs) {
    }

    private final String ownNamespace;
    private final ArrayDeque<Captured> captured = new ArrayDeque<>();

    /**
     * Creates a guard.
     *
     * @param ownNamespace the mod's identifier namespace; custom click actions in it never reach a server
     */
    public PacketGuard(final String ownNamespace) {
        this.ownNamespace = ownNamespace;
    }

    /** What a serverbound packet does, as far as the guard cares. */
    public enum Kind {
        /** {@code ServerboundRenameItemPacket}, sent by the anvil screen. */
        RENAME_ITEM,
        /** {@code ServerboundSignUpdatePacket}, sent when a sign edit screen closes. */
        SIGN_UPDATE,
        /** {@code ServerboundEditBookPacket}, sent by the book edit and sign screens. */
        EDIT_BOOK,
        /** {@code ServerboundContainerClickPacket}. */
        CONTAINER_CLICK,
        /** {@code ServerboundContainerClosePacket}. */
        CONTAINER_CLOSE,
        /** {@code ServerboundSelectTradePacket}, sent by the merchant screen. */
        SELECT_TRADE,
        /** {@code ServerboundContainerButtonClickPacket}, sent by the stonecutter and enchanting screens. */
        CONTAINER_BUTTON,
        /** {@code ServerboundPlaceRecipePacket}, sent by the crafting screen's recipe book. */
        PLACE_RECIPE,
        /** {@code ServerboundCustomClickActionPacket}, sent by dialog and chat click actions. */
        CUSTOM_CLICK_ACTION,
        /** {@code ServerboundChatPacket}. */
        CHAT,
        /** Anything else; never blocked. */
        OTHER
    }

    /**
     * Records a chat line the bridge captured, so a chat packet carrying it is dropped.
     *
     * @param line the captured line
     * @param nowMs monotonic milliseconds
     */
    public synchronized void captured(final String line, final long nowMs) {
        prune(nowMs);
        if (captured.size() == MAX_CAPTURED) {
            captured.removeFirst();
        }
        captured.addLast(new Captured(line, nowMs));
    }

    /**
     * Forgets a line the bridge let through, so an identical line typed again reaches the server.
     *
     * @param line the line passed to the server
     */
    public synchronized void released(final String line) {
        captured.removeIf(c -> c.line().equals(line));
    }

    /**
     * Decides whether a packet must be dropped.
     *
     * @param kind what the packet does
     * @param bridgeScreenOpen whether a bridge surface or container view is the open screen
     * @param chatText the message of a chat packet, else null
     * @param customNamespace the identifier namespace of a custom click action, else null
     * @param nowMs monotonic milliseconds
     * @return true to drop the packet
     */
    public synchronized boolean blocks(final Kind kind, final boolean bridgeScreenOpen, final @Nullable String chatText,
            final @Nullable String customNamespace, final long nowMs) {
        return switch (kind) {
            case RENAME_ITEM, SIGN_UPDATE, EDIT_BOOK, CONTAINER_CLICK, CONTAINER_CLOSE, SELECT_TRADE, CONTAINER_BUTTON, PLACE_RECIPE ->
                    bridgeScreenOpen;
            case CUSTOM_CLICK_ACTION -> bridgeScreenOpen || ownNamespace.equals(customNamespace);
            case CHAT -> chatText != null && consume(chatText, nowMs);
            case OTHER -> false;
        };
    }

    private boolean consume(final String text, final long nowMs) {
        prune(nowMs);
        final Iterator<Captured> it = captured.iterator();
        while (it.hasNext()) {
            if (it.next().line().equals(text)) {
                it.remove();
                return true;
            }
        }
        return false;
    }

    private void prune(final long nowMs) {
        while (!captured.isEmpty() && nowMs - captured.getFirst().atMs() > CAPTURE_TTL_MS) {
            captured.removeFirst();
        }
    }
}
