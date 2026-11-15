/**
 * The protocol engine: handshake, sessions, sequencing, acks, resume, heartbeat,
 * object sync and shared objects. Runs entirely on the client thread inside the budgeted tick.
 */
@NullMarked
package io.github.pix3lpirat3.mfbridge.core.session;

import org.jspecify.annotations.NullMarked;
