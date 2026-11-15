package io.github.pix3lpirat3.mfbridge.core.decl;

import io.github.pix3lpirat3.mfbridge.protocol.core.ActionData;
import io.github.pix3lpirat3.mfbridge.protocol.core.Scope;

/**
 * A {@code core:action} declaration.
 *
 * @param key full object key, {@code <owner>/<extension>/~action/<id>}; pass it to {@code
 *     BridgeCore.invokeAction}
 * @param ownerName display name of the owning session
 * @param scope object scope: global actions also work outside the bot's world
 * @param data decoded declaration
 */
public record ActionDecl(String key, String ownerName, Scope scope, ActionData data) {}
