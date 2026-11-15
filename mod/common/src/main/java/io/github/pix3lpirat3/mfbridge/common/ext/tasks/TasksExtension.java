package io.github.pix3lpirat3.mfbridge.common.ext.tasks;

import io.github.pix3lpirat3.mfbridge.api.client.ObjectView;
import com.mojang.blaze3d.platform.InputConstants;
import io.github.pix3lpirat3.mfbridge.api.ExtensionId;
import io.github.pix3lpirat3.mfbridge.api.ExtensionRegistrar;
import io.github.pix3lpirat3.mfbridge.api.MessageContext;
import io.github.pix3lpirat3.mfbridge.api.ObjectKind;
import io.github.pix3lpirat3.mfbridge.api.SemVer;
import io.github.pix3lpirat3.mfbridge.api.client.ClientBridge;
import io.github.pix3lpirat3.mfbridge.api.client.ClientExtension;
import io.github.pix3lpirat3.mfbridge.api.client.ClientRegistrar;
import io.github.pix3lpirat3.mfbridge.api.client.HomeTab;
import io.github.pix3lpirat3.mfbridge.common.ext.tasks.wire.BridgeTaskControl;
import io.github.pix3lpirat3.mfbridge.common.ext.tasks.wire.TaskAction;
import io.github.pix3lpirat3.mfbridge.common.ext.tasks.wire.TaskData;
import io.github.pix3lpirat3.mfbridge.common.ext.tasks.wire.TaskState;
import io.github.pix3lpirat3.mfbridge.common.ext.tasks.wire.TasksWire;
import io.github.pix3lpirat3.mfbridge.common.ui.screen.UiColors;
import io.github.pix3lpirat3.mfbridge.protocol.core.ScreenItem;
import io.github.pix3lpirat3.mfbridge.protocol.core.ScreenRow;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * {@code bridge:tasks}, the mod half: a Tasks tab on Bridge home lists every
 * bot's {@code bridge:task} objects with their progress and subtasks and pauses, resumes or cancels
 * them, and one key pauses or resumes every bot's tasks. Answers go to the bot as {@code
 * bridge:task.control}; the bot's Node half applies them.
 */
public final class TasksExtension implements ClientExtension {

    /** The extension id. */
    public static final ExtensionId ID = new ExtensionId("bridge", "tasks");

    private static final String SEP = "|";

    private @Nullable ClientBridge bridge;

    /** A task as the tab shows it. */
    private record Row(String owner, String id, TaskData data) {}

    @Override
    public ExtensionId id() {
        return ID;
    }

    @Override
    public SemVer version() {
        return SemVer.parse("1.0.0");
    }

    @Override
    public void register(final ExtensionRegistrar r) {
        r.kind(ObjectKind.of(TasksWire.TASK, SemVer.parse("1.0.0")));
        // Sent by the mod only; registered so connections negotiate it.
        r.message(TasksWire.TASK_CONTROL, (ctx, message) -> { });
    }

    @Override
    public void registerClient(final ClientRegistrar r) {
        this.bridge = r.bridge();
        r.homeTab(Component.translatable("mineflayer_bridge_tasks.tab"), new HomeTab() {
            @Override
            public List<ScreenRow> rows() {
                return tabRows();
            }

            @Override
            public void use(final String itemId, final @Nullable Object value) {
                final String[] parts = itemId.split("\\|", 3);
                if (parts.length == 3) {
                    send(parts[1], parts[2], TaskAction.valueOf(parts[0].toUpperCase(Locale.ROOT)));
                }
            }
        });
        r.keybind("pause_all_tasks", InputConstants.UNKNOWN.getValue(), this::togglePauseAll);
    }

    /** Every task in the snapshot, by owner, in declaration order. */
    private List<Row> tasks() {
        final ClientBridge b = bridge;
        if (b == null) {
            return List.of();
        }
        final List<Row> out = new ArrayList<>();
        for (final ObjectView<TaskData> o : b.objects(TaskData.class)) {
            out.add(new Row(o.owner(), taskId(o.localId()), o.data()));
        }
        return out;
    }

    private static String taskId(final String localId) {
        final int slash = localId.lastIndexOf('/');
        return slash < 0 ? localId : localId.substring(slash + 1);
    }

