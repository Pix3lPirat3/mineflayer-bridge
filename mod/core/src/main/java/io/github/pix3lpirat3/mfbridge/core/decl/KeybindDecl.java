package io.github.pix3lpirat3.mfbridge.core.decl;

import io.github.pix3lpirat3.mfbridge.protocol.core.KeybindData;

/**
 * A {@code core:keybind} declaration.
 *
 * @param key full object key, {@code <owner>/<extension>/~key/<id>}; pass it to {@code
 *     BridgeCore.keybindEvent}
 * @param ownerName display name of the owning session
 * @param data decoded declaration with the bot's default keys
 */
public record KeybindDecl(String key, String ownerName, KeybindData data) {}
