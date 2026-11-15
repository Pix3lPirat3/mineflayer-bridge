package io.github.pix3lpirat3.mfbridge.core.query;

import io.github.pix3lpirat3.mfbridge.api.thread.ClientThread;
import io.github.pix3lpirat3.mfbridge.protocol.core.QueryBlocks;
import io.github.pix3lpirat3.mfbridge.protocol.core.QueryBlocksResult;
import io.github.pix3lpirat3.mfbridge.protocol.core.QueryEntities;
import io.github.pix3lpirat3.mfbridge.protocol.core.QueryEntitiesResult;

/**
 * What the player's client has loaded, answered for {@code query.entities} and {@code query.blocks}.
 * Implemented by the game integration; the core checks the permission,
 * the world match and the limits before calling, so implementations get requests within
 * {@link #MAX_RADIUS}, {@link #MAX_ENTITIES}, {@link #MAX_VOLUME} and {@link #MAX_BLOCKS}.
 */
@ClientThread
public interface ClientQueries {
    /** Largest entity query radius, blocks. */
    float MAX_RADIUS = 128f;
    /** Most entities in one answer. */
    int MAX_ENTITIES = 1024;
    /** Largest block query box, blocks. */
    long MAX_VOLUME = 65_536L;
    /** Most block positions in one answer. */
    int MAX_BLOCKS = 65_536;

    /**
     * Returns the loaded entities near a point, nearest first, the player's own excluded.
     *
     * @param query the request, with the centre filled in and the limits applied
     * @return the answer
     */
    QueryEntitiesResult entities(QueryEntities query);

    /**
     * Returns the loaded blocks in a box.
     *
     * @param query the request, with the limits applied
     * @return the answer; {@code complete} is false when part of the box is not loaded or the limit cut it
     */
    QueryBlocksResult blocks(QueryBlocks query);
}
