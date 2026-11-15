package io.github.pix3lpirat3.mfbridge.common.ext;

import io.github.pix3lpirat3.mfbridge.api.BridgeExtension;
import io.github.pix3lpirat3.mfbridge.common.ext.console.ConsoleExtension;
import io.github.pix3lpirat3.mfbridge.common.ext.hotkeys.HotkeysExtension;
import io.github.pix3lpirat3.mfbridge.common.ext.map.MapExtension;
import io.github.pix3lpirat3.mfbridge.common.ext.ping.PingExtension;
import io.github.pix3lpirat3.mfbridge.common.ext.spectate.SpectateExtension;
import io.github.pix3lpirat3.mfbridge.common.ext.tasks.TasksExtension;
import java.util.List;

/** The bridge's own mod extensions, registered through the same path as third-party ones. */
public final class FirstParty {

    private FirstParty() {
    }

    /**
     * Returns new instances of the first-party extensions.
     *
     * @return the extensions, in a stable order
     */
    public static List<BridgeExtension> extensions() {
        return List.of(new ConsoleExtension(), new HotkeysExtension(), new MapExtension(), new PingExtension(), new SpectateExtension(),
                new TasksExtension());
    }
}
