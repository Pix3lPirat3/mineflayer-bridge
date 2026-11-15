package io.github.pix3lpirat3.mfbridge.api;

import io.github.pix3lpirat3.mfbridge.protocol.wire.KindType;

/**
 * An object kind contributed by an extension. The {@link
 * KindType#id()} of an extension kind is ignored: extension kind ids are assigned per connection in
 * {@code welcome}, by {@link KindType#name()}.
 *
 * @param type kind name and generated codec
 * @param version kind schema version
 * @param validator data validator, run for every write
 * @param handler store change observer
 * @param <T> decoded data type
 */
public record ObjectKind<T>(KindType<T> type, SemVer version, KindValidator<T> validator, StoreHandler<T> handler) {
    /** Creates a kind with no validation beyond decoding and no store observer. */
    public static <T> ObjectKind<T> of(KindType<T> type, SemVer version) {
        return new ObjectKind<>(type, version, KindValidator.acceptAll(), new StoreHandler<>() {});
    }
}
