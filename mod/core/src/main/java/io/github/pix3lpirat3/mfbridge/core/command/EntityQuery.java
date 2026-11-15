package io.github.pix3lpirat3.mfbridge.core.command;

import io.github.pix3lpirat3.mfbridge.protocol.core.Vec3d;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The client's view of loaded entities, used to resolve target selectors and entity names into
 * entity ids before {@code command.invoked} is sent. Implementations
 * read the client level on the client thread; the core's {@link #NONE} knows no entities.
 */
public interface EntityQuery {
    /** Knows no entities: every lookup answers null or an empty list. */
    EntityQuery NONE = new EntityQuery() {
        @Override
        public @Nullable Integer player() {
            return null;
        }

        @Override
        public @Nullable Integer bot(String username) {
            return null;
        }

        @Override
        public @Nullable Integer looked() {
            return null;
        }

        @Override
        public @Nullable Integer named(String name) {
            return null;
        }

        @Override
        public List<Integer> find(Selector.Filter filter, Vec3d from, int limit) {
            return List.of();
        }

        @Override
        public @Nullable Vec3d position(int entityId) {
            return null;
        }
    };

    /** Returns the player's entity id, or null outside a world. */
    @Nullable Integer player();

    /**
     * Finds a bot's own entity by matching its username in the client level.
     *
     * @param username the bot's username
     * @return its entity id, or null when it is not loaded
     */
    @Nullable Integer bot(String username);

    /** Returns the id of the entity under the crosshair, or null. */
    @Nullable Integer looked();

    /**
     * Finds a loaded player entity by name, ignoring case.
     *
     * @param name the typed name
     * @return its entity id, or null
     */
    @Nullable Integer named(String name);

    /**
     * Finds entities matching a selector filter, nearest first.
     *
     * @param filter type, tag and distance constraints; distances are in blocks from {@code from}
     * @param from the point distances are measured from, in world coordinates
     * @param limit the most ids to return, at least 1
     * @return matching entity ids, nearest first. Immutable.
     */
    List<Integer> find(Selector.Filter filter, Vec3d from, int limit);

    /**
     * Returns where an entity is.
     *
     * @param entityId the entity id
     * @return its position in world coordinates, or null when it is not loaded
     */
    @Nullable Vec3d position(int entityId);
}