    private List<ScreenRow> tabRows() {
        final ClientBridge b = bridge;
        final List<Row> all = tasks();
        final List<ScreenRow> rows = new ArrayList<>();
        if (all.isEmpty() || b == null) {
            rows.add(row(new ScreenItem("text").withText(I18n.get("mineflayer_bridge_tasks.empty")).withColor(UiColors.LABEL)));
            return rows;
        }
        final Map<String, List<Row>> byOwner = new LinkedHashMap<>();
        for (final Row t : all) {
            byOwner.computeIfAbsent(t.owner(), k -> new ArrayList<>()).add(t);
        }
        for (final Map.Entry<String, List<Row>> e : byOwner.entrySet()) {
            rows.add(row(new ScreenItem("heading").withText(b.botName(e.getKey()))));
            for (final Row t : e.getValue()) {
                if (t.data().parent() == null) {
                    addTask(rows, t, e.getValue(), 0);
                }
            }
        }
        return rows;
    }

    private static void addTask(final List<ScreenRow> rows, final Row t, final List<Row> siblings, final int depth) {
        final TaskData d = t.data();
        final StringBuilder text = new StringBuilder("  ".repeat(depth)).append(d.name());
        if (d.total() != null && d.current() != null) {
            final double f = d.total() == 0 ? 1 : Math.min(1, d.current() / d.total());
            text.append("  ").append(Math.round(f * 100)).append('%');
        }
        if (d.status() != null && !d.status().isEmpty()) {
            text.append("  ").append(d.status());
        }
        final List<ScreenItem> items = new ArrayList<>();
        items.add(new ScreenItem("text").withText(text.toString()).withColor(color(d.state())));
        final String key = t.owner() + SEP + t.id();
        if (d.state() == TaskState.RUNNING && d.pausable()) {
            items.add(new ScreenItem("button").withId("pause" + SEP + key).withText(I18n.get("mineflayer_bridge_tasks.pause")).withWidth(60));
        } else if (d.state() == TaskState.PAUSED) {
            items.add(new ScreenItem("button").withId("resume" + SEP + key).withText(I18n.get("mineflayer_bridge_tasks.resume")).withWidth(60));
        }
        if ((d.state() == TaskState.RUNNING || d.state() == TaskState.PAUSED) && d.cancellable()) {
            items.add(new ScreenItem("button").withId("cancel" + SEP + key).withText(I18n.get("mineflayer_bridge_tasks.cancel")).withWidth(60));
        }
        rows.add(new ScreenRow(items));
        for (final Row child : siblings) {
            if (t.id().equals(child.data().parent())) {
                addTask(rows, child, siblings, depth + 1);
            }
        }
    }

    private static int color(final TaskState state) {
        return switch (state) {
            case RUNNING -> UiColors.TEXT;
            case PAUSED -> UiColors.WARNING;
            case DONE -> UiColors.GOOD;
            case FAILED, CANCELLED -> UiColors.ERROR;
        };
    }

    /** Pauses every running pausable task, or resumes them all when none is running. */
    private void togglePauseAll() {
        final List<Row> all = tasks();
        final boolean anyRunning = all.stream().anyMatch(t -> t.data().state() == TaskState.RUNNING && t.data().pausable());
        int n = 0;
        for (final Row t : all) {
            if (anyRunning && t.data().state() == TaskState.RUNNING && t.data().pausable()) {
                n += send(t.owner(), t.id(), TaskAction.PAUSE) ? 1 : 0;
            } else if (!anyRunning && t.data().state() == TaskState.PAUSED) {
                n += send(t.owner(), t.id(), TaskAction.RESUME) ? 1 : 0;
            }
        }
        final ClientBridge b = bridge;
        if (b != null) {
            final String count = I18n.get(n == 1 ? "mineflayer_bridge_tasks.count.one" : "mineflayer_bridge_tasks.count.other", n);
            b.toast(I18n.get("mineflayer_bridge_tasks.toast"), n == 0 ? I18n.get("mineflayer_bridge_tasks.none")
                    : I18n.get(anyRunning ? "mineflayer_bridge_tasks.paused" : "mineflayer_bridge_tasks.resumed", count));
        }
    }

    private boolean send(final String owner, final String task, final TaskAction action) {
        final ClientBridge b = bridge;
        final MessageContext session = b == null ? null : b.session(owner);
        if (b == null || session == null) {
            return false;
        }
        try {
            session.send(TasksWire.TASK_CONTROL, new BridgeTaskControl(task, action));
            b.playerAsked(owner);
            return true;
        } catch (final RuntimeException e) {
            // A bot without the Node half of bridge:tasks did not negotiate the message.
            {
                b.toastError(I18n.get("mineflayer_bridge_tasks.toast"), I18n.get("mineflayer_bridge_tasks.refused", b.botName(owner), e.getMessage()));
            }
            return false;
        }
    }

    private static ScreenRow row(final ScreenItem... items) {
        return new ScreenRow(List.of(items));
    }
}
