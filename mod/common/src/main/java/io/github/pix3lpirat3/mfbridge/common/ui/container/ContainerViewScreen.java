package io.github.pix3lpirat3.mfbridge.common.ui.container;

import io.github.pix3lpirat3.mfbridge.common.ui.BridgeUi;
import io.github.pix3lpirat3.mfbridge.common.ui.UiContext;
import io.github.pix3lpirat3.mfbridge.common.ui.logic.ContainerLayout;
import io.github.pix3lpirat3.mfbridge.common.ui.logic.SlotGestures;
import io.github.pix3lpirat3.mfbridge.common.ui.screen.ChildScreen;
import io.github.pix3lpirat3.mfbridge.common.ui.screen.UiColors;
import io.github.pix3lpirat3.mfbridge.core.BridgeCore;
import io.github.pix3lpirat3.mfbridge.core.container.SlotStorage;
import io.github.pix3lpirat3.mfbridge.core.decl.ContainerViewDecl;
import io.github.pix3lpirat3.mfbridge.protocol.core.ContainerViewData;
import io.github.pix3lpirat3.mfbridge.protocol.core.SlotClick;
import io.github.pix3lpirat3.mfbridge.protocol.core.SlotEntry;
import io.github.pix3lpirat3.mfbridge.protocol.core.SlotItem;
import it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import java.util.List;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Options;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.PageButton;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * The container view and menu screen:
 * vanilla container textures, slot rendering, tooltips and gestures, filled from a
 * bot's {@code core:container-view}.
 *
 * <p>It is a plain {@link Screen}, never an {@code AbstractContainerScreen} or a vanilla menu, so no
 * container click or close packet can be built. Each slot has a policy:
 *
 * <ul>
 *   <li>{@code button} slots send gestures to the bot as {@code container.input} and show a pending
 *       state until the bot acknowledges;
 *   <li>{@code locked} slots do nothing;
 *   <li>{@code take}, {@code place}, {@code free} and {@code ask} slots are the view's own virtual
 *       storage ({@link SlotStorage}) with a cursor stack local to the view and vanilla gestures:
 *       pick up, place one or all, swap, shift-click, drag-split, double-click collect and number
 *       keys, all within storage. Every change is reported with {@code container.storage}; a move
 *       through an {@code ask} slot also goes out as {@code container.input} and rolls back with a red
 *       flash and the bot's reason as tooltip when rejected. Items left on the cursor return to their
 *       origin on close;
 *   <li>{@code source} slots are infinite templates: a click puts a virtual copy of the bot's item on
 *       the cursor (left a full stack, right one) and shift-click copies a stack into storage; the
 *       template never runs out.
 * </ul>
 *
 * <p>Storage has no other way to receive items, since moving real ones would need packets. So while
 * the view has storage slots the player's own inventory below it is a copy source as well: a click
 * copies the real stack onto the cursor (left all, right half), shift-click copies it into storage,
 * and clicking an empty inventory slot drops a copy from the cursor. The real inventory is never
 * changed. Copies left on the cursor vanish on close.
 *
 * <p>Layouts {@code furnace} and {@code brewing} show read-only progress, {@code lectern} shows pages.
 * {@code reopenParent} opens the parent view when the player closes this one, and {@code
 * requireAnswer} makes Esc ask once before closing. Without storage slots the player's own inventory
 * below the view is only drawn. Client thread only.
 */
public final class ContainerViewScreen extends ChildScreen {

