package io.github.pix3lpirat3.mfbridge.common.ui.inspector;

import io.github.pix3lpirat3.mfbridge.common.ui.devices.DevicePermissionsScreen;
import io.github.pix3lpirat3.mfbridge.common.ui.devices.DevicesScreen;
import io.github.pix3lpirat3.mfbridge.common.ui.screen.ChildScreen;
import io.github.pix3lpirat3.mfbridge.common.ui.screen.UiColors;
import io.github.pix3lpirat3.mfbridge.core.BridgeCore;
import io.github.pix3lpirat3.mfbridge.core.diag.BridgeMetrics;
import io.github.pix3lpirat3.mfbridge.core.diag.ConnectionInfo;
import io.github.pix3lpirat3.mfbridge.core.diag.Gauge;
import io.github.pix3lpirat3.mfbridge.core.diag.Metric;
import io.github.pix3lpirat3.mfbridge.core.diag.ObjectBounds;
import io.github.pix3lpirat3.mfbridge.core.diag.ObjectInfo;
import io.github.pix3lpirat3.mfbridge.core.diag.SessionInfo;
import io.github.pix3lpirat3.mfbridge.core.diag.TimingRing;
import io.github.pix3lpirat3.mfbridge.core.diag.TrafficEntry;
import io.github.pix3lpirat3.mfbridge.core.record.RecorderControls;
import io.github.pix3lpirat3.mfbridge.core.record.ReplayHandle;
import io.github.pix3lpirat3.mfbridge.core.remote.DeviceInfo;
import io.github.pix3lpirat3.mfbridge.protocol.core.Interest;
import io.github.pix3lpirat3.mfbridge.protocol.core.LayerVisibility;
import io.github.pix3lpirat3.mfbridge.protocol.core.SampleRequest;
import io.github.pix3lpirat3.mfbridge.protocol.core.WorldKey;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.jspecify.annotations.Nullable;

/**
 * The inspector ({@code bridge:inspector}). Tabs: Connections, Sessions,
 * Objects (search and world highlight), Traffic (decoded live log with filter, pause, export,
 * recording and replay), Performance (against the time budgets) and Permissions (links to the
 * device screens). Data is pulled from the core twice a second; traffic is captured only while the
 * Traffic tab is open and not paused. The world stays visible behind the screen, so a highlighted
 * object can be seen.
 */
public final class InspectorScreen extends ChildScreen {
    /** The inspector tabs. */
    public enum Tab {
        /** Devices, connections, round trip, bytes, budget use, recording. */
        CONNECTIONS("screen.mineflayer_bridge.inspector.tab.connections"),
        /** Sessions: kind, identity, world, attach state, interest, extensions. */
        SESSIONS("screen.mineflayer_bridge.inspector.tab.sessions"),
        /** The object store browser. */
        OBJECTS("screen.mineflayer_bridge.inspector.tab.objects"),
        /** The decoded message log. */
        TRAFFIC("screen.mineflayer_bridge.inspector.tab.traffic"),
        /** Costs per tick and frame. */
        PERFORMANCE("screen.mineflayer_bridge.inspector.tab.performance"),
        /** Links to the device permission screens. */
        PERMISSIONS("screen.mineflayer_bridge.inspector.tab.permissions");

        private final Component label;

        Tab(final String labelKey) {
            this.label = Component.translatable(labelKey);
        }
    }

    private record Line(String text, int argb) {
    }

    private static final int ROW = 10;
    private static final int TOP = 44;
    private static final int SIDE = 8;
    private static final int REFRESH_TICKS = 10;
    private static final int SELECTED_ARGB = 0x50FFFFFF;
    private static final int PANEL_ARGB = 0xA0000000;
    private static final int BACKDROP_ARGB = 0x70000000;
    private static Tab lastTab = Tab.CONNECTIONS;

    private final Supplier<@Nullable BridgeCore> core;
    private final Highlight highlight;
    private Tab tab = lastTab;
    private final List<Line> lines = new ArrayList<>();
    private List<ObjectInfo> objects = List.of();
    private int objectTotal;
    private @Nullable String selectedObject;
    private String objectQuery = "";
    private List<TrafficEntry> traffic = List.of();
    private long selectedTraffic = -1;
    private String trafficFilter = "";
    private boolean paused;
    private int scroll;
    private int ticks;
    private Component status = Component.empty();
    private int statusArgb = UiColors.LABEL;

