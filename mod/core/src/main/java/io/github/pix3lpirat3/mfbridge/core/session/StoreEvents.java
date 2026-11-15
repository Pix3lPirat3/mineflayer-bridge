package io.github.pix3lpirat3.mfbridge.core.session;

import io.github.pix3lpirat3.mfbridge.api.StoreHandler;
import io.github.pix3lpirat3.mfbridge.api.thread.ClientThread;
import io.github.pix3lpirat3.mfbridge.core.blob.BlobCache;
import io.github.pix3lpirat3.mfbridge.core.blob.BlobRef;
import io.github.pix3lpirat3.mfbridge.core.ext.ExtensionHost;
import io.github.pix3lpirat3.mfbridge.core.store.ObjectStore;
import io.github.pix3lpirat3.mfbridge.core.store.StoredObject;
import io.github.pix3lpirat3.mfbridge.protocol.core.CoreKinds;
import io.github.pix3lpirat3.mfbridge.protocol.core.LayerData;
import io.github.pix3lpirat3.mfbridge.protocol.wire.KindType;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * Tells each part of the engine what a store change means for it: which snapshot to look at again,
 * blob pins, quota counts, layers, settings, variables and the handlers of extension kinds.
 */
@ClientThread
final class StoreEvents implements ObjectStore.Listener {
    private final Logger log;
    private final ExtensionHost extensions;
    private @Nullable RenderPublisher render;
    private @Nullable DeclarationPublisher declarations;
    private @Nullable VariablePublisher vars;
    private @Nullable BlobCache blobs;
    private @Nullable OwnerQuotas ownerQuotas;
    private @Nullable PlayerView view;
    private @Nullable BotSettings botSettings;
    private @Nullable Interactions interactions;

    StoreEvents(Logger log, ExtensionHost extensions) {
        this.log = log;
        this.extensions = extensions;
    }

    /** Sets the parts to tell; the store is built before them, and nothing is written until then. */
    void bind(RenderPublisher render, DeclarationPublisher declarations, VariablePublisher vars, BlobCache blobs,
            OwnerQuotas ownerQuotas, PlayerView view, BotSettings botSettings, Interactions interactions) {
        this.render = render;
        this.declarations = declarations;
        this.vars = vars;
        this.blobs = blobs;
        this.ownerQuotas = ownerQuotas;
        this.view = view;
        this.botSettings = botSettings;
        this.interactions = interactions;
    }

    @Override
    public void upserted(StoredObject o, StoredObject.@Nullable State previous) {
        BlobCache blobs = Objects.requireNonNull(this.blobs);
        OwnerQuotas ownerQuotas = Objects.requireNonNull(this.ownerQuotas);
        PlayerView view = Objects.requireNonNull(this.view);
        StoredObject.State st = o.state();
        changed(o, st.kind());
        if (previous != null && previous.kind() != st.kind()) {
            changed(o, previous.kind());
        }
        BlobRef blob = RenderPublisher.blobOf(st.decoded());
        if (blob != null) {
            blobs.pin(blob.hex());
            blobs.touch(blob.hex());
        }
        BlobRef old = previous == null ? null : RenderPublisher.blobOf(previous.decoded());
        if (old != null) {
            blobs.unpin(old.hex());
        }
        if (previous != null) {
            ownerQuotas.replaced(o, previous);
        }
        ownerQuotas.added(o, st);
        if (previous != null && previous.kind() == CoreKinds.LAYER && st.kind() != CoreKinds.LAYER) {
            if (view.layers().undeclare(o.key())) {
                view.ownerChanged(o.parts().owner());
            }
        }
        if (st.kind() == CoreKinds.LAYER && st.decoded() instanceof LayerData d) {
            if (view.layers().declare(o.key(), d.category(), d.defaultVisible())) {
                view.ownerChanged(o.parts().owner());
            }
        }
        if (st.kind() == CoreKinds.SETTINGS) {
            Objects.requireNonNull(botSettings).declared(o);
        }
        if (st.kind() == CoreKinds.VARS) {
            Objects.requireNonNull(vars).upserted(o);
        }
        ExtensionHost.RegisteredKind<?> ext = extensions.registered(st.kind());
        if (ext != null && st.decoded() != null) {
            notifyUpsert(ext.kind().handler(), o.key(), st.rev(), st.decoded());
        }
    }

    @Override
    public void removed(StoredObject o) {
        PlayerView view = Objects.requireNonNull(this.view);
        StoredObject.State st = o.state();
        changed(o, st.kind());
        BlobRef blob = RenderPublisher.blobOf(st.decoded());
        if (blob != null) {
            Objects.requireNonNull(blobs).unpin(blob.hex());
        }
        Objects.requireNonNull(ownerQuotas).removed(o, st);
        if (st.kind() == CoreKinds.LAYER && view.layers().undeclare(o.key())) {
            view.ownerChanged(o.parts().owner());
        }
        if (st.kind() == CoreKinds.TOOL) {
            Objects.requireNonNull(interactions).removed(o.key());
        }
        if (st.kind() == CoreKinds.VARS) {
            Objects.requireNonNull(vars).removed(o);
        }
        ExtensionHost.RegisteredKind<?> ext = extensions.registered(st.kind());
        if (ext != null) {
            try {
                ext.kind().handler().removed(o.key());
            } catch (RuntimeException e) {
                log.warn("Store handler failed for {}: {}", o.key(), e.toString());
            }
        }
    }

    /** Marks what a write or removal of this kind feeds; variables and layers are handled apart. */
    private void changed(StoredObject o, KindType<?> kind) {
        if (KindRoles.declaration(kind)) {
            Objects.requireNonNull(declarations).ownerChanged(o.parts().owner());
        } else if (kind == CoreKinds.STYLE) {
            Objects.requireNonNull(render).styleChanged(o);
        } else if (KindRoles.rendered(kind)) {
            Objects.requireNonNull(render).objectChanged(o);
        }
    }

    @SuppressWarnings("unchecked")
    private void notifyUpsert(StoreHandler<?> handler, String key, long rev, Object data) {
        try {
            ((StoreHandler<Object>) handler).upserted(key, rev, data);
        } catch (RuntimeException e) {
            log.warn("Store handler failed for {}: {}", key, e.toString());
        }
    }
}