    private static final Identifier GENERIC_54 = Identifier.withDefaultNamespace("textures/gui/container/generic_54.png");
    private static final Identifier HOPPER = Identifier.withDefaultNamespace("textures/gui/container/hopper.png");
    private static final Identifier DISPENSER = Identifier.withDefaultNamespace("textures/gui/container/dispenser.png");
    private static final Identifier INVENTORY = Identifier.withDefaultNamespace("textures/gui/container/inventory.png");
    private static final Identifier FURNACE = Identifier.withDefaultNamespace("textures/gui/container/furnace.png");
    private static final Identifier BREWING = Identifier.withDefaultNamespace("textures/gui/container/brewing_stand.png");
    private static final Identifier BOOK = Identifier.withDefaultNamespace("textures/gui/book.png");
    private static final Identifier LIT_PROGRESS = Identifier.withDefaultNamespace("container/furnace/lit_progress");
    private static final Identifier BURN_PROGRESS = Identifier.withDefaultNamespace("container/furnace/burn_progress");
    private static final Identifier FUEL_LENGTH = Identifier.withDefaultNamespace("container/brewing_stand/fuel_length");
    private static final Identifier BREW_PROGRESS = Identifier.withDefaultNamespace("container/brewing_stand/brew_progress");
    private static final Identifier BUBBLES = Identifier.withDefaultNamespace("container/brewing_stand/bubbles");
    private static final Identifier SLOT_SPRITE = Identifier.withDefaultNamespace("container/slot");
    private static final Identifier HIGHLIGHT_BACK = Identifier.withDefaultNamespace("container/slot_highlight_back");
    private static final Identifier HIGHLIGHT_FRONT = Identifier.withDefaultNamespace("container/slot_highlight_front");
    /** 26.3 BrewingStandScreen.BUBBLELENGTHS. */
    private static final int[] BUBBLE_LENGTHS = {29, 24, 20, 16, 11, 6, 0};
    private static final int PENDING_ARGB = 0x80000000;
    private static final int PANEL_ARGB = 0xFFC6C6C6;
    private static final int FLASH_ARGB = 0x90FF2020;
    private static final int DRAG_ARGB = 0x80FFFFFF;
    private static final int WARNING_ARGB = 0xFFFF5555;
    private static final int OUTSIDE = -999;
    private static final long FLASH_MS = 600L;
    private static final long REJECT_TOOLTIP_MS = 4_000L;
    private static final long CONFIRM_CLOSE_MS = 3_000L;
    private static final int BOOK_LINE_WIDTH = 114;
    private static final int BOOK_LINES = 14;

    private final UiContext ui;
    private final String viewKey;
    private final Component inventoryTitle;
    private final SlotStorage storage = new SlotStorage(StorageRules.INSTANCE);
    private final IntOpenHashSet pending = new IntOpenHashSet();
    private final Int2ObjectOpenHashMap<SlotVisual> visuals = new Int2ObjectOpenHashMap<>();
    private final Int2ObjectOpenHashMap<SlotVisual> storageVisuals = new Int2ObjectOpenHashMap<>();
    private final Int2LongOpenHashMap flashUntil = new Int2LongOpenHashMap();
    private final Int2ObjectOpenHashMap<String> rejections = new Int2ObjectOpenHashMap<>();
    private final Int2LongOpenHashMap rejectionUntil = new Int2LongOpenHashMap();
    private final IntArrayList dragSlots = new IntArrayList();
    private @Nullable ContainerViewData shown;
    private ContainerLayout layout = ContainerLayout.parse(ContainerLayout.DEFAULT);
    private @Nullable SlotVisual filler;
    private @Nullable SlotVisual cursorVisual;
    private int left;
    private int top;
    private int lastClickedSlot = Integer.MIN_VALUE;
    private boolean closedByCore;
    private boolean removedOnce;
    private int lastMouseX;
    private int lastMouseY;
    private boolean dragging;
    private boolean dragRight;
    private long closeArmedAtMs = Long.MIN_VALUE;
    private int page;
    private int linesPage = -1;
    private List<FormattedCharSequence> pageLines = List.of();

    /**
     * Creates the screen. The caller reports the view open with {@code ContainerControls.opened}.
     *
     * @param ui the UI services
     * @param view the declared view
     */
    public ContainerViewScreen(final UiContext ui, final ContainerViewDecl view) {
        super(Component.literal(view.data().title()), null);
        this.ui = ui;
        this.viewKey = view.key();
        this.inventoryTitle = Component.translatable("container.inventory");
        refresh(view.data());
    }

    /**
     * Returns the key of the shown view.
     *
     * @return the {@code core:container-view} object key
     */
    public String viewKey() {
        return viewKey;
    }

