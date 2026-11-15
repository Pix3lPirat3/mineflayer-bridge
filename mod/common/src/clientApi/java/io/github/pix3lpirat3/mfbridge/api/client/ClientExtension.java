package io.github.pix3lpirat3.mfbridge.api.client;

import io.github.pix3lpirat3.mfbridge.api.BridgeExtension;

/**
 * A mod extension that adds to the game itself. Implemented by the same
 * class as its {@link BridgeExtension}; discovery and dependencies are the extension's.
 */
public interface ClientExtension extends BridgeExtension {

    /**
     * Adds the extension's client parts. Runs once during client init, after every dependency's,
     * and only when the extension loads.
     *
     * @param registrar where to add them
     */
    void registerClient(ClientRegistrar registrar);
}
