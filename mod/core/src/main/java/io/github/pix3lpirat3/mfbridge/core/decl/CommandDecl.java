package io.github.pix3lpirat3.mfbridge.core.decl;

import io.github.pix3lpirat3.mfbridge.protocol.core.CommandData;
import io.github.pix3lpirat3.mfbridge.protocol.core.Scope;
import org.jspecify.annotations.Nullable;

/**
 * A {@code core:command} declaration.
 *
 * @param key full object key, {@code <owner>/<extension>/~cmd/<name>}; pass it to {@code
 *     BridgeCore.invokeCommand}
 * @param ownerName display name of the owning session
 * @param ownerIdentity bot identity of the owner, or null for a controller
 * @param scope object scope; world-scoped commands only appear while the player's world matches
 * @param data decoded declaration
 */
public record CommandDecl(String key, String ownerName, @Nullable String ownerIdentity, Scope scope, CommandData data) {}