    private void refresh(final ContainerViewData data) {
        if (data == shown) {
            return;
        }
        final boolean layoutChanged = shown == null || !shown.layout().equals(data.layout());
        shown = data;
        layout = ContainerLayout.parse(data.layout());
        // Storage slots the player changed keep their contents; the bot wins elsewhere.
        storage.update(data.slots());
        visuals.clear();
        for (final SlotEntry e : data.slots()) {
            if (!storage.isStorage(e.slot())) {
                visuals.put(e.slot(), SlotVisual.of(e.item()));
            }
        }
        final SlotItem fill = data.filler();
        filler = fill == null ? null : SlotVisual.of(fill);
        linesPage = -1;
        if (layoutChanged) {
            place();
            if (minecraft != null) {
                rebuildWidgets();
            }
        }
    }

    private void place() {
        left = (width - layout.width()) / 2;
        top = (height - layout.height()) / 2;
    }

    @Override
    protected void init() {
        place();
        if (layout.background() == ContainerLayout.Background.LECTERN) {
            addRenderableWidget(new PageButton(left + 116, top + 157, true, b -> turnPage(1), true));
            addRenderableWidget(new PageButton(left + 43, top + 157, false, b -> turnPage(-1), true));
        }
    }

    private int pageCount() {
        final ContainerViewData data = shown;
        final List<String> pages = data == null ? null : data.pages();
        return pages == null ? 0 : pages.size();
    }

    private void turnPage(final int delta) {
        page = Mth.clamp(page + delta, 0, Math.max(0, pageCount() - 1));
    }

    @Override
    public void tick() {
        final BridgeCore core = ui.core();
        final ContainerViewDecl view = core == null ? null : core.declarations().containerView(viewKey);
        if (core == null || view == null || !viewKey.equals(core.containers().open())) {
            // The bot closed it, removed it, or was detached; the core already knows.
            closedByCore = true;
            minecraft.gui.setScreen(null);
            return;
        }
        refresh(view.data());
    }

    @Override
    public void removed() {
        if (removedOnce) {
            return;
        }
        removedOnce = true;
        final BridgeCore core = ui.core();
        if (storage.returnCursor() != null && !closedByCore) {
            reportStorage();
        }
        if (!closedByCore && core != null) {
            core.containers().closed(viewKey);
        }
    }

    @Override
    public void onClose() {
        close(false);
    }

    /**
     * Closes the view by the player's choice. With {@code requireAnswer}, closing
     * without an answer needs a second Esc within a few seconds; {@code reopenParent} opens the parent.
     */
    private void close(final boolean answered) {
        final ContainerViewData data = shown;
        if (!answered && data != null && data.requireAnswer()) {
            final long now = ui.nowMs();
            if (closeArmedAtMs == Long.MIN_VALUE || now - closeArmedAtMs > CONFIRM_CLOSE_MS) {
                closeArmedAtMs = now;
                return;
            }
        }
        final String parent = data == null ? null : data.reopenParent();
        minecraft.gui.setScreen(null);
        if (parent != null && !parent.equals(viewKey)) {
            ui.openContainerView(parent);
        }
    }

    @Override
    public boolean isInGameUi() {
        return true;
    }

    @Override
    public void extractBackground(final GuiGraphicsExtractor graphics, final int mouseX, final int mouseY, final float a) {
        super.extractBackground(graphics, mouseX, mouseY, a);
        final int w = layout.width();
        final int h = layout.height();
        switch (layout.background()) {
            case GENERIC_54 -> {
                final int rows = layout.chestRows();
                graphics.blit(RenderPipelines.GUI_TEXTURED, GENERIC_54, left, top, 0f, 0f, w, rows * 18 + 17, 256, 256);
                graphics.blit(RenderPipelines.GUI_TEXTURED, GENERIC_54, left, top + rows * 18 + 17, 0f, 126f, w, 96, 256, 256);
            }
            case HOPPER -> graphics.blit(RenderPipelines.GUI_TEXTURED, HOPPER, left, top, 0f, 0f, w, h, 256, 256);
            case DISPENSER -> graphics.blit(RenderPipelines.GUI_TEXTURED, DISPENSER, left, top, 0f, 0f, w, h, 256, 256);
            case INVENTORY -> graphics.blit(RenderPipelines.GUI_TEXTURED, INVENTORY, left, top, 0f, 0f, w, h, 256, 256);
            case FURNACE -> {
                graphics.blit(RenderPipelines.GUI_TEXTURED, FURNACE, left, top, 0f, 0f, w, h, 256, 256);
                furnaceProgress(graphics);
            }
            case BREWING -> {
                graphics.blit(RenderPipelines.GUI_TEXTURED, BREWING, left, top, 0f, 0f, w, h, 256, 256);
                brewingProgress(graphics);
            }
            case LECTERN -> graphics.blit(RenderPipelines.GUI_TEXTURED, BOOK, left, top, 0f, 0f, w, h, 256, 256);
            case PANEL -> {
                final int r = left + w;
                final int b = top + h;
                graphics.fill(left, top, r, b, 0xFF000000);
                graphics.fill(left + 1, top + 1, r - 1, b - 1, 0xFFFFFFFF);
                graphics.fill(left + 3, top + 3, r - 1, b - 1, 0xFF555555);
                graphics.fill(left + 3, top + 3, r - 3, b - 3, PANEL_ARGB);
                for (final ContainerLayout.Slot s : layout.slots()) {
                    graphics.blitSprite(RenderPipelines.GUI_TEXTURED, SLOT_SPRITE, left + s.x() - 1, top + s.y() - 1, 18, 18);
                }
            }
        }
    }

