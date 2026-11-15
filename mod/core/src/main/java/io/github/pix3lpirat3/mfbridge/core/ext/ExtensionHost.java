package io.github.pix3lpirat3.mfbridge.core.ext;

import io.github.pix3lpirat3.mfbridge.api.BridgeExtension;
import io.github.pix3lpirat3.mfbridge.api.ExtensionRegistrar;
import io.github.pix3lpirat3.mfbridge.api.MessageAccess;
import io.github.pix3lpirat3.mfbridge.api.MessageHandler;
import io.github.pix3lpirat3.mfbridge.api.ObjectKind;
import io.github.pix3lpirat3.mfbridge.api.Setting;
import io.github.pix3lpirat3.mfbridge.api.SettingValue;
import io.github.pix3lpirat3.mfbridge.api.WorldMatcher;
import io.github.pix3lpirat3.mfbridge.api.event.EventBus;
import io.github.pix3lpirat3.mfbridge.api.thread.ClientThread;
import io.github.pix3lpirat3.mfbridge.core.remote.Permission;
import io.github.pix3lpirat3.mfbridge.core.world.WorldMatchers;
import io.github.pix3lpirat3.mfbridge.protocol.core.CoreKinds;
import io.github.pix3lpirat3.mfbridge.protocol.core.CoreMessages;
import io.github.pix3lpirat3.mfbridge.protocol.core.ExtensionRef;
import io.github.pix3lpirat3.mfbridge.protocol.wire.KindType;
import io.github.pix3lpirat3.mfbridge.protocol.wire.MessageType;
import io.github.pix3lpirat3.mfbridge.protocol.wire.WireCodecs;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Holds what mod-side extensions registered, and negotiates per-connection ids for extension
 * messages and kinds. Registration happens once at start on the client thread.
 */
@ClientThread
public final class ExtensionHost {
    /**
     * A registered extension message.
     *
     * @param extensionId owning extension
     * @param type message type; its id is ignored
     * @param handler inbound handler
     * @param permission the permission a session needs to send it, or null for none
     * @param attached whether the player must have the sending session attached
     * @param <T> message type
     */
    public record RegisteredMessage<T>(String extensionId, MessageType<T> type, MessageHandler<T> handler,
            @Nullable Permission permission, boolean attached) {}

    /**
     * A registered extension kind.
     *
     * @param extensionId owning extension
     * @param kind the kind
     * @param <T> data type
     */
    public record RegisteredKind<T>(String extensionId, ObjectKind<T> kind) {}

    private static final Map<String, KindType<?>> CORE_KINDS = new HashMap<>();

    static {
        for (KindType<?> k : CoreKinds.ALL) {
            CORE_KINDS.put(k.name(), k);
        }
    }

    private final Map<String, RegisteredMessage<?>> messages = new LinkedHashMap<>();
    private final Map<String, RegisteredKind<?>> kinds = new LinkedHashMap<>();
    private final Map<String, KindType<?>> unknownKinds = new HashMap<>();
    private final Map<String, SettingImpl<?>> settings = new LinkedHashMap<>();
    private final List<ExtensionRef> refs = new ArrayList<>();
    private final WorldMatchers matchers;
    private final EventBus events;
    private final ExtensionSettingsFile saved;

    /** Creates a host that adds registered matchers to {@code matchers}; settings live in memory. */
    public ExtensionHost(WorldMatchers matchers, EventBus events) {
        this(matchers, events, ExtensionSettingsFile.memory());
    }

    /** Creates a host whose settings start from, and save to, {@code saved}. */
    public ExtensionHost(WorldMatchers matchers, EventBus events, ExtensionSettingsFile saved) {
        this.matchers = matchers;
        this.events = events;
        this.saved = saved;
    }

    /**
     * Runs an extension's registration.
     *
     * @throws IllegalArgumentException when the extension is already registered or registers a
     *     conflicting name
     */
    public void register(BridgeExtension extension) {
        String id = extension.id().toString();
        for (ExtensionRef r : refs) {
            if (r.id().equals(id)) {
                throw new IllegalArgumentException("extension registered twice: " + id);
            }
        }
        refs.add(new ExtensionRef(id, extension.version().toString()));
        extension.register(new Registrar(id, extension.id().namespace()));
    }

    /** Returns the registered extensions, for {@code welcome}. */
    public List<ExtensionRef> refs() {
        return List.copyOf(refs);
    }

    /** Returns a registered message by name, or null. */
    public @Nullable RegisteredMessage<?> message(String name) {
        return messages.get(name);
    }

    /** Returns a registered extension kind by name, or null. */
    public @Nullable RegisteredKind<?> kind(String name) {
        return kinds.get(name);
    }

