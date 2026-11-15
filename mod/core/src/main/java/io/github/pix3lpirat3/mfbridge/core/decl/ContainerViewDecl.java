package io.github.pix3lpirat3.mfbridge.core.decl;

import io.github.pix3lpirat3.mfbridge.protocol.core.ContainerViewData;
import org.jspecify.annotations.Nullable;

/**
 * A {@code core:container-view} declaration.
 *
 * @param key full object key, {@code <owner>/<extension>/~view/<id>}; pass it to {@code
 *     BridgeCore.containers()}
 * @param ownerName display name of the owning session
 * @param ownerIdentity bot identity of the owner, or null for a controller
 * @param data decoded view: title, layout, slots, filler and close behaviour
 */
public record ContainerViewDecl(String key, String ownerName, @Nullable String ownerIdentity, ContainerViewData data) {}