    /** Same geometry as 26.3 {@code AbstractFurnaceScreen}; values come from the view, 0 to 1. */
    private void furnaceProgress(final GuiGraphicsExtractor graphics) {
        final ContainerViewData data = shown;
        if (data == null) {
            return;
        }
        final Float fuel = data.fuel();
        if (fuel != null && fuel > 0f) {
            final int lit = Mth.ceil(Mth.clamp(fuel, 0f, 1f) * 13f) + 1;
            graphics.blitSprite(RenderPipelines.GUI_TEXTURED, LIT_PROGRESS, 14, 14, 0, 14 - lit, left + 56, top + 36 + 14 - lit, 14, lit);
        }
        final Float progress = data.progress();
        if (progress != null && progress > 0f) {
            final int burn = Mth.ceil(Mth.clamp(progress, 0f, 1f) * 24f);
            graphics.blitSprite(RenderPipelines.GUI_TEXTURED, BURN_PROGRESS, 24, 16, 0, 0, left + 79, top + 34, burn, 16);
        }
    }

    /** Same geometry as 26.3 {@code BrewingStandScreen}; bubbles animate while brewing. */
    private void brewingProgress(final GuiGraphicsExtractor graphics) {
        final ContainerViewData data = shown;
        if (data == null) {
            return;
        }
        final Float fuel = data.fuel();
        if (fuel != null && fuel > 0f) {
            final int length = Mth.clamp(Mth.ceil(18f * fuel), 0, 18);
            graphics.blitSprite(RenderPipelines.GUI_TEXTURED, FUEL_LENGTH, 18, 4, 0, 0, left + 60, top + 44, length, 4);
        }
        final Float progress = data.progress();
        if (progress != null && progress > 0f && progress < 1f) {
            final int length = (int) (28f * Mth.clamp(progress, 0f, 1f));
            if (length > 0) {
                graphics.blitSprite(RenderPipelines.GUI_TEXTURED, BREW_PROGRESS, 9, 28, 0, 0, left + 97, top + 16, 9, length);
            }
            final int bubbles = BUBBLE_LENGTHS[(int) (ui.nowMs() / 100L % BUBBLE_LENGTHS.length)];
            if (bubbles > 0) {
                graphics.blitSprite(RenderPipelines.GUI_TEXTURED, BUBBLES, 12, 29, 0, 29 - bubbles, left + 63, top + 14 + 29 - bubbles, 12,
                        bubbles);
            }
        }
    }

