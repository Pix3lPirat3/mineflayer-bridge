package io.github.pix3lpirat3.mfbridge.core.decl;

import io.github.pix3lpirat3.mfbridge.protocol.core.HudPanelData;
import java.util.List;

/**
 * A {@code core:hud.panel} with its widgets. A widget belongs to the panel
 * when its key is {@code <panel key>/<widget id>} and its {@code panel} field equals the panel id.
 *
 * @param key full object key, {@code <owner>/<extension>/~hud/<panel>}
 * @param ownerName display name of the owning session
 * @param data decoded panel declaration
 * @param widgets widgets sorted by {@code order}, then key. Immutable.
 */
public record HudPanel(String key, String ownerName, HudPanelData data, List<HudWidget> widgets) {
    /** Copies {@code widgets}. */
    public HudPanel {
        widgets = List.copyOf(widgets);
    }
}
