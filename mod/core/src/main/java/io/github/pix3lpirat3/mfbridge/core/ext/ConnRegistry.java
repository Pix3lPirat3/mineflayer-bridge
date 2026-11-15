package io.github.pix3lpirat3.mfbridge.core.ext;

import io.github.pix3lpirat3.mfbridge.api.thread.ClientThread;
import io.github.pix3lpirat3.mfbridge.protocol.core.CoreKinds;
import io.github.pix3lpirat3.mfbridge.protocol.core.RegistryEntry;
import io.github.pix3lpirat3.mfbridge.protocol.core.RegistrySpace;
import io.github.pix3lpirat3.mfbridge.protocol.wire.KindType;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Per-connection id assignment for extension messages and kinds: ids
 * start at {@code 0x100}, in the order the plugin listed the names; unsupported names are left out.
 * Ids are never persisted.
 */
@ClientThread
public final class ConnRegistry {
    /** First id available to extension messages and kinds. */
    public static final int FIRST_ID = 0x100;

    private final Int2ObjectOpenHashMap<ExtensionHost.RegisteredMessage<?>> messagesById = new Int2ObjectOpenHashMap<>();
    private final Object2IntOpenHashMap<String> messageIds = new Object2IntOpenHashMap<>();
    private final Int2ObjectOpenHashMap<ExtensionHost.RegisteredKind<?>> kindsById = new Int2ObjectOpenHashMap<>();
    private final Object2IntOpenHashMap<String> kindIds = new Object2IntOpenHashMap<>();
    private final List<RegistryEntry> entries = new ArrayList<>();

    ConnRegistry(ExtensionHost host, List<String> messageNames, List<String> kindNames) {
        messageIds.defaultReturnValue(-1);
        kindIds.defaultReturnValue(-1);
        int next = FIRST_ID;
        for (String name : messageNames) {
            ExtensionHost.RegisteredMessage<?> m = host.message(name);
            if (m != null && !messageIds.containsKey(name)) {
                messageIds.put(name, next);
                messagesById.put(next, m);
                entries.add(new RegistryEntry(name, next, RegistrySpace.MESSAGE));
                next++;
            }
        }
        for (String name : kindNames) {
            ExtensionHost.RegisteredKind<?> k = host.kind(name);
            if (k != null && !kindIds.containsKey(name)) {
                kindIds.put(name, next);
                kindsById.put(next, k);
                entries.add(new RegistryEntry(name, next, RegistrySpace.KIND));
                next++;
            }
        }
    }

    /** Returns the {@code welcome} registry entries. */
    public List<RegistryEntry> entries() {
        return List.copyOf(entries);
    }

    /** Returns the extension message for a wire id, or null. */
    public ExtensionHost.@Nullable RegisteredMessage<?> message(int id) {
        return messagesById.get(id);
    }

    /** Returns the wire id of an extension message, or -1 when not negotiated. */
    public int messageId(String name) {
        return messageIds.getInt(name);
    }

    /** Resolves a wire kind id: core kinds below {@code 0x100}, negotiated extension kinds above. */
    public @Nullable KindType<?> kind(int id) {
        if (id < FIRST_ID) {
            return CoreKinds.byId(id);
        }
        ExtensionHost.RegisteredKind<?> k = kindsById.get(id);
        return k == null ? null : k.kind().type();
    }

    /** Returns the extension kind for a wire id, or null for core or unknown kinds. */
    public ExtensionHost.@Nullable RegisteredKind<?> extensionKind(int id) {
        return kindsById.get(id);
    }

    /**
     * Returns the wire id of a kind on this connection: the fixed id of core kinds, the negotiated id
     * of extension kinds, or -1 when the connection cannot name the kind.
     */
    public int kindId(KindType<?> kind) {
        if (kind.id() >= 0 && kind.id() < FIRST_ID && CoreKinds.byId(kind.id()) == kind) {
            return kind.id();
        }
        return kindIds.getInt(kind.name());
    }
}
