package io.github.pix3lpirat3.mfbridge.core;

import io.github.pix3lpirat3.mfbridge.core.blob.BlobRef;
import io.github.pix3lpirat3.mfbridge.core.traits.Traits;
import io.github.pix3lpirat3.mfbridge.protocol.core.HoverStyle;
import io.github.pix3lpirat3.mfbridge.protocol.core.Interactive;
import io.github.pix3lpirat3.mfbridge.protocol.core.ObjectTraits;
import io.github.pix3lpirat3.mfbridge.protocol.core.Scope;
import io.github.pix3lpirat3.mfbridge.protocol.core.StyleData;
import io.github.pix3lpirat3.mfbridge.protocol.core.WorldKey;
import io.github.pix3lpirat3.mfbridge.protocol.wire.KindType;
import org.jspecify.annotations.Nullable;

/**
 * One visible object in a {@link RenderSnapshot}. Immutable as long as renderers treat {@code data}
 * as read-only (generated records are immutable; packed arrays inside them must not be modified).
 *
 * @param handle number of the object while it is in the store; a later object may get it once this one is gone
 * @param key full object key {@code <owner>/<extension>/<local id>}
 * @param owner owner part without trailing slash, for example {@code bot:<identity>} or {@code
 *     shared}
 * @param extension extension segment, for example {@code bridge:pathfinder-viz}
 * @param localId local id within owner and extension
 * @param layer local layer name, or null when the object is in no layer
 * @param kind object kind; core kinds are the constants in {@code CoreKinds}
 * @param rev object revision
 * @param data decoded kind data: the generated record for core kinds, the extension codec's value
 *     for extension kinds, or null when the kind is unknown to this mod
 * @param style resolved {@code core:style} referenced by the data's {@code style} field, or null
 * @param scope world or global
 * @param world world key for world-scoped objects, or null
 * @param blob blob the data refers to (retained-tier kinds), or null; see {@link RenderSnapshot#blobs()}
 * @param traits object traits, or null; read them through {@link #interactive()}, {@link
 *     #hover()} and {@link Traits}, which apply the per-kind aliases
 */
public record RenderEntry(
        int handle,
        String key,
        String owner,
        String extension,
        String localId,
        @Nullable String layer,
        KindType<?> kind,
        long rev,
        @Nullable Object data,
        @Nullable StyleData style,
        Scope scope,
        @Nullable WorldKey world,
        @Nullable BlobRef blob,
        @Nullable ObjectTraits traits) {

    /** Creates an entry that refers to no blob and has no traits. */
    public RenderEntry(int handle, String key, String owner, String extension, String localId, @Nullable String layer,
            KindType<?> kind, long rev, @Nullable Object data, @Nullable StyleData style, Scope scope,
            @Nullable WorldKey world) {
        this(handle, key, owner, extension, localId, layer, kind, rev, data, style, scope, world, null, null);
    }

    /** Returns the effective interaction spec: the traits' one, else the kind's own field, else null. */
    public @Nullable Interactive interactive() {
        return Traits.interactive(traits, data);
    }

    /** Returns the effective hover style: the traits' one, else the kind's own field, else null. */
    public @Nullable HoverStyle hover() {
        return Traits.hover(traits, data);
    }
}
