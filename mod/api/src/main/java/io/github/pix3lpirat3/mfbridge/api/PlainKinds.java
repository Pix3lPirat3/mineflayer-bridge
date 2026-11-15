package io.github.pix3lpirat3.mfbridge.api;

import io.github.pix3lpirat3.mfbridge.protocol.wire.KindType;
import io.github.pix3lpirat3.mfbridge.protocol.wire.WireCodecs;
import org.jspecify.annotations.Nullable;

/**
 * Object kinds carrying plain values, for extensions that do not use the bridge's code generator.
 * The Node half declares the same name with {@code plainKind} from the plugin.
 *
 * <p>The data of such an object is one plain value, usually a map: {@code null}, {@link Boolean},
 * {@link Long} or {@link Double}, {@link String}, {@code List<Object>} and {@code Map<String,
 * Object>} of these, with the rules {@link PlainMessages} gives for numbers and maps. Since the
 * value has no schema, a {@link KindValidator} is the place to check its shape.
 */
public final class PlainKinds {
    private PlainKinds() {}

    /**
     * Builds a plain-value object kind, to wrap in an {@link ObjectKind} and register with {@link
     * ExtensionRegistrar#kind}.
     *
     * @param name the kind name, starting with the extension's namespace and {@code :}
     * @return the kind; the id it carries is unused, since each connection assigns one in {@code welcome}
     */
    public static KindType<@Nullable Object> type(String name) {
        return new KindType<>(0, name, WireCodecs.ANY);
    }

    /**
     * Declares a plain-value object kind with no check of its data and no store observer.
     *
     * @param name the kind name, starting with the extension's namespace and {@code :}
     * @param version the version of the kind's shape
     * @return the kind, to register with {@link ExtensionRegistrar#kind}
     */
    public static ObjectKind<@Nullable Object> kind(String name, SemVer version) {
        return ObjectKind.of(type(name), version);
    }

    /**
     * Declares a plain-value object kind.
     *
     * @param name the kind name, starting with the extension's namespace and {@code :}
     * @param version the version of the kind's shape
     * @param validator checks the value of every write; a plain kind has no other schema
     * @param handler hears of every write and removal
     * @return the kind, to register with {@link ExtensionRegistrar#kind}
     */
    public static ObjectKind<@Nullable Object> kind(String name, SemVer version, KindValidator<@Nullable Object> validator,
            StoreHandler<@Nullable Object> handler) {
        return new ObjectKind<>(type(name), version, validator, handler);
    }
}