    @Override
    public void extractRenderState(final GuiGraphicsExtractor graphics, final int mouseX, final int mouseY, final float a) {
        super.extractRenderState(graphics, mouseX, mouseY, a);
        lastMouseX = mouseX;
        lastMouseY = mouseY;
        final long now = ui.nowMs();
        if (layout.background() == ContainerLayout.Background.LECTERN) {
            renderPages(graphics);
        } else if (layout.background() == ContainerLayout.Background.INVENTORY) {
            // The inventory texture has the armour and the model where a title would go: draw it above.
            graphics.centeredText(font, title, width / 2, top - 12, 0xFFFFFFFF);
        } else {
            graphics.text(font, title, left + 8, top + 6, UiColors.CONTAINER_LABEL, false);
        }
        if (layout.inventoryLabelY() >= 0) {
            graphics.text(font, inventoryTitle, left + 8, top + layout.inventoryLabelY(), UiColors.CONTAINER_LABEL, false);
        }
        final double rx = mouseX - left;
        final double ry = mouseY - top;
        final ContainerLayout.Slot hovered = layout.slotAt(rx, ry);
        final ContainerLayout.Slot hoveredPlayer = hovered == null ? layout.playerSlotAt(rx, ry) : null;
        final ContainerLayout.Slot highlight = hovered != null ? hovered : hoveredPlayer;
        if (highlight != null) {
            graphics.blitSprite(RenderPipelines.GUI_TEXTURED, HIGHLIGHT_BACK, left + highlight.x() - 4, top + highlight.y() - 4, 24, 24);
        }
        int seed = 0;
        for (final ContainerLayout.Slot s : layout.slots()) {
            final int x = left + s.x();
            final int y = top + s.y();
            final SlotVisual visual = visualAt(s.index());
            if (visual != null) {
                visual.draw(graphics, font, x, y, seed++, now);
            }
            if (dragging && dragSlots.contains(s.index())) {
                graphics.fill(x, y, x + 16, y + 16, DRAG_ARGB);
            }
            if (pending.contains(s.index())) {
                graphics.fill(x, y, x + 16, y + 16, PENDING_ARGB);
                graphics.centeredText(font, "...", x + 8, y + 4, 0xFFFFFFFF);
            }
            if (flashUntil.containsKey(s.index()) && now < flashUntil.get(s.index())) {
                graphics.fill(x, y, x + 16, y + 16, FLASH_ARGB);
            }
        }
        final Inventory inventory = minecraft.player == null ? null : minecraft.player.getInventory();
        if (inventory != null) {
            for (final ContainerLayout.Slot s : layout.playerSlots()) {
                final ItemStack stack = inventory.getItem(s.index());
                if (!stack.isEmpty()) {
                    graphics.item(stack, left + s.x(), top + s.y(), seed++);
                    graphics.itemDecorations(font, stack, left + s.x(), top + s.y());
                }
            }
        }
        if (highlight != null) {
            graphics.blitSprite(RenderPipelines.GUI_TEXTURED, HIGHLIGHT_FRONT, left + highlight.x() - 4, top + highlight.y() - 4, 24, 24);
        }
        final SlotItem carried = storage.cursor();
        if (carried != null) {
            SlotVisual cv = cursorVisual;
            if (cv == null || cv.item() != carried) {
                cv = SlotVisual.of(carried);
                cursorVisual = cv;
            }
            cv.draw(graphics, font, mouseX - 8, mouseY - 8, seed, now);
        } else if (highlight != null) {
            tooltip(graphics, hovered, hoveredPlayer, inventory, mouseX, mouseY, now);
        }
        if (closeArmedAtMs != Long.MIN_VALUE && now - closeArmedAtMs <= CONFIRM_CLOSE_MS) {
            graphics.centeredText(font, Component.translatable("mineflayer_bridge.container.confirm_close"), width / 2, top - 12,
                    WARNING_ARGB);
        }
    }

    private void tooltip(final GuiGraphicsExtractor graphics, final ContainerLayout.@Nullable Slot hovered,
            final ContainerLayout.@Nullable Slot hoveredPlayer, final @Nullable Inventory inventory, final int mouseX, final int mouseY,
            final long now) {
        if (hovered != null) {
            final int index = hovered.index();
            if (rejections.containsKey(index) && now < rejectionUntil.get(index)) {
                graphics.setTooltipForNextFrame(font, Component.literal(rejections.get(index)).withColor(WARNING_ARGB), mouseX, mouseY);
                return;
            }
            final SlotVisual visual = visualAt(index);
            if (visual != null && visual != filler) {
                visual.tooltip(graphics, font, mouseX, mouseY, now);
            }
        } else if (hoveredPlayer != null && inventory != null) {
            final ItemStack under = inventory.getItem(hoveredPlayer.index());
            if (!under.isEmpty()) {
                graphics.setTooltipForNextFrame(font, under, mouseX, mouseY);
            }
        }
    }

