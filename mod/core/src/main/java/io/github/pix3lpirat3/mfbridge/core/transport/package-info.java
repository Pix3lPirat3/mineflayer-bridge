/**
 * WebSocket transport: a single-thread Netty server that decodes frame
 * headers and hands frames to the client thread through lock-free per-connection queues.
 */
@NullMarked
package io.github.pix3lpirat3.mfbridge.core.transport;

import org.jspecify.annotations.NullMarked;
