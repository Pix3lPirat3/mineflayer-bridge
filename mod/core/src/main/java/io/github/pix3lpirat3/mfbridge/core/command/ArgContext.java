package io.github.pix3lpirat3.mfbridge.core.command;

import io.github.pix3lpirat3.mfbridge.protocol.core.Vec3d;
import io.github.pix3lpirat3.mfbridge.protocol.core.Vec3i;
import org.jspecify.annotations.Nullable;

/**
 * What argument parsing needs from the game: the origin for {@code ~} coordinates, the crosshair
 * target for {@code here} and {@code @looked}, registry lookups for ids and the loaded entities for
 * selectors. Implementations read game state when called, on the client thread.
 */
public interface ArgContext {
    /** A context with the origin at 0, no crosshair target, no entities and every id accepted. */
    ArgContext NONE = new ArgContext() {
        @Override
        public Vec3i originBlock() {
            return new Vec3i(0, 0, 0);
        }

        @Override
        public Vec3d originExact() {
            return new Vec3d(0, 0, 0);
        }

        @Override
        public @Nullable Vec3i crosshairBlock() {
            return null;
        }

        @Override
        public @Nullable Vec3d crosshairPos() {
            return null;
        }

        @Override
        public boolean blockExists(String id) {
            return true;
        }

        @Override
        public boolean itemExists(String id) {
            return true;
        }
    };

    /** Returns the block position {@code ~} refers to in {@code block_pos} arguments: the player's. */
    Vec3i originBlock();

    /** Returns the exact position {@code ~} refers to in {@code vec3} arguments: the player's. */
    Vec3d originExact();

    /** Returns the block under the crosshair, or null when the crosshair is on no block. */
    @Nullable Vec3i crosshairBlock();

    /** Returns the exact crosshair hit position, or null when the crosshair is on nothing. */
    @Nullable Vec3d crosshairPos();

    /**
     * Returns whether a block id is registered.
     *
     * @param id a namespaced id such as {@code minecraft:stone}
     * @return whether the block exists
     */
    boolean blockExists(String id);

    /**
     * Returns whether an item id is registered.
     *
     * @param id a namespaced id such as {@code minecraft:stick}
     * @return whether the item exists
     */
    boolean itemExists(String id);

    /** Returns the loaded entities, for selectors and entity names; {@link EntityQuery#NONE} by default. */
    default EntityQuery entities() {
        return EntityQuery.NONE;
    }
}