    /** Lectern pages with the 26.3 {@code BookViewScreen} geometry: page indicator and 14 lines. */
    private void renderPages(final GuiGraphicsExtractor graphics) {
        final ContainerViewData data = shown;
        final List<String> pages = data == null ? null : data.pages();
        final int count = pages == null ? 0 : pages.size();
        graphics.centeredText(font, title, width / 2, top - 12, 0xFFFFFFFF);
        if (pages == null || count == 0) {
            return;
        }
        page = Mth.clamp(page, 0, count - 1);
        if (linesPage != page) {
            linesPage = page;
            pageLines = List.copyOf(font.split(Component.literal(pages.get(page)), BOOK_LINE_WIDTH));
        }
        final Component indicator = Component.translatable("book.pageIndicator", page + 1, count);
        graphics.text(font, indicator, left + 148 - font.width(indicator), top + 16, 0xFF000000, false);
        final int lines = Math.min(BOOK_LINES, pageLines.size());
        for (int i = 0; i < lines; i++) {
            graphics.text(font, pageLines.get(i), left + 36, top + 30 + i * 9, 0xFF000000, false);
        }
    }

    /** The visual of a slot: storage contents, the bot's item, or the filler for an empty slot. */
    private @Nullable SlotVisual visualAt(final int index) {
        if (storage.isStorage(index)) {
            final SlotItem item = storage.item(index);
            if (item == null) {
                return null;
            }
            SlotVisual v = storageVisuals.get(index);
            if (v == null || v.item() != item) {
                v = SlotVisual.of(item);
                storageVisuals.put(index, v);
            }
            return v;
        }
        final SlotVisual v = visuals.get(index);
        return v != null ? v : filler;
    }

    @Override
    public boolean mouseClicked(final MouseButtonEvent event, final boolean doubleClick) {
        if (super.mouseClicked(event, doubleClick)) {
            return true;
        }
        final double rx = event.x() - left;
        final double ry = event.y() - top;
        final ContainerLayout.Slot slot = layout.slotAt(rx, ry);
        final boolean outside = !layout.inside(rx, ry);
        if (slot == null) {
            lastClickedSlot = Integer.MIN_VALUE;
            final ContainerLayout.Slot own = layout.playerSlotAt(rx, ry);
            if (own != null) {
                inventoryClick(own, event);
            } else if (outside && storage.cursor() != null) {
                // Virtual items cannot be thrown into the world; clicking outside puts them back instead.
                apply(storage.returnCursor(), OUTSIDE, SlotClick.LEFT, null);
            } else if (outside) {
                final SlotGestures.Gesture g = SlotGestures.mouse(event.button(), event.hasShiftDown(), false,
                        minecraft.options.keyPickItem.matchesMouse(event));
                if (g != null && (g.click() == SlotClick.LEFT || g.click() == SlotClick.RIGHT)) {
                    send(OUTSIDE, g);
                }
            }
            return true;
        }
        final int index = slot.index();
        final boolean repeat = doubleClick && index == lastClickedSlot;
        lastClickedSlot = index;
        final SlotVisual visual = visualAt(index);
        if (visual != null && visual != filler) {
            visual.playClickSound();
        }
        final SlotStorage.Policy policy = storage.policy(index);
        if (policy == SlotStorage.Policy.LOCKED) {
            return true;
        }
        if (policy.storage()) {
            storageClick(index, event, repeat);
            return true;
        }
        if (policy == SlotStorage.Policy.SOURCE) {
            sourceClick(index, event);
            return true;
        }
        final SlotGestures.Gesture g = SlotGestures.mouse(event.button(), event.hasShiftDown(), repeat,
                minecraft.options.keyPickItem.matchesMouse(event));
        if (g != null) {
            send(index, g);
        }
        return true;
    }

    /** A source slot copies its template, never running out. */
    private void sourceClick(final int index, final MouseButtonEvent event) {
        final int button = event.button();
        if (button != SlotGestures.BUTTON_LEFT && button != SlotGestures.BUTTON_RIGHT) {
            return;
        }
        final SlotItem template = storage.template(index);
        if (event.hasShiftDown() && template != null) {
            apply(storage.quickCopy(template, StorageRules.INSTANCE.maxStackSize(template), index), index,
                    button == SlotGestures.BUTTON_RIGHT ? SlotClick.SHIFT_RIGHT : SlotClick.SHIFT_LEFT, null);
            return;
        }
        apply(storage.clickSource(index, button == SlotGestures.BUTTON_RIGHT), index,
                button == SlotGestures.BUTTON_RIGHT ? SlotClick.RIGHT : SlotClick.LEFT, null);
    }

