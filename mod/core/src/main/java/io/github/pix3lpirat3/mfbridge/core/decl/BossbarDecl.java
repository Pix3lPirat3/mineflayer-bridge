package io.github.pix3lpirat3.mfbridge.core.decl;

import io.github.pix3lpirat3.mfbridge.protocol.core.BossbarData;

/**
 * A {@code core:bossbar} declaration.
 *
 * @param key full object key, {@code <owner>/<extension>/~bossbar/<id>}
 * @param ownerName display name of the owning session
 * @param data decoded declaration
 */
public record BossbarDecl(String key, String ownerName, BossbarData data) {}
