package io.github.pix3lpirat3.mfbridge.core.session;

import io.github.pix3lpirat3.mfbridge.api.thread.AnyThread;
import io.github.pix3lpirat3.mfbridge.api.thread.ClientThread;
import io.github.pix3lpirat3.mfbridge.core.store.StoredObject;
import io.github.pix3lpirat3.mfbridge.core.vars.Variables;
import io.github.pix3lpirat3.mfbridge.protocol.core.VarsData;
import java.util.HashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** Publishes the variables of every owner and extension from their {@code core:vars} objects. */
@ClientThread
final class VariablePublisher {
    /** The {@code core:vars} object of each {@code <owner>/<extension>}. */
    private final Map<String, StoredObject> objects = new HashMap<>();
    private boolean dirty;
    private volatile Variables variables = Variables.EMPTY;

    /** Returns the latest variables. */
    @AnyThread
    Variables variables() {
        return variables;
    }

    /** Takes a {@code core:vars} object that was written. */
    void upserted(StoredObject o) {
        objects.put(scope(o), o);
        dirty = true;
    }

    /** Forgets a {@code core:vars} object that was removed. */
    void removed(StoredObject o) {
        if (objects.remove(scope(o)) != null) {
            dirty = true;
        }
    }

    /** Publishes new variables when a {@code core:vars} object changed since the last call. */
    void publish() {
        if (!dirty) {
            return;
        }
        dirty = false;
        Map<String, Map<String, @Nullable Object>> byScope = new HashMap<>();
        for (Map.Entry<String, StoredObject> e : objects.entrySet()) {
            if (e.getValue().state().decoded() instanceof VarsData d) {
                byScope.put(e.getKey(), d.values());
            }
        }
        variables = new Variables(variables.version() + 1, byScope);
    }

    private static String scope(StoredObject o) {
        return o.parts().owner() + '/' + o.parts().extension();
    }
}