    /**
     * With storage slots in the view, the player's inventory is a copy
     * source. Only a virtual copy moves; the real stack and the server never hear of it.
     */
    private void inventoryClick(final ContainerLayout.Slot own, final MouseButtonEvent event) {
        final int button = event.button();
        final Inventory inventory = minecraft.player == null ? null : minecraft.player.getInventory();
        if (inventory == null || !storage.hasStorage() || (button != SlotGestures.BUTTON_LEFT && button != SlotGestures.BUTTON_RIGHT)) {
            return;
        }
        final ItemStack stack = inventory.getItem(own.index());
        final SlotItem copy = stack.isEmpty() ? null : SlotItems.of(stack);
        final boolean right = button == SlotGestures.BUTTON_RIGHT;
        if (event.hasShiftDown() && copy != null) {
            apply(storage.quickCopy(copy, copy.count(), SlotStorage.CURSOR), OUTSIDE,
                    right ? SlotClick.SHIFT_RIGHT : SlotClick.SHIFT_LEFT, null);
            return;
        }
        apply(storage.clickInventory(copy, right), OUTSIDE, right ? SlotClick.RIGHT : SlotClick.LEFT, null);
    }

    private void storageClick(final int index, final MouseButtonEvent event, final boolean repeat) {
        final int button = event.button();
        if (minecraft.options.keyPickItem.matchesMouse(event) || (button != SlotGestures.BUTTON_LEFT && button != SlotGestures.BUTTON_RIGHT)) {
            return;
        }
        final boolean right = button == SlotGestures.BUTTON_RIGHT;
        if (event.hasShiftDown()) {
            apply(storage.quickMove(index), index, right ? SlotClick.SHIFT_RIGHT : SlotClick.SHIFT_LEFT, null);
            return;
        }
        if (repeat && !right && storage.cursor() != null) {
            apply(storage.collect(), index, SlotClick.DOUBLE, null);
            return;
        }
        if (storage.cursor() != null) {
            // Vanilla quick craft: with a stack on the cursor the press starts a drag; the release decides.
            dragging = true;
            dragRight = right;
            dragSlots.clear();
            dragSlots.add(index);
            return;
        }
        apply(storage.click(index, right), index, right ? SlotClick.RIGHT : SlotClick.LEFT, null);
    }

    @Override
    public boolean mouseDragged(final MouseButtonEvent event, final double dx, final double dy) {
        if (dragging) {
            final ContainerLayout.Slot slot = layout.slotAt(event.x() - left, event.y() - top);
            if (slot != null && storage.isStorage(slot.index()) && !dragSlots.contains(slot.index())) {
                dragSlots.add(slot.index());
            }
            return true;
        }
        return super.mouseDragged(event, dx, dy);
    }

    @Override
    public boolean mouseReleased(final MouseButtonEvent event) {
        if (dragging && event.button() == (dragRight ? SlotGestures.BUTTON_RIGHT : SlotGestures.BUTTON_LEFT)) {
            dragging = false;
            final int first = dragSlots.getInt(0);
            final SlotClick click = dragRight ? SlotClick.RIGHT : SlotClick.LEFT;
            if (dragSlots.size() == 1) {
                apply(storage.click(first, dragRight), first, click, null);
            } else {
                apply(storage.drag(List.copyOf(dragSlots), dragRight), first, click, null);
            }
            dragSlots.clear();
            return true;
        }
        return super.mouseReleased(event);
    }

