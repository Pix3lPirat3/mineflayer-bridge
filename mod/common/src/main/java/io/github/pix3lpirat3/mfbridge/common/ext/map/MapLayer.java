package io.github.pix3lpirat3.mfbridge.common.ext.map;

/** What the map draws; the legend switches each on or off. */
enum MapLayer {
    /** Other players the client sees. */
    PLAYERS(0xFFFFFFFF),
    /** Bots with a bridge session. */
    BOTS(0xFF4FC3F7),
    /** Monsters. */
    HOSTILE(0xFFFF5252),
    /** Other living things: animals, villagers, fish. */
    ANIMALS(0xFF9CCC65),
    /** Items lying on the ground. */
    ITEMS(0xFFFFEE58),
    /** Shared zones. */
    ZONES(0xFF81C784),
    /** Shared points and waypoints. */
    POINTS(0xFFFF8A65),
    /** Shared routes. */
    ROUTES(0xFFBA68C8);

    /** The colour the map draws this layer in. */
    final int argb;

    MapLayer(final int argb) {
        this.argb = argb;
    }
}
