package io.github.pix3lpirat3.mfbridge.core.session;

import io.github.pix3lpirat3.mfbridge.protocol.core.CoreKinds;
import io.github.pix3lpirat3.mfbridge.protocol.wire.KindType;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/**
 * What a write of each kind can change: the render snapshot, the declaration snapshot or neither.
 * A write only marks what its kind feeds, so a variable or HUD update never touches the renderers.
 */
final class KindRoles {
    private static final Set<KindType<?>> DECLARATIONS = identitySet(List.of(
            CoreKinds.COMMAND, CoreKinds.CREW, CoreKinds.ACTION, CoreKinds.KEYBIND, CoreKinds.TOOL, CoreKinds.INTERCEPT,
            CoreKinds.SCREEN, CoreKinds.SETTINGS, CoreKinds.CONTAINER_VIEW, CoreKinds.BOSSBAR, CoreKinds.HUD_PANEL,
            CoreKinds.HUD_TEXT, CoreKinds.HUD_KV, CoreKinds.HUD_BAR, CoreKinds.HUD_LIST, CoreKinds.HUD_BADGE,
            CoreKinds.HUD_SPARKLINE, CoreKinds.HUD_ITEM));

    private KindRoles() {}

    private static Set<KindType<?>> identitySet(List<KindType<?>> kinds) {
        Set<KindType<?>> set = Collections.newSetFromMap(new IdentityHashMap<>());
        set.addAll(kinds);
        return set;
    }

    /** Returns whether objects of this kind are declarations: commands, HUD parts and the like. */
    static boolean declaration(KindType<?> kind) {
        return DECLARATIONS.contains(kind);
    }

    /** Returns whether objects of this kind only describe others: layers, styles and variables. */
    static boolean metadata(KindType<?> kind) {
        return kind == CoreKinds.LAYER || kind == CoreKinds.STYLE || kind == CoreKinds.VARS;
    }

    /** Returns whether objects of this kind are entries of the render snapshot. */
    static boolean rendered(KindType<?> kind) {
        return !metadata(kind) && !declaration(kind);
    }
}