    @Override
    public boolean keyPressed(final KeyEvent event) {
        final Options options = minecraft.options;
        if (event.isEscape() || options.keyInventory.matches(event)) {
            onClose();
            return true;
        }
        final ContainerLayout.Slot hovered = hoveredSlot();
        if (hovered != null) {
            final int index = hovered.index();
            final SlotStorage.Policy policy = storage.policy(index);
            if (policy == SlotStorage.Policy.LOCKED || policy == SlotStorage.Policy.SOURCE) {
                return true;
            }
            if (policy.storage()) {
                final int hotbar = hotbarIndex(options, event);
                final int target = hotbar < 0 ? -1 : hotbarTarget(hotbar);
                if (target >= 0) {
                    apply(storage.swap(index, target), index, SlotClick.NUMBER, hotbar);
                    return true;
                }
                return super.keyPressed(event);
            }
            final SlotGestures.Gesture g = options.keyPickItem.matches(event)
                    ? new SlotGestures.Gesture(SlotClick.MIDDLE, null)
                    : SlotGestures.key(options.keyDrop.matches(event), event.hasControlDownWithQuirk(), hotbarIndex(options, event));
            if (g != null) {
                send(index, g);
                return true;
            }
        }
        return super.keyPressed(event);
    }

    /**
     * Number keys work within storage. In the player inventory layout the
     * hotbar row is storage slot 36 plus the key; elsewhere the key picks the n-th storage slot.
     */
    private int hotbarTarget(final int hotbar) {
        if (layout.background() == ContainerLayout.Background.INVENTORY && storage.isStorage(36 + hotbar)) {
            return 36 + hotbar;
        }
        final List<Integer> slots = storage.storageSlots();
        return hotbar < slots.size() ? slots.get(hotbar) : -1;
    }

    private static int hotbarIndex(final Options options, final KeyEvent event) {
        final KeyMapping[] keys = options.keyHotbarSlots;
        for (int i = 0; i < keys.length && i < 9; i++) {
            if (keys[i].matches(event)) {
                return i;
            }
        }
        return -1;
    }

    private ContainerLayout.@Nullable Slot hoveredSlot() {
        return layout.slotAt(lastMouseX - left, lastMouseY - top);
    }

    /** Reports a storage change, and asks the bot to confirm it when an {@code ask} slot took part. */
    private void apply(final SlotStorage.@Nullable Move move, final int slot, final SlotClick click, final @Nullable Integer hotbar) {
        if (move == null) {
            return;
        }
        reportStorage();
        final List<Integer> ask = move.askSlots();
        final BridgeCore core = ui.core();
        if (ask.isEmpty() || core == null) {
            return;
        }
        final int at = ask.contains(slot) ? slot : ask.getFirst();
        pending.add(at);
        core.containers().input(viewKey, at, click, hotbar, move.moved(), move.moved() == null ? null : move.fromSlot())
                .whenComplete((ack, error) -> {
                    pending.remove(at);
                    final String reason = error != null ? BridgeUi.describe(error)
                            : ack.ok() ? null : ack.message() == null || ack.message().isEmpty() ? I18n.get("mineflayer_bridge.rejected") : ack.message();
                    if (reason != null) {
                        reject(move, reason);
                    }
                });
    }

    /** A rejected {@code ask} move rolls back with a red flash and the reason as tooltip. */
    private void reject(final SlotStorage.Move move, final String reason) {
        if (removedOnce) {
            return;
        }
        final List<Integer> restored = storage.rollback(move.undo());
        final long now = ui.nowMs();
        for (final int s : restored) {
            flashUntil.put(s, now + FLASH_MS);
            rejections.put(s, reason);
            rejectionUntil.put(s, now + REJECT_TOOLTIP_MS);
        }
        if (!restored.isEmpty() || move.cursorChanged()) {
            reportStorage();
        }
    }

    private void reportStorage() {
        final BridgeCore core = ui.core();
        if (core != null && !storage.storageSlots().isEmpty()) {
            core.containers().storageChanged(viewKey, storage.entries());
        }
    }

    private void send(final int slot, final SlotGestures.Gesture g) {
        final BridgeCore core = ui.core();
        final ContainerViewData data = shown;
        if (core == null || data == null) {
            return;
        }
        pending.add(slot);
        core.containers().input(viewKey, slot, g.click(), g.hotbar()).whenComplete((ack, error) -> {
            pending.remove(slot);
            if (error != null) {
                ui.toastError(title.getString(), BridgeUi.describe(error));
            } else if (!ack.ok() && ack.message() != null && !ack.message().isEmpty()) {
                ui.toastError(title.getString(), ack.message());
            }
        });
        if (data.closeOnClick() && SlotGestures.closesMenu(g.click())) {
            close(true);
        }
    }
}
