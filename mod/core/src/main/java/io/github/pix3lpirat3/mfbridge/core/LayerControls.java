package io.github.pix3lpirat3.mfbridge.core;

import io.github.pix3lpirat3.mfbridge.api.thread.ClientThread;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Player layer toggles. Visibility is the player's override when set, else the {@code
 * defaultVisible} of the layer's {@code core:layer} object, else visible. Overrides are persisted in
 * the player store and survive restarts. Client thread only.
 */
@ClientThread
public interface LayerControls {
    /** Returns every layer declared by a {@code core:layer} object or overridden by the player. */
    List<LayerInfo> layers();

    /** Returns the effective visibility of a layer. */
    boolean isVisible(String owner, String extension, String layer);

    /**
     * Sets or clears the player's override. Changes are sent to the owning sessions as {@code
     * interest} and reflected in the next render snapshot.
     *
     * @param visible the override, or null to fall back to the declared default
     */
    void setVisible(String owner, String extension, String layer, @Nullable Boolean visible);
}