    /**
     * Creates the inspector.
     *
     * @param parent the screen to return to, or null to close to the game
     * @param core the running core, or null while the endpoint is down
     * @param highlight the world highlight that selecting an object drives
     */
    public InspectorScreen(final @Nullable Screen parent, final Supplier<@Nullable BridgeCore> core, final Highlight highlight) {
        super(Component.translatable("screen.mineflayer_bridge.inspector.title"), parent);
        this.core = core;
        this.highlight = highlight;
    }

    @Override
    protected void init() {
        final int count = Tab.values().length;
        final int tabWidth = Math.min(90, (width - 2 * SIDE - (count - 1) * 2) / count);
        final int left = width / 2 - (tabWidth * count + (count - 1) * 2) / 2;
        for (int i = 0; i < count; i++) {
            final Tab t = Tab.values()[i];
            final Button b = Button.builder(t.label, x -> select(t))
                    .bounds(left + i * (tabWidth + 2), 18, tabWidth, 20).build();
            b.active = t != tab;
            addRenderableWidget(b);
        }
        final BridgeCore c = core.get();
        switch (tab) {
            case OBJECTS -> searchBox(objectQuery, v -> {
                objectQuery = v;
                scroll = 0;
                refresh();
            });
            case TRAFFIC -> {
                searchBox(trafficFilter, v -> {
                    trafficFilter = v;
                    scroll = 0;
                    refresh();
                });
                trafficButtons(c);
            }
            case PERMISSIONS -> permissionButtons(c);
            case CONNECTIONS, SESSIONS, PERFORMANCE -> {
            }
        }
        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> onClose())
                .bounds(width - SIDE - 80, height - 24, 80, 20).build());
        capture();
        refresh();
    }

    private void searchBox(final String value, final Consumer<String> responder) {
        final EditBox box = new EditBox(font, SIDE, TOP, Math.min(240, width / 2), 16, Component.translatable("screen.mineflayer_bridge.inspector.search"));
        box.setMaxLength(200);
        box.setValue(value);
        box.setResponder(responder::accept);
        addRenderableWidget(box);
        setInitialFocus(box);
    }

    private void trafficButtons(final @Nullable BridgeCore c) {
        int x = SIDE;
        final int y = height - 24;
        addRenderableWidget(Button.builder(Component.translatable(paused ? "screen.mineflayer_bridge.inspector.resume" : "screen.mineflayer_bridge.inspector.pause"), b -> {
            paused = !paused;
            rebuildWidgets();
        }).bounds(x, y, 56, 20).build());
        x += 58;
        addRenderableWidget(Button.builder(Component.translatable("screen.mineflayer_bridge.inspector.clear"), b -> {
            final BridgeCore cc = core.get();
            if (cc != null) {
                cc.inspector().traffic().clear();
            }
            selectedTraffic = -1;
            refresh();
        }).bounds(x, y, 46, 20).build());
        x += 48;
        addRenderableWidget(Button.builder(Component.translatable("screen.mineflayer_bridge.inspector.export"), b -> export()).bounds(x, y, 52, 20).build());
        x += 54;
        final boolean recording = c != null && c.recorder().recording();
        addRenderableWidget(Button.builder(Component.translatable(recording ? "screen.mineflayer_bridge.inspector.record.stop" : "screen.mineflayer_bridge.inspector.record"),
                b -> toggleRecording())
                .bounds(x, y, 90, 20).build());
        x += 92;
        final ReplayHandle replay = c == null ? null : c.recorder().lastReplay();
        final boolean replaying = replay != null && !replay.stopped();
        addRenderableWidget(Button.builder(Component.translatable(replaying ? "screen.mineflayer_bridge.inspector.replay.stop" : "screen.mineflayer_bridge.inspector.replay"),
                b -> toggleReplay())
                .bounds(x, y, 90, 20).build());
    }

    private void permissionButtons(final @Nullable BridgeCore c) {
        addRenderableWidget(Button.builder(Component.translatable("screen.mineflayer_bridge.inspector.all_devices"),
                b -> minecraft.gui.setScreen(new DevicesScreen(this, core))).bounds(SIDE, TOP, 120, 20).build());
        if (c == null) {
            return;
        }
        int y = TOP + 28;
        for (final DeviceInfo d : c.devices().list()) {
            if (y + 20 > height - 30) {
                break;
            }
            addRenderableWidget(Button.builder(Tab.PERMISSIONS.label,
                    b -> minecraft.gui.setScreen(new DevicePermissionsScreen(this, core, d.id()))).bounds(SIDE, y, 90, 20).build());
            y += 24;
        }
    }

    private void select(final Tab t) {
        tab = t;
        lastTab = t;
        scroll = 0;
        rebuildWidgets();
    }

    /** Traffic is captured only while its tab is open and not paused. */
    private void capture() {
        final BridgeCore c = core.get();
        if (c != null) {
            c.inspector().traffic().capturing(tab == Tab.TRAFFIC && !paused);
        }
    }

    @Override
    public void removed() {
        final BridgeCore c = core.get();
        if (c != null) {
            c.inspector().traffic().capturing(false);
        }
        super.removed();
    }

    @Override
    public void tick() {
        super.tick();
        if (++ticks % REFRESH_TICKS == 0) {
            refresh();
        }
    }

    private void refresh() {
        lines.clear();
        final BridgeCore c = core.get();
        if (c == null) {
            lines.add(new Line(I18n.get("mineflayer_bridge.endpoint.stopped"), UiColors.ERROR));
            return;
        }
        switch (tab) {
            case CONNECTIONS -> connections(c);
            case SESSIONS -> sessions(c);
            case OBJECTS -> {
                final List<ObjectInfo> all = c.inspector().objects();
                objectTotal = all.size();
                final List<ObjectInfo> shown = new ArrayList<>();
                for (final ObjectInfo o : all) {
                    if (o.matches(objectQuery)) {
                        shown.add(o);
                    }
                }
                objects = shown;
            }
            case TRAFFIC -> {
                if (!paused) {
                    final List<TrafficEntry> shown = new ArrayList<>();
                    final String f = trafficFilter.strip().toLowerCase(Locale.ROOT);
                    for (final TrafficEntry e : c.inspector().traffic().entries()) {
                        if (f.isEmpty() || (e.typeName() + ' ' + e.device() + " c" + e.connectionId()).toLowerCase(Locale.ROOT).contains(f)) {
                            shown.add(e);
                        }
                    }
                    traffic = shown;
                }
            }
            case PERFORMANCE -> performance(c.inspector().metrics());
            case PERMISSIONS -> permissions(c);
        }
    }

    private void connections(final BridgeCore c) {
        final RecorderControls rec = c.recorder();
        lines.add(new Line(rec.recording()
                ? I18n.get("screen.mineflayer_bridge.inspector.listening.recording", c.config().bindAddress(), c.boundPort(), rec.directory())
                : I18n.get("mineflayer_bridge.endpoint.listening", c.config().bindAddress(), c.boundPort()),
                rec.recording() ? UiColors.WARNING : UiColors.LABEL));
        final List<ConnectionInfo> list = c.inspector().connections();
        if (list.isEmpty()) {
            lines.add(new Line(I18n.get("screen.mineflayer_bridge.inspector.connections.empty"), UiColors.LABEL));
        }
        for (final ConnectionInfo i : list) {
            lines.add(new Line("", UiColors.TEXT));
            lines.add(new Line("#" + i.id() + "  " + i.deviceName() + (i.deviceId() == null ? "" : "  (" + i.deviceId() + ")"),
                    UiColors.HEADER));
            lines.add(new Line("  " + i.remoteAddress() + (i.secure() ? "  TLS" : "  plain") + "  state " + i.state()
                    + "  rtt " + (i.rttMs() < 0 ? "-" : i.rttMs() + " ms") + (i.recording() ? "  recording" : ""), UiColors.TEXT));
            lines.add(new Line("  in " + bytes(i.bytesIn()) + " / " + i.framesIn() + " frames   out " + bytes(i.bytesOut()) + " / "
                    + i.framesOut() + " frames   queued " + i.queuedInbound(), UiColors.TEXT));
            final double use = i.budgetUse();
            lines.add(new Line(String.format(Locale.ROOT, "  budget %d msg/s of %d, %s/s of %s  (%.0f%%)   %d session(s)",
                    i.messagesPerSecond(), i.messageBudget(), bytes(i.bytesPerSecond()), bytes(i.byteBudget()), use * 100,
                    i.sessions()), use >= 1 ? UiColors.ERROR : use >= 0.75 ? UiColors.WARNING : UiColors.GOOD));
        }
        for (final SessionInfo s : c.inspector().sessions()) {
            if (!s.connected()) {
                lines.add(new Line("", UiColors.TEXT));
                lines.add(new Line(I18n.get("screen.mineflayer_bridge.inspector.detached", s.displayName(), s.detachedForMs() / 1000,
                        s.unackedMessages()), UiColors.WARNING));
            }
        }
    }

    private void sessions(final BridgeCore c) {
        final List<SessionInfo> list = c.inspector().sessions();
        if (list.isEmpty()) {
            lines.add(new Line(I18n.get("screen.mineflayer_bridge.inspector.sessions.empty"), UiColors.LABEL));
        }
        for (final SessionInfo s : list) {
            lines.add(new Line(s.displayName() + "  [" + s.kind() + "]  session " + s.sessionId()
                    + (s.connectionId() == 0 ? "  detached" : "  on #" + s.connectionId()) + (s.superseded() ? "  superseded" : ""),
                    UiColors.HEADER));
            lines.add(new Line("  identity " + (s.identity() == null ? "-" : s.identity()) + "   owner " + s.owner(), UiColors.TEXT));
            lines.add(new Line("  world " + world(s.world()) + "   " + (s.attached() ? "attached" : "not attached")
                    + (s.worldMatched() ? ", world matches" : ", other world") + "   " + s.objects() + " object(s)",
                    s.attached() ? UiColors.GOOD : UiColors.LABEL));
            final Interest in = s.interest();
            if (in != null) {
                final StringBuilder layers = new StringBuilder();
                for (final LayerVisibility l : in.layers()) {
                    layers.append(' ').append(l.extension()).append('/').append(l.layer()).append(l.visible() ? "+" : "-");
                }
                lines.add(new Line("  interest detail " + in.detail().name().toLowerCase(Locale.ROOT) + (layers.isEmpty() ? "" : "  layers" + layers),
                        UiColors.TEXT));
            }
            lines.add(new Line("  extensions " + (s.extensions().isEmpty() ? "-" : String.join(", ", s.extensions())), UiColors.TEXT));
            final SampleRequest r = s.samples();
            if (r != null) {
                lines.add(new Line(String.format(Locale.ROOT, "  client samples %.1f Hz:%s%s%s", r.rateHz(),
                        r.position() ? " position" : "", r.target() ? " target" : "", r.heldItem() ? " held item" : ""), UiColors.WARNING));
            }
            lines.add(new Line("", UiColors.TEXT));
        }
    }

    private void performance(final BridgeMetrics m) {
        lines.add(new Line(I18n.get("screen.mineflayer_bridge.inspector.targets", BridgeMetrics.SAMPLES), UiColors.LABEL));
        lines.add(new Line("", UiColors.TEXT));
        for (final Metric metric : Metric.values()) {
            final TimingRing r = m.ring(metric);
            final long avg = r.average();
            final int argb = r.count() == 0 ? UiColors.LABEL : avg > metric.targetNanos() ? UiColors.ERROR : avg > metric.targetNanos() / 2 ? UiColors.WARNING
                    : UiColors.GOOD;
            lines.add(new Line(String.format(Locale.ROOT, "%-18s %7s avg  %7s max   target %s %s", metric.label(), ms(avg), ms(r.max()),
                    ms(metric.targetNanos()), metric.perFrame() ? "per frame (shared)" : "per tick"), argb));
        }
        final long draw = m.averageDrawPhase();
        lines.add(new Line("", UiColors.TEXT));
        lines.add(new Line(I18n.get("screen.mineflayer_bridge.inspector.draw_total", ms(draw), ms(Metric.DRAW_IMMEDIATE.targetNanos())),
                draw > Metric.DRAW_IMMEDIATE.targetNanos() ? UiColors.ERROR : UiColors.GOOD));
        lines.add(new Line(Gauge.RETAINED_BYTES.label() + ": " + bytes(m.gauge(Gauge.RETAINED_BYTES)) + "   "
                + Gauge.UPLOAD_QUEUE.label() + ": " + m.gauge(Gauge.UPLOAD_QUEUE) + "   " + Gauge.IMMEDIATE_COMMANDS.label() + ": "
                + m.gauge(Gauge.IMMEDIATE_COMMANDS), UiColors.TEXT));
    }

    private void permissions(final BridgeCore c) {
        final List<DeviceInfo> list = c.devices().list();
        if (list.isEmpty()) {
            lines.add(new Line(I18n.get("screen.mineflayer_bridge.inspector.devices.empty"), UiColors.LABEL));
        }
        for (final DeviceInfo d : list) {
            lines.add(new Line(I18n.get(d.local() ? "screen.mineflayer_bridge.inspector.device.local" : "screen.mineflayer_bridge.inspector.device.paired",
                    d.name(), d.connections()), UiColors.TEXT));
            lines.add(new Line("", UiColors.TEXT));
        }
    }

    private void export() {
        final BridgeCore c = core.get();
        if (c == null) {
            return;
        }
        try {
            final List<Path> files = c.recorder().export(traffic);
            report(files.isEmpty() ? Component.translatable("screen.mineflayer_bridge.inspector.export.none")
                    : Component.translatable("screen.mineflayer_bridge.inspector.export.done", files.size(), c.recorder().directory().toString()),
                    UiColors.GOOD);
        } catch (final IOException e) {
            report(Component.translatable("screen.mineflayer_bridge.inspector.export.failed", String.valueOf(e.getMessage())), UiColors.ERROR);
        }
    }

    private void toggleRecording() {
        final BridgeCore c = core.get();
        if (c == null) {
            return;
        }
        final RecorderControls rec = c.recorder();
        if (rec.recording()) {
            final List<Path> files = rec.stop();
            report(Component.translatable("screen.mineflayer_bridge.inspector.record.done", files.size(), rec.directory().toString()), UiColors.GOOD);
        } else {
            try {
                rec.start();
                report(Component.translatable("screen.mineflayer_bridge.inspector.record.started", rec.directory().toString()), UiColors.WARNING);
            } catch (final IOException e) {
                report(Component.translatable("screen.mineflayer_bridge.inspector.record.failed", String.valueOf(e.getMessage())), UiColors.ERROR);
            }
        }
        rebuildWidgets();
    }

    private void toggleReplay() {
        final BridgeCore c = core.get();
        if (c == null) {
            return;
        }
        final RecorderControls rec = c.recorder();
        final ReplayHandle last = rec.lastReplay();
        if (last != null && !last.stopped()) {
            last.stop();
            report(Component.translatable("screen.mineflayer_bridge.inspector.replay.stopped"), UiColors.LABEL);
        } else {
            final List<Path> files = rec.files();
            if (files.isEmpty()) {
                report(Component.translatable("screen.mineflayer_bridge.inspector.replay.none", rec.directory().toString()), UiColors.WARNING);
            } else {
                try {
                    final ReplayHandle r = rec.replay(files.get(0), 1);
                    report(Component.translatable("screen.mineflayer_bridge.inspector.replay.started", String.valueOf(r.file().getFileName()), r.total()),
                            UiColors.WARNING);
                } catch (final IOException e) {
                    report(Component.translatable("screen.mineflayer_bridge.inspector.replay.failed", String.valueOf(e.getMessage())), UiColors.ERROR);
                }
            }
        }
        rebuildWidgets();
    }

    private void report(final Component text, final int argb) {
        status = text;
        statusArgb = argb;
    }

    private int listTop() {
        return tab == Tab.OBJECTS || tab == Tab.TRAFFIC ? TOP + 22 : tab == Tab.PERMISSIONS ? TOP + 28 : TOP;
    }

    private int listBottom() {
        return tab == Tab.OBJECTS || tab == Tab.TRAFFIC ? TOP + 22 + (height - 40 - TOP - 22) * 11 / 20 : height - 40;
    }

    private int rowsShown() {
        return Math.max(1, (listBottom() - listTop()) / ROW);
    }

    /** The first traffic row shown: the log follows the newest messages unless paused or scrolled. */
    private int trafficFirst() {
        return scroll == 0 && !paused ? Math.max(0, traffic.size() - rowsShown()) : scroll;
    }

    private int rowCount() {
        return switch (tab) {
            case OBJECTS -> objects.size();
            case TRAFFIC -> traffic.size();
            default -> lines.size();
        };
    }

    @Override
    public boolean mouseScrolled(final double x, final double y, final double scrollX, final double scrollY) {
        final int max = Math.max(0, rowCount() - rowsShown());
        scroll = Math.clamp(scroll - (int) Math.signum(scrollY) * 3, 0, max);
        return true;
    }

    @Override
    public boolean mouseClicked(final MouseButtonEvent event, final boolean doubleClick) {
        if (super.mouseClicked(event, doubleClick)) {
            return true;
        }
        if ((tab != Tab.OBJECTS && tab != Tab.TRAFFIC) || event.y() < listTop() || event.y() >= listBottom()) {
            return false;
        }
        final int index = (tab == Tab.TRAFFIC ? trafficFirst() : scroll) + (int) ((event.y() - listTop()) / ROW);
        if (tab == Tab.OBJECTS && index < objects.size()) {
            final ObjectInfo o = objects.get(index);
            selectedObject = o.key();
            final ObjectBounds.Box box = ObjectBounds.of(o.decoded());
            if (box != null) {
                highlight.show(box, System.currentTimeMillis());
                report(Component.translatable("screen.mineflayer_bridge.inspector.highlight", o.key(), Highlight.DURATION_MS / 1000), UiColors.GOOD);
            } else {
                report(Component.translatable("screen.mineflayer_bridge.inspector.highlight.none", o.kind()), UiColors.LABEL);
            }
            return true;
        }
        if (tab == Tab.TRAFFIC && index < traffic.size()) {
            selectedTraffic = traffic.get(index).index();
            return true;
        }
        return false;
    }

    @Override
    public void extractBackground(final GuiGraphicsExtractor graphics, final int mouseX, final int mouseY, final float a) {
        // A light backdrop instead of the blur, so a highlighted object stays visible behind the screen.
        graphics.fill(0, 0, width, height, BACKDROP_ARGB);
    }

    @Override
    public void extractRenderState(final GuiGraphicsExtractor graphics, final int mouseX, final int mouseY, final float a) {
        super.extractRenderState(graphics, mouseX, mouseY, a);
        graphics.centeredText(font, title, width / 2, 6, UiColors.TEXT);
        final int top = listTop();
        final int bottom = listBottom();
        final int right = width - SIDE;
        graphics.fill(SIDE - 2, top - 2, right + 2, bottom + 2, PANEL_ARGB);
        switch (tab) {
            case OBJECTS -> drawObjects(graphics, top, right);
            case TRAFFIC -> drawTraffic(graphics, top, right);
            case PERMISSIONS -> drawLines(graphics, top + 6, SIDE + 96, right);
            default -> drawLines(graphics, top, SIDE, right);
        }
        graphics.text(font, status, SIDE, height - 36, statusArgb);
    }

    private void drawLines(final GuiGraphicsExtractor graphics, final int top, final int left, final int right) {
        final int shown = Math.min(rowsShown(), lines.size() - scroll);
        final int step = tab == Tab.PERMISSIONS ? 12 : ROW;
        for (int i = 0; i < shown; i++) {
            final Line l = lines.get(scroll + i);
            graphics.text(font, font.plainSubstrByWidth(l.text(), right - left), left, top + i * step + 1, l.argb());
        }
    }

    private void drawObjects(final GuiGraphicsExtractor graphics, final int top, final int right) {
        graphics.text(font, I18n.get("screen.mineflayer_bridge.inspector.objects.count", objects.size(), objectTotal), SIDE + Math.min(240, width / 2) + 8, TOP + 4, UiColors.LABEL);
        final int shown = Math.min(rowsShown(), objects.size() - scroll);
        ObjectInfo selected = null;
        for (int i = 0; i < shown; i++) {
            final ObjectInfo o = objects.get(scroll + i);
            final int y = top + i * ROW;
            if (o.key().equals(selectedObject)) {
                graphics.fill(SIDE - 1, y, right + 1, y + ROW, SELECTED_ARGB);
            }
            final String meta = o.kind() + (o.layer() == null ? "" : "  layer " + o.layer()) + "  r" + o.rev() + (o.visible() ? "" : "  hidden");
            final int metaWidth = font.width(meta);
            graphics.text(font, font.plainSubstrByWidth(o.key(), right - SIDE - metaWidth - 8), SIDE, y + 1, o.visible() ? UiColors.TEXT : UiColors.LABEL);
            graphics.text(font, meta, right - metaWidth, y + 1, UiColors.HEADER);
        }
        for (final ObjectInfo o : objects) {
            if (o.key().equals(selectedObject)) {
                selected = o;
                break;
            }
        }
        if (selected != null) {
            detail(graphics, selected.key() + "  (" + selected.owner() + ", " + selected.extension() + ", " + selected.dataBytes() + " bytes"
                    + (selected.world() == null ? "" : ", " + world(selected.world())) + ")", selected.dataText(), right);
        } else {
            detail(graphics, I18n.get("screen.mineflayer_bridge.inspector.objects.select"), "", right);
        }
    }

    private void drawTraffic(final GuiGraphicsExtractor graphics, final int top, final int right) {
        final BridgeCore c = core.get();
        final boolean capturing = c != null && c.inspector().traffic().capturing();
        graphics.text(font, I18n.get(paused ? "screen.mineflayer_bridge.inspector.traffic.count.paused" : capturing
                ? "screen.mineflayer_bridge.inspector.traffic.count.live" : "screen.mineflayer_bridge.inspector.traffic.count", traffic.size()),
                SIDE + Math.min(240, width / 2) + 8, TOP + 4, paused ? UiColors.WARNING : UiColors.GOOD);
        final int rows = rowsShown();
        final int first = trafficFirst();
        final int shown = Math.min(rows, traffic.size() - first);
        TrafficEntry selected = null;
        for (int i = 0; i < shown; i++) {
            final TrafficEntry e = traffic.get(first + i);
            final int y = top + i * ROW;
            if (e.index() == selectedTraffic) {
                graphics.fill(SIDE - 1, y, right + 1, y + ROW, SELECTED_ARGB);
            }
            final String head = String.format(Locale.ROOT, "%s #%d %-22s %s", e.inbound() ? "<" : ">", e.connectionId(), e.typeName(),
                    e.header());
            graphics.text(font, font.plainSubstrByWidth(head, right - SIDE), SIDE, y + 1, e.inbound() ? UiColors.TEXT : UiColors.HEADER);
        }
        for (final TrafficEntry e : traffic) {
            if (e.index() == selectedTraffic) {
                selected = e;
                break;
            }
        }
        if (selected != null) {
            detail(graphics, selected.typeName() + "  " + selected.header() + "  " + selected.device() + "  " + selected.payload().length
                    + " bytes", selected.text(), right);
        } else {
            detail(graphics, I18n.get("screen.mineflayer_bridge.inspector.traffic.select"), "", right);
        }
    }

    private void detail(final GuiGraphicsExtractor graphics, final String heading, final String body, final int right) {
        final int top = listBottom() + 6;
        final int bottom = height - 40;
        graphics.fill(SIDE - 2, top - 2, right + 2, bottom + 2, PANEL_ARGB);
        graphics.text(font, font.plainSubstrByWidth(heading, right - SIDE), SIDE, top, UiColors.HEADER);
        if (body.isEmpty()) {
            return;
        }
        final List<FormattedCharSequence> wrapped = font.split(Component.literal(body), right - SIDE);
        int y = top + ROW + 2;
        for (final FormattedCharSequence line : wrapped) {
            if (y + ROW > bottom) {
                break;
            }
            graphics.text(font, line, SIDE, y, UiColors.TEXT);
            y += ROW;
        }
    }

    private static String world(final @Nullable WorldKey w) {
        return w == null ? "-" : w.server() + " " + w.dimension();
    }

    private static String ms(final long nanos) {
        return String.format(Locale.ROOT, "%.3f ms", nanos / 1_000_000.0);
    }

    private static String bytes(final long b) {
        if (b < 10_000) {
            return b + " B";
        }
        if (b < 10_000_000) {
            return (b >> 10) + " KiB";
        }
        return (b >> 20) + " MiB";
    }
}
