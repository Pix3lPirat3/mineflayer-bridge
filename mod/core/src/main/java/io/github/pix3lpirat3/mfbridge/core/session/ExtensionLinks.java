package io.github.pix3lpirat3.mfbridge.core.session;

import io.github.pix3lpirat3.mfbridge.api.BridgeException;
import io.github.pix3lpirat3.mfbridge.api.MessageContext;
import io.github.pix3lpirat3.mfbridge.api.SessionRef;
import io.github.pix3lpirat3.mfbridge.api.thread.ClientThread;
import io.github.pix3lpirat3.mfbridge.core.ext.ConnRegistry;
import io.github.pix3lpirat3.mfbridge.protocol.wire.MessageType;
import org.jspecify.annotations.Nullable;

/** The session handles that mod extensions get: for handlers, and to message the Node half first. */
@ClientThread
final class ExtensionLinks {
    private final SessionTable table;
    private final Outbox outbox;

    ExtensionLinks(SessionTable table, Outbox outbox) {
        this.table = table;
        this.outbox = outbox;
    }

    /** Returns a session as an extension's message handler sees it. */
    MessageContext context(Session s) {
        return new SessionContext(s);
    }

    private final class SessionContext implements MessageContext {
        private final Session session;

        SessionContext(Session session) {
            this.session = session;
        }

        @Override
        public int sessionId() {
            return session.id;
        }

        @Override
        public String ownerPrefix() {
            return session.ownerPrefix;
        }

        @Override
        public String displayName() {
            return session.displayName;
        }

        @Override
        public <T> void send(MessageType<T> type, T message) {
            int id = negotiatedId(session, type);
            if (id < 0 || !type.from().allowsFromMod()) {
                throw new BridgeException("unknown_type", type.name() + " was not negotiated on this connection");
            }
            outbox.sendExtension(session, id, type, message);
        }

        @Override
        public SessionRef session() {
            return ref(session);
        }
    }

    /** One handle per session, so a resumed session keeps its ref. */
    private SessionRef ref(Session s) {
        SessionRef r = s.ref;
        if (r == null) {
            r = new SessionHandle(s);
            s.ref = r;
        }
        return r;
    }

    private final class SessionHandle implements SessionRef {
        private final Session session;

        SessionHandle(Session session) {
            this.session = session;
        }

        @Override
        public int sessionId() {
            return session.id;
        }

        @Override
        public String ownerPrefix() {
            return session.ownerPrefix;
        }

        @Override
        public String displayName() {
            return session.displayName;
        }

        @Override
        public boolean isOpen() {
            return !session.ended && !session.superseded;
        }

        @Override
        public <T> boolean send(MessageType<T> type, T message) {
            if (!type.from().allowsFromMod()) {
                throw new IllegalArgumentException(type.name() + " may only be sent by Node");
            }
            int id = negotiatedId(session, type);
            if (id < 0 || !isOpen()) {
                return false;
            }
            outbox.sendExtension(session, id, type, message);
            return true;
        }

        @Override
        public String toString() {
            return "SessionRef[" + session.ownerPrefix + "]";
        }
    }

    private static int negotiatedId(Session s, MessageType<?> type) {
        ConnRegistry reg = s.registry();
        return reg == null ? -1 : reg.messageId(type.name());
    }

    /**
     * Returns the session of an owner as extension handlers see it, so a mod extension can message
     * the Node half first, or null while no session writes for that owner.
     */
    @Nullable MessageContext sessionContext(String owner) {
        Session w = table.writers.get(owner.endsWith("/") ? owner : owner + '/');
        return w == null ? null : new SessionContext(w);
    }
}
