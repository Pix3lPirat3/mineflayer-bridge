package io.github.pix3lpirat3.mfbridge.core.decl;

import io.github.pix3lpirat3.mfbridge.protocol.core.CrewData;

/**
 * A {@code core:crew} declaration: the bots a session gives orders to, and its named groups of them.
 * The session's order commands are typed after {@code @<bot>}, {@code @<group>} or {@code @all}.
 *
 * @param key full object key
 * @param owner owner key of the declaring session, without trailing slash
 * @param ownerName display name of the declaring session
 * @param data decoded declaration
 */
public record CrewDecl(String key, String owner, String ownerName, CrewData data) {}
