package io.github.pix3lpirat3.mfbridge.common.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.pix3lpirat3.mfbridge.common.render.command.DrawCommand;
import io.github.pix3lpirat3.mfbridge.core.RenderEntry;
import io.github.pix3lpirat3.mfbridge.core.RenderSnapshot;
import io.github.pix3lpirat3.mfbridge.core.blob.BlobStore;
import io.github.pix3lpirat3.mfbridge.core.vars.Variables;
import io.github.pix3lpirat3.mfbridge.protocol.core.BoxData;
import io.github.pix3lpirat3.mfbridge.protocol.core.CoreKinds;
import io.github.pix3lpirat3.mfbridge.protocol.core.LabelData;
import io.github.pix3lpirat3.mfbridge.protocol.core.Scope;
import io.github.pix3lpirat3.mfbridge.protocol.core.Vec3d;
import io.github.pix3lpirat3.mfbridge.protocol.wire.KindType;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

class SnapshotCommandsTest {
    private static final String P = "bot:miner@example.com/e/";

    private final TreeMap<String, RenderEntry> visible = new TreeMap<>();
    private RenderSnapshot snapshot = RenderSnapshot.EMPTY;

    private static RenderEntry entry(final String id, final KindType<?> kind, final long rev, final Object data) {
        return new RenderEntry(0, P + id, "bot:miner@example.com", "e", id, null, kind, rev, data, null, Scope.GLOBAL, null);
    }

    private static RenderEntry box(final String id, final long rev, final double x) {
        return entry(id, CoreKinds.BOX, rev, new BoxData(new Vec3d(x, 0, 0), new Vec3d(1, 1, 1), null));
    }

    private static RenderEntry label(final String id, final long rev, final String text) {
        return entry(id, CoreKinds.LABEL, rev, new LabelData(new Vec3d(0, 64, 0), text, 1, 0x40000000, 64, null));
    }

    /** Publishes the next snapshot the way the core does: one version on, with the change listed. */
    private RenderSnapshot publish(final List<RenderEntry> upserted, final List<String> removedKeys) {
        final List<RenderEntry> removed = new ArrayList<>();
        for (final String key : removedKeys) {
            removed.add(visible.remove(P + key));
        }
        for (final RenderEntry e : upserted) {
            visible.put(e.key(), e);
        }
        snapshot = new RenderSnapshot(snapshot.version() + 1, visible.values(), BlobStore.NONE,
                new RenderSnapshot.Change(snapshot.version(), upserted, removed));
        return snapshot;
    }

    private static Variables vars(final long version, final @Nullable Object done) {
        final Map<String, @Nullable Object> values = new java.util.HashMap<>();
        values.put("done", done);
        return new Variables(version, Map.of("bot:miner@example.com/e", values));
    }

    @Test
    void onlyChangedEntriesAreConvertedAgain() {
        final SnapshotCommands commands = new SnapshotCommands();
        final SnapshotCommands.Converted first = commands.update(
                publish(List.of(box("a", 1, 1), box("b", 1, 2), label("c", 1, "plain")), List.of()), Variables.EMPTY);
        assertEquals(3, first.commands().size());
        assertSame(first, commands.update(snapshot, Variables.EMPTY), "nothing changed");

        final SnapshotCommands.Converted second = commands.update(publish(List.of(box("b", 2, 9)), List.of()), Variables.EMPTY);
        assertNotSame(first.commands(), second.commands());
        assertSame(first.commands().get(0), second.commands().get(0), "a was not converted again");
        assertNotSame(first.commands().get(1), second.commands().get(1));
        assertSame(first.commands().get(2), second.commands().get(2), "nor was the label");
        assertEquals(9, ((DrawCommand.Box) second.commands().get(1)).min().x());

        final SnapshotCommands.Converted third = commands.update(publish(List.of(), List.of("a")), Variables.EMPTY);
        assertEquals(2, third.commands().size());
        assertSame(second.commands().get(1), third.commands().get(0));

        // An entry that draws nothing here leaves the list as it is.
        final SnapshotCommands.Converted fourth = commands.update(
                publish(List.of(entry("task", CoreKinds.ZONE, 1, "not drawn here")), List.of()), Variables.EMPTY);
        assertSame(third, fourth);
    }

    @Test
    void entriesAreKeptInKeyOrder() {
        final SnapshotCommands commands = new SnapshotCommands();
        commands.update(publish(List.of(box("m", 1, 1)), List.of()), Variables.EMPTY);
        commands.update(publish(List.of(box("z", 1, 3)), List.of()), Variables.EMPTY);
        final SnapshotCommands.Converted c = commands.update(publish(List.of(box("a", 1, 0), box("n", 1, 2)), List.of()),
                Variables.EMPTY);
        final List<Double> xs = new ArrayList<>();
        for (final DrawCommand d : c.commands()) {
            xs.add(((DrawCommand.Box) d).min().x());
        }
        assertEquals(List.of(0d, 1d, 2d, 3d), xs);
    }

    @Test
    void aMissedSnapshotStartsOver() {
        final SnapshotCommands commands = new SnapshotCommands();
        commands.update(publish(List.of(box("a", 1, 1)), List.of()), Variables.EMPTY);
        publish(List.of(box("b", 1, 2)), List.of());
        final SnapshotCommands.Converted c = commands.update(publish(List.of(), List.of("a")), Variables.EMPTY);
        assertEquals(1, c.commands().size());
        assertEquals(2, ((DrawCommand.Box) c.commands().get(0)).min().x());
    }

    @Test
    void aVariableChangeTouchesOnlyTextThatReadsDifferently() {
        final SnapshotCommands commands = new SnapshotCommands();
        final SnapshotCommands.Converted first = commands.update(
                publish(List.of(box("a", 1, 1), label("plain", 1, "plain"), label("t", 1, "done {done}")), List.of()), vars(1, 3L));
        assertEquals("done 3", ((DrawCommand.Label) first.commands().get(2)).text());

        assertSame(first, commands.update(snapshot, vars(2, 3L)), "the variable kept its value");

        final SnapshotCommands.Converted changed = commands.update(snapshot, vars(3, 4L));
        assertEquals("done 4", ((DrawCommand.Label) changed.commands().get(2)).text());
        assertSame(first.commands().get(0), changed.commands().get(0));
        assertSame(first.commands().get(1), changed.commands().get(1));

        final SnapshotCommands withoutTemplates = new SnapshotCommands();
        final RenderSnapshot plain = new RenderSnapshot(1, List.of(box("a", 1, 1), label("plain", 1, "plain")));
        final SnapshotCommands.Converted before = withoutTemplates.update(plain, vars(1, 3L));
        assertSame(before, withoutTemplates.update(plain, vars(2, 4L)), "no placeholders, nothing to do");
        assertTrue(before.entityTags().isEmpty());
    }
}
