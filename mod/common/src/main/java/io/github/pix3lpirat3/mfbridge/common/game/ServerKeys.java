package io.github.pix3lpirat3.mfbridge.common.game;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import java.nio.file.Path;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.world.level.storage.LevelResource;

/** Builds the server part of the world key. */
public final class ServerKeys {

    private ServerKeys() {
    }

    /**
     * Returns the key of the server the client is connected to. Call on the client thread while in a world.
     *
     * @param minecraft the client
     * @return {@code localhost:<port>} for an integrated server opened to LAN, {@code singleplayer:<save folder>}
     *     for one that is not (the folder, because two saves may share a display name), {@code host:port} for
     *     a remote server with the host lower-cased, or {@code unknown} if neither is known
     */
    public static String current(final Minecraft minecraft) {
        final IntegratedServer integrated = minecraft.getSingleplayerServer();
        if (integrated != null) {
            // Bots can only join an integrated server once it is opened to LAN, through its published port.
            return integrated.isPublished()
                    ? "localhost:" + integrated.getPort()
                    : "singleplayer:" + saveFolder(integrated);
        }
        final ServerData data = minecraft.getCurrentServer();
        if (data == null) {
            return "unknown";
        }
        final ServerAddress address = ServerAddress.parseString(data.ip);
        return address.getHost().toLowerCase(java.util.Locale.ROOT) + ":" + address.getPort();
    }

    /** The name of the folder the world is saved in, which no other save shares. */
    private static String saveFolder(final IntegratedServer integrated) {
        final Path name = integrated.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize().getFileName();
        return name == null ? integrated.getWorldData().getLevelName() : name.toString();
    }
}
