package io.github.pix3lpirat3.mfbridge.api.client;

import org.jspecify.annotations.Nullable;

/**
 * A zone, point or route the player currently sees. Coordinates are world blocks.
 *
 * @param key the full object key
 * @param owner the owner without a trailing slash: {@code shared}, {@code player} or a bot owner
 * @param kind what it is
 * @param name its name
 * @param minX the lowest x it reaches
 * @param minY the lowest y
 * @param minZ the lowest z
 * @param maxX the highest x; equal to {@code minX} for a point
 * @param maxY the highest y
 * @param maxZ the highest z
 * @param polyline a route's path as x, y, z triples, or null for zones and points; read-only
 */
public record Place(String key, String owner, Kind kind, String name, double minX, double minY, double minZ, double maxX,
        double maxY, double maxZ, double @Nullable [] polyline) {

    /** What a {@link Place} is. */
    public enum Kind {
        /** A volume. */
        ZONE,
        /** A single position. */
        POINT,
        /** A path through positions. */
        ROUTE
    }
}
