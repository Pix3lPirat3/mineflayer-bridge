package io.github.pix3lpirat3.mfbridge.common.ui.edit;

import io.github.pix3lpirat3.mfbridge.common.render.command.DrawCommand;
import io.github.pix3lpirat3.mfbridge.common.spatial.SpatialEditor;
import io.github.pix3lpirat3.mfbridge.common.spatial.SpatialWrites;
import io.github.pix3lpirat3.mfbridge.common.spatial.logic.Gizmo;
import io.github.pix3lpirat3.mfbridge.common.ui.UiContext;
import io.github.pix3lpirat3.mfbridge.common.ui.logic.FieldEditors;
import io.github.pix3lpirat3.mfbridge.common.ui.logic.FieldSpec;
import io.github.pix3lpirat3.mfbridge.common.ui.logic.FormValues;
import io.github.pix3lpirat3.mfbridge.common.ui.screen.ColorPickerScreen;
import io.github.pix3lpirat3.mfbridge.common.ui.screen.FormScreen;
import io.github.pix3lpirat3.mfbridge.common.ui.screen.PromptAnswers;
import io.github.pix3lpirat3.mfbridge.common.ui.screen.TextPromptScreen;
import io.github.pix3lpirat3.mfbridge.common.ui.surface.AnvilPromptScreen;
import io.github.pix3lpirat3.mfbridge.core.BridgeCore;
import io.github.pix3lpirat3.mfbridge.core.RenderEntry;
import io.github.pix3lpirat3.mfbridge.core.input.PromptInput;
import io.github.pix3lpirat3.mfbridge.core.store.PatchApplier;
import io.github.pix3lpirat3.mfbridge.core.traits.Traits;
import io.github.pix3lpirat3.mfbridge.protocol.core.PointData;
import io.github.pix3lpirat3.mfbridge.protocol.core.PromptKind;
import io.github.pix3lpirat3.mfbridge.protocol.core.PromptRequest;
import io.github.pix3lpirat3.mfbridge.protocol.core.Vec3d;
import io.github.pix3lpirat3.mfbridge.protocol.wire.KindType;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * Shift+click editing of objects with {@code traits.editable}. One field opens
 * its own editor: text in an inline editor or the anvil surface, a position with the move gizmo, a
 * colour in the colour picker, an enum cycles, a boolean toggles and a number opens a number field
 * that also scrolls. Several fields open one small form. Every result is one field patch written
 * through {@link SpatialWrites#write}, so it is one undo step and bot-owned objects go to their owner
 * as {@code obj.edit.propose}. Client thread only.
 */
public final class ObjectEditor {

    private final UiContext ui;
    private final SpatialWrites writes;
    private @Nullable RenderEntry moving;
    private FieldEditors.@Nullable Field movingField;
    private @Nullable PointData working;
    private Gizmo.@Nullable Drag drag;

    /**
     * Creates the editor.
     *
     * @param ui the UI services
     * @param writes the edit writer shared with the spatial tools
     */
    public ObjectEditor(final UiContext ui, final SpatialWrites writes) {
        this.ui = ui;
        this.writes = writes;
    }

    /**
     * Returns whether an object's editable fields can be edited now.
     *
     * @param key the object key
     * @return true when it is in the snapshot and declares editable fields
     */
    public boolean editable(final String key) {
        final RenderEntry e = entry(key);
        return e != null && e.blob() == null && !Traits.editable(e.traits()).isEmpty();
    }

    /**
     * Opens the editor of an object's editable fields.
     *
     * @param key the object key
     * @return whether the object has editable fields; the click is consumed then, whatever happens
     */
    public boolean open(final String key) {
        final RenderEntry e = entry(key);
        // Retained-tier objects (those with a blob) answer unsupported, so they are never edited here.
        if (e == null || e.data() == null || e.blob() != null || Traits.editable(e.traits()).isEmpty()) {
            return false;
        }
        final String name = name(e);
        if (writes.waiting(key)) {
            ui.toastError(name, I18n.get("mineflayer_bridge.edit.waiting"));
            return true;
        }
        final List<FieldEditors.Field> fields = FieldEditors.fields(e.data(), Traits.editable(e.traits()));
        if (fields.isEmpty()) {
            ui.toastError(name, I18n.get("mineflayer_bridge.edit.no_fields"));
            return true;
        }
        if (fields.size() > 1) {
            form(e, fields);
            return true;
        }
        final FieldEditors.Field f = fields.getFirst();
        final Minecraft minecraft = Minecraft.getInstance();
        switch (f.editor()) {
            case TOGGLE, CYCLE -> propose(e, Map.of(f.name(), FieldEditors.next(f)), I18n.get("mineflayer_bridge.edit.change", f.label()));
            case TEXT -> minecraft.gui.setScreen(new TextPromptScreen(prompt(e, f), answers(e, f)));
            case ANVIL -> minecraft.gui.setScreen(new AnvilPromptScreen(prompt(e, f), answers(e, f)));
            case COLOR -> minecraft.gui.setScreen(new ColorPickerScreen(Component.literal(name + ": " + f.label()),
                    f.value() instanceof Integer argb ? argb : 0xFFFFFFFF,
                    argb -> propose(e, Map.of(f.name(), argb), I18n.get("mineflayer_bridge.edit.change", f.label()))));
            case NUMBER -> form(e, fields);
            case GIZMO -> {
                if (f.value() instanceof Vec3d pos) {
                    moving = e;
                    movingField = f;
                    working = new PointData(f.label(), pos);
                    drag = null;
                }
            }
        }
        return true;
    }

    private @Nullable RenderEntry entry(final String key) {
        final BridgeCore core = ui.core();
        if (core == null) {
            return null;
        }
        return core.renderSnapshot().find(key);
    }

    private static String name(final RenderEntry e) {
        return e.localId().isEmpty() ? e.key() : e.localId();
    }

    /** A local text prompt, so the bot prompt surfaces edit text without a bot request. */
    private static PromptInput prompt(final RenderEntry e, final FieldEditors.Field f) {
        final PromptRequest r = new PromptRequest(PromptKind.TEXT, name(e) + ": " + f.label(), null, f.value(), 0, null,
                "screen", "minecraft:name_tag", null, null, null, null, null, null);
        return new PromptInput(-1L, name(e), null, 0, r, 0L, Long.MAX_VALUE);
    }

    private PromptAnswers answers(final RenderEntry e, final FieldEditors.Field f) {
        final PromptInput[] active = {prompt(e, f)};
        return new PromptAnswers() {
            @Override
            public @Nullable PromptInput active() {
                return active[0];
            }

            @Override
            public void answer(final Object value) {
                active[0] = null;
                propose(e, Map.of(f.name(), value), I18n.get("mineflayer_bridge.edit.edit", f.label()));
            }

            @Override
            public long remainingMs() {
                return Long.MAX_VALUE;
            }
        };
    }

    private void form(final RenderEntry e, final List<FieldEditors.Field> fields) {
        final List<FieldSpec> specs = new ArrayList<>(fields.size());
        for (final FieldEditors.Field f : fields) {
            specs.add(spec(f));
        }
        Minecraft.getInstance().gui.setScreen(new FormScreen(Component.translatable("mineflayer_bridge.edit.edit", name(e)),
                Component.literal(e.owner()), specs, Component.translatable("screen.mineflayer_bridge.form.save"), raw -> {
                    final FormValues.Result r = FormValues.validate(I18n::get, specs, raw);
                    if (!r.ok()) {
                        return r.error();
                    }
                    final Map<String, Object> changes = new HashMap<>();
                    for (int i = 0; i < fields.size(); i++) {
                        final FieldEditors.Field f = fields.get(i);
                        final Object v = r.values().get(f.name());
                        // Inputs show rounded numbers, so an untouched field keeps its exact value.
                        if (v != null && !Objects.equals(raw.get(f.name()), FormValues.initial(specs.get(i)))) {
                            changes.put(f.name(), v);
                        }
                    }
                    try {
                        propose(e, changes, I18n.get("mineflayer_bridge.edit.edit", name(e)));
                    } catch (final IllegalArgumentException ex) {
                        return ex.getMessage();
                    }
                    return null;
                }, () -> {
                }, null, null));
    }

    private static FieldSpec spec(final FieldEditors.Field f) {
        final Object v = f.value();
        return switch (f.editor()) {
            case TEXT, ANVIL -> new FieldSpec(f.name(), f.label(), FieldSpec.Kind.TEXT, v, null, null, null, List.of(), false);
            case COLOR -> new FieldSpec(f.name(), I18n.get("mineflayer_bridge.edit.color_field", f.label()), FieldSpec.Kind.TEXT,
                    v instanceof Integer argb ? FieldEditors.colorText(argb) : null, null, null, null, List.of(), true);
            case NUMBER -> new FieldSpec(f.name(), f.label(), FieldEditors.whole(f.type()) ? FieldSpec.Kind.INTEGER : FieldSpec.Kind.NUMBER,
                    v, null, null, null, List.of(), true);
            case TOGGLE -> new FieldSpec(f.name(), f.label(), FieldSpec.Kind.TOGGLE, v, null, null, null, List.of(), false);
            case CYCLE -> {
                if (!f.type().isEnum()) {
                    yield new FieldSpec(f.name(), f.label(), FieldSpec.Kind.TOGGLE, v, null, null, null, List.of(), false);
                }
                final List<String> options = new ArrayList<>();
                for (final Object c : f.type().getEnumConstants()) {
                    options.add(((Enum<?>) c).name());
                }
                yield new FieldSpec(f.name(), f.label(), FieldSpec.Kind.SELECT, v == null ? null : ((Enum<?>) v).name(), null, null, null,
                        options, true);
            }
            case GIZMO -> new FieldSpec(f.name(), f.label(), FieldSpec.Kind.VEC3,
                    v instanceof Vec3d p ? List.of(p.x(), p.y(), p.z()) : null, null, null, null, List.of(), true);
        };
    }

    /**
     * Writes field changes as one patch and one undo step.
     *
     * @throws IllegalArgumentException when a value does not fit its field
     */
    @SuppressWarnings("unchecked")
    private void propose(final RenderEntry e, final Map<String, Object> raw, final String label) {
        final Object data = e.data();
        if (data == null) {
            return;
        }
        final List<FieldEditors.Field> fields = FieldEditors.fields(data, Traits.editable(e.traits()));
        final Map<String, @Nullable Object> changes = new HashMap<>();
        final List<Integer> indices = new ArrayList<>();
        for (final FieldEditors.Field f : fields) {
            final Object v = raw.get(f.name());
            if (v != null) {
                changes.put(f.name(), FieldEditors.convert(I18n::get, f, v));
                indices.add(f.index());
            }
        }
        if (indices.isEmpty()) {
            return;
        }
        final Object next = FieldEditors.with(I18n::get, data, changes);
        final KindType<Object> kind = (KindType<Object>) e.kind();
        final byte[] encoded = kind.encode(next);
        final int[] at = new int[indices.size()];
        for (int i = 0; i < at.length; i++) {
            at[i] = indices.get(i);
        }
        writes.write(e.key(), e.owner(), e.rev(), name(e), label + " (" + name(e) + ")", e.kind(), encoded,
                PatchApplier.fieldsPatch(encoded, at), null);
    }

    /** Returns whether the move gizmo is out for a position field. */
    public boolean moving() {
        return moving != null;
    }

    /**
     * Handles a primary press while the move gizmo is out: a hit on a handle starts a drag.
     *
     * @return whether a handle was hit
     */
    public boolean press() {
        final PointData w = working;
        if (w == null || drag != null) {
            return drag != null;
        }
        final Gizmo.Hit hit = Gizmo.hit(handles(w), SpatialEditor.ray(), SpatialEditor.REACH);
        if (hit == null) {
            return false;
        }
        drag = new Gizmo.Drag(hit, w, SpatialEditor.ray());
        return true;
    }

    /** Ends a drag: the moved position is written once and the gizmo goes away. */
    public void release() {
        final Gizmo.Drag d = drag;
        final RenderEntry e = moving;
        final FieldEditors.Field f = movingField;
        if (d == null || e == null || f == null) {
            return;
        }
        drag = null;
        if (d.update(SpatialEditor.ray(), SpatialEditor.snap()) instanceof PointData p && !p.pos().equals(f.value())) {
            propose(e, Map.of(f.name(), p.pos()), I18n.get("mineflayer_bridge.edit.move", f.label()));
            cancel();
        }
    }

    /**
     * Changes the grab distance of the running drag.
     *
     * @param steps whole scroll steps
     * @return whether a drag took them
     */
    public boolean scroll(final int steps) {
        final Gizmo.Drag d = drag;
        if (d != null) {
            d.scroll(steps);
        }
        return d != null;
    }

    /** Puts the move gizmo away without writing. */
    public void cancel() {
        moving = null;
        movingField = null;
        working = null;
        drag = null;
    }

    /**
     * Adds the move gizmo for this frame.
     *
     * @param out the frame's command list
     */
    public void preview(final List<DrawCommand> out) {
        final PointData w = working;
        if (w == null) {
            return;
        }
        final Gizmo.Drag d = drag;
        final PointData shown = d != null && d.update(SpatialEditor.ray(), SpatialEditor.snap()) instanceof PointData p ? p : w;
        final List<Gizmo.Handle> handles = handles(shown);
        final Gizmo.Hit hover = d == null ? Gizmo.hit(handles, SpatialEditor.ray(), SpatialEditor.REACH) : null;
        SpatialEditor.handles(out, handles, d != null ? d.handle() : hover == null ? null : hover.handle(), -1);
    }

    /**
     * Returns the hint while the move gizmo is out.
     *
     * @return the keys line, or empty
     */
    public String hint() {
        final FieldEditors.Field f = movingField;
        return f == null ? "" : I18n.get("mineflayer_bridge.edit.moving", f.label());
    }

    /** Point handles without the yaw ring: a position has no direction. */
    private static List<Gizmo.Handle> handles(final PointData p) {
        final List<Gizmo.Handle> out = new ArrayList<>();
        for (final Gizmo.Handle h : Gizmo.handles(p, -1)) {
            if (h.type() != Gizmo.Type.YAW) {
                out.add(h);
            }
        }
        return out;
    }
}