    /**
     * Resolves a kind name to a kind type: a core kind, an extension kind, or a placeholder that
     * decodes data as a raw MessagePack value, for persisted objects of kinds no extension provides.
     */
    public KindType<?> kindByName(String name) {
        KindType<?> core = CORE_KINDS.get(name);
        if (core != null) {
            return core;
        }
        RegisteredKind<?> ext = kinds.get(name);
        if (ext != null) {
            return ext.kind().type();
        }
        return unknownKinds.computeIfAbsent(name, n -> new KindType<>(-1, n, WireCodecs.ANY));
    }

    /** Returns a registered setting by {@code <extension id>/<key>}, or null. */
    public @Nullable SettingValue<?> setting(String qualifiedKey) {
        return settings.get(qualifiedKey);
    }

    /** Returns one extension's settings in registration order, for the settings screen. */
    public List<SettingValue<?>> settings(String extensionId) {
        String prefix = extensionId + '/';
        List<SettingValue<?>> out = new ArrayList<>();
        settings.forEach((k, v) -> {
            if (k.startsWith(prefix)) {
                out.add(v);
            }
        });
        return out;
    }

    /** Assigns ids for the names a plugin listed in {@code hello} that this mod supports. */
    public ConnRegistry negotiate(List<String> messageNames, List<String> kindNames) {
        return new ConnRegistry(this, messageNames, kindNames);
    }

    private final class Registrar implements ExtensionRegistrar {
        private final String extensionId;
        private final String namespacePrefix;

        Registrar(String extensionId, String namespace) {
            this.extensionId = extensionId;
            this.namespacePrefix = namespace + ':';
        }

        private void checkName(String name) {
            if (!name.startsWith(namespacePrefix) || name.length() == namespacePrefix.length()) {
                throw new IllegalArgumentException(name + " is outside namespace " + namespacePrefix);
            }
            if (messages.containsKey(name) || kinds.containsKey(name) || CORE_KINDS.containsKey(name)) {
                throw new IllegalArgumentException("name already registered: " + name);
            }
            for (MessageType<?> t : CoreMessages.ALL) {
                if (t.name().equals(name)) {
                    throw new IllegalArgumentException("name already registered: " + name);
                }
            }
        }

        @Override
        public <T> void kind(ObjectKind<T> kind) {
            checkName(kind.type().name());
            kinds.put(kind.type().name(), new RegisteredKind<>(extensionId, kind));
        }

        @Override
        public <T> void message(MessageType<T> type, MessageAccess access, MessageHandler<T> handler) {
            checkName(type.name());
            if (!type.from().allowsFromNode() && !type.from().allowsFromMod()) {
                throw new IllegalArgumentException("message has no direction: " + type.name());
            }
            String key = access.permission();
            Permission permission = key == null ? null : Permission.byKey(key);
            if (key != null && permission == null) {
                throw new IllegalArgumentException("unknown permission " + key + " for " + type.name());
            }
            messages.put(type.name(), new RegisteredMessage<>(extensionId, type, handler, permission, access.attached()));
        }

        @Override
        public void matcher(WorldMatcher matcher, int priority) {
            matchers.add(matcher, priority);
        }

        @Override
        public <T> SettingValue<T> setting(Setting<T> setting) {
            String qualified = extensionId + '/' + setting.key();
            if (settings.containsKey(qualified)) {
                throw new IllegalArgumentException("setting already registered: " + qualified);
            }
            SettingImpl<T> value = new SettingImpl<>(qualified, setting, saved);
            settings.put(qualified, value);
            return value;
        }

        @Override
        public EventBus events() {
            return events;
        }
    }

    private static final class SettingImpl<T> implements SettingValue<T> {
        private final String qualified;
        private final Setting<T> setting;
        private final ExtensionSettingsFile saved;
        private volatile T value;

        SettingImpl(String qualified, Setting<T> setting, ExtensionSettingsFile saved) {
            this.qualified = qualified;
            this.setting = setting;
            this.saved = saved;
            T stored = saved.get(qualified, setting);
            this.value = stored == null ? setting.defaultValue() : stored;
        }

        @Override
        public Setting<T> setting() {
            return setting;
        }

        @Override
        public T get() {
            return value;
        }

        @Override
        public void set(T next) {
            if (!setting.type().isInstance(next)) {
                throw new IllegalArgumentException("value does not match " + setting.type().getName());
            }
            value = next;
            saved.put(qualified, next);
        }
    }

    /** Returns the registration of an extension kind, or null for core and unknown kinds. */
    public @Nullable RegisteredKind<?> registered(KindType<?> kind) {
        return kind.id() < 0 ? null : kind(kind.name());
    }
}
