package io.github.pix3lpirat3.mfbridge.common.ui.logic;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * Geometry of a container view layout, in GUI pixels relative to the top
 * left of the background image. Slot positions are where vanilla draws the 16 by 16 item, matching
 * the vanilla menus of the same textures: {@code ChestMenu}, {@code HopperMenu}, {@code DispenserMenu}
 * and {@code InventoryMenu} (whose slot order is also mineflayer's window order). Game-free.
 *
 * @param background the texture to draw
 * @param width background width
 * @param height background height
 * @param slots the view's slots, by view slot index. Immutable.
 * @param playerSlots the player's own inventory drawn below the view (read-only), by player inventory
 *     index: 0 to 8 hotbar, 9 to 35 main; empty when the layout has none. Immutable.
 * @param inventoryLabelY where the vanilla "Inventory" label goes, or -1 without player slots
 */
public record ContainerLayout(
        Background background, int width, int height, List<Slot> slots, List<Slot> playerSlots, int inventoryLabelY) {

    /** The layout bots get when theirs does not parse: a three-row chest. */
    public static final String DEFAULT = "chest:3";

    private static final int SLOT = 18;
    private static final int MAX_GRID_COLUMNS = 18;
    private static final int MAX_GRID_ROWS = 12;

    /** Copies the slot lists. */
    public ContainerLayout {
        slots = List.copyOf(slots);
        playerSlots = List.copyOf(playerSlots);
    }

    /** The background texture of a layout. */
    public enum Background {
        /** {@code textures/gui/container/generic_54.png}, cut to the row count. */
        GENERIC_54,
        /** {@code textures/gui/container/hopper.png}. */
        HOPPER,
        /** {@code textures/gui/container/dispenser.png}. */
        DISPENSER,
        /** {@code textures/gui/container/inventory.png}. */
        INVENTORY,
        /** A plain vanilla-coloured panel with slot sprites, for grids no vanilla texture has. */
        PANEL,
        /** {@code textures/gui/container/furnace.png}, with read-only flame and arrow. */
        FURNACE,
        /** {@code textures/gui/container/brewing_stand.png}, with read-only fuel, arrow and bubbles. */
        BREWING,
        /** {@code textures/gui/book.png}: read-only pages, no slots. */
        LECTERN
    }

    /**
     * One slot.
     *
     * @param index view slot index, or player inventory index for player slots
     * @param x left of the item, relative to the background
     * @param y top of the item, relative to the background
     */
    public record Slot(int index, int x, int y) {
    }

    /**
     * Returns the number of chest rows for {@link Background#GENERIC_54}, else 0.
     *
     * @return 1 to 6, or 0
     */
    public int chestRows() {
        return background == Background.GENERIC_54 ? (height - 114) / SLOT : 0;
    }

    /**
     * Parses a layout name: {@code chest:<1-6>}, {@code hopper}, {@code dispenser}, {@code
     * player_inventory} (or {@code player-inventory}), {@code grid:<cols>x<rows>}, {@code furnace},
     * {@code brewing} or {@code lectern}.
     *
     * @param layout the declared layout
     * @return the geometry; unknown or out-of-range layouts give {@link #DEFAULT}
     */
    public static ContainerLayout parse(final String layout) {
        final ContainerLayout parsed = tryParse(layout.strip().toLowerCase(Locale.ROOT));
        return parsed != null ? parsed : chest(3);
    }

    private static @Nullable ContainerLayout tryParse(final String s) {
        switch (s) {
            case "chest" -> {
                return chest(3);
            }
            case "hopper" -> {
                return hopper();
            }
            case "dispenser", "dropper" -> {
                return dispenser();
            }
            case "player_inventory", "player-inventory", "inventory" -> {
                return inventory();
            }
            case "furnace" -> {
                return furnace();
            }
            case "brewing", "brewing_stand" -> {
                return brewing();
            }
            case "lectern", "book" -> {
                return new ContainerLayout(Background.LECTERN, 192, 192, List.of(), List.of(), -1);
            }
            default -> {
            }
        }
        if (s.startsWith("chest:")) {
            final int rows = number(s.substring("chest:".length()));
            return rows >= 1 && rows <= 6 ? chest(rows) : null;
        }
        if (s.startsWith("grid:")) {
            final String dims = s.substring("grid:".length());
            final int x = dims.indexOf('x');
            if (x < 0) {
                return null;
            }
            final int cols = number(dims.substring(0, x));
            final int rows = number(dims.substring(x + 1));
            return cols >= 1 && cols <= MAX_GRID_COLUMNS && rows >= 1 && rows <= MAX_GRID_ROWS ? grid(cols, rows) : null;
        }
        return null;
    }

    private static int number(final String s) {
        try {
            return Integer.parseInt(s);
        } catch (final NumberFormatException e) {
            return -1;
        }
    }

    /** {@code ContainerScreen}: image height 114 + rows * 18, player inventory 31 px below the rows. */
    private static ContainerLayout chest(final int rows) {
        final List<Slot> slots = new ArrayList<>(rows * 9);
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < 9; c++) {
                slots.add(new Slot(r * 9 + c, 8 + c * SLOT, 18 + r * SLOT));
            }
        }
        final int height = 114 + rows * SLOT;
        return new ContainerLayout(Background.GENERIC_54, 176, height, slots, player(rows * SLOT + 31), height - 94);
    }

    private static ContainerLayout hopper() {
        final List<Slot> slots = new ArrayList<>(5);
        for (int i = 0; i < 5; i++) {
            slots.add(new Slot(i, 44 + i * SLOT, 20));
        }
        return new ContainerLayout(Background.HOPPER, 176, 133, slots, player(51), 133 - 94);
    }

    private static ContainerLayout dispenser() {
        final List<Slot> slots = new ArrayList<>(9);
        for (int r = 0; r < 3; r++) {
            for (int c = 0; c < 3; c++) {
                slots.add(new Slot(r * 3 + c, 62 + c * SLOT, 17 + r * SLOT));
            }
        }
        return new ContainerLayout(Background.DISPENSER, 176, 166, slots, player(84), 166 - 94);
    }

    /** {@code AbstractFurnaceMenu}: input, fuel, result. */
    private static ContainerLayout furnace() {
        return new ContainerLayout(Background.FURNACE, 176, 166, List.of(new Slot(0, 56, 17), new Slot(1, 56, 53), new Slot(2, 116, 35)),
                player(84), 166 - 94);
    }

    /** {@code BrewingStandMenu}: three bottles, ingredient, blaze powder. */
    private static ContainerLayout brewing() {
        return new ContainerLayout(Background.BREWING, 176, 166, List.of(new Slot(0, 56, 51), new Slot(1, 79, 58), new Slot(2, 102, 51),
                new Slot(3, 79, 17), new Slot(4, 17, 17)), player(84), 166 - 94);
    }

    /** {@code InventoryMenu} order: result, 2x2 crafting, armor head to feet, main, hotbar, offhand. */
    private static ContainerLayout inventory() {
        final List<Slot> slots = new ArrayList<>(46);
        slots.add(new Slot(0, 154, 28));
        for (int i = 0; i < 4; i++) {
            slots.add(new Slot(1 + i, 98 + (i % 2) * SLOT, 18 + (i / 2) * SLOT));
        }
        for (int i = 0; i < 4; i++) {
            slots.add(new Slot(5 + i, 8, 8 + i * SLOT));
        }
        for (int i = 0; i < 27; i++) {
            slots.add(new Slot(9 + i, 8 + (i % 9) * SLOT, 84 + (i / 9) * SLOT));
        }
        for (int i = 0; i < 9; i++) {
            slots.add(new Slot(36 + i, 8 + i * SLOT, 142));
        }
        slots.add(new Slot(45, 77, 62));
        return new ContainerLayout(Background.INVENTORY, 176, 166, slots, List.of(), -1);
    }

    private static ContainerLayout grid(final int cols, final int rows) {
        final int width = Math.max(14 + cols * SLOT, 176);
        final int left = (width - cols * SLOT) / 2 + 1;
        final List<Slot> slots = new ArrayList<>(cols * rows);
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                slots.add(new Slot(r * cols + c, left + c * SLOT, 18 + r * SLOT));
            }
        }
        return new ContainerLayout(Background.PANEL, width, 18 + rows * SLOT + 7, slots, List.of(), -1);
    }

    private static List<Slot> player(final int top) {
        final List<Slot> slots = new ArrayList<>(36);
        for (int i = 0; i < 27; i++) {
            slots.add(new Slot(9 + i, 8 + (i % 9) * SLOT, top + (i / 9) * SLOT));
        }
        for (int i = 0; i < 9; i++) {
            slots.add(new Slot(i, 8 + i * SLOT, top + 58));
        }
        return slots;
    }

    /**
     * Finds the view slot under a point, with vanilla's one-pixel margin around the item.
     *
     * @param x horizontal position relative to the background
     * @param y vertical position relative to the background
     * @return the slot, or null
     */
    public @Nullable Slot slotAt(final double x, final double y) {
        return hit(slots, x, y);
    }

    /**
     * Finds the player slot under a point.
     *
     * @param x horizontal position relative to the background
     * @param y vertical position relative to the background
     * @return the slot, or null
     */
    public @Nullable Slot playerSlotAt(final double x, final double y) {
        return hit(playerSlots, x, y);
    }

    private static @Nullable Slot hit(final List<Slot> list, final double x, final double y) {
        for (final Slot s : list) {
            if (x >= s.x() - 1 && x < s.x() + 17 && y >= s.y() - 1 && y < s.y() + 17) {
                return s;
            }
        }
        return null;
    }

    /**
     * Returns whether a point is on the background.
     *
     * @param x horizontal position relative to the background
     * @param y vertical position relative to the background
     * @return true inside the image
     */
    public boolean inside(final double x, final double y) {
        return x >= 0 && y >= 0 && x < width && y < height;
    }
}
