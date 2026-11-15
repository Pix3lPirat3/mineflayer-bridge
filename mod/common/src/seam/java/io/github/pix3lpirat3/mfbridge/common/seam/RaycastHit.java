package io.github.pix3lpirat3.mfbridge.common.seam;

import java.util.UUID;

/** The result of a successful raycast from the camera. */
public sealed interface RaycastHit {

    /**
     * Returns where the ray hit.
     *
     * @return hit location in world space
     */
    Vec3d location();

    /**
     * A hit on a block face.
     *
     * @param location hit location in world space
     * @param blockX block x coordinate
     * @param blockY block y coordinate
     * @param blockZ block z coordinate
     * @param face lower-case name of the face that was hit, such as {@code up} or {@code north}
     */
    record Block(Vec3d location, int blockX, int blockY, int blockZ, String face) implements RaycastHit {
    }

    /**
     * A hit on an entity.
     *
     * @param location hit location in world space
     * @param entityId the client-side entity UUID
     */
    record Entity(Vec3d location, UUID entityId) implements RaycastHit {
    }
}
