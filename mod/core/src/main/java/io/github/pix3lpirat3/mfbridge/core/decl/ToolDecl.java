package io.github.pix3lpirat3.mfbridge.core.decl;

import io.github.pix3lpirat3.mfbridge.protocol.core.ToolData;

/**
 * A {@code core:tool} declaration.
 *
 * @param key full object key, {@code <owner>/<extension>/~tool/<id>}; pass it to {@code
 *     BridgeCore.setActiveTool} and {@code BridgeCore.toolEvent}
 * @param ownerName display name of the owning session
 * @param data decoded declaration
 */
public record ToolDecl(String key, String ownerName, ToolData data) {}
