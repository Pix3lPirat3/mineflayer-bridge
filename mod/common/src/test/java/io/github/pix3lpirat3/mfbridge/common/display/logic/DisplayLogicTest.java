package io.github.pix3lpirat3.mfbridge.common.display.logic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.pix3lpirat3.mfbridge.protocol.core.ElementInput;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

final class DisplayLogicTest {
    private static final double EPS = 1e-9;
    private static final double[] IDENTITY = {0, 0, 0, 1};

    @Test
    void animationsLoopOverTheirPeriod() {
        final Animation.Sample s = new Animation.Sample();
        Animation.BOB.sample(0.2, 2000, 500, s);
        assertEquals(0.2, s.dy, EPS, "a quarter period is the top of the bob");
        Animation.BOB.sample(0.2, 2000, 2500, s);
        assertEquals(0.2, s.dy, EPS, "loops repeat every period");
        Animation.SPIN.sample(5, 1000, 250, s);
        assertEquals(Math.PI / 2, s.yaw, EPS, "spin ignores the amplitude");
        assertEquals(0, s.dy, EPS);
        Animation.PULSE.sample(0.1, 1000, 750, s);
        assertEquals(0.9, s.scale, EPS);
        Animation.SWAY.sample(0.3, 400, 100, s);
        assertEquals(0.3, s.roll, EPS);
        Animation.NONE.sample(1, 1000, 123, s);
        assertEquals(1, s.scale, EPS);
        assertEquals(Animation.SPIN, Animation.of("Spin"));
        assertEquals(Animation.NONE, Animation.of("wobble"));
        Animation.BOB.sample(1, 0, 7, s);
        assertTrue(Double.isFinite(s.dy), "a zero period does not divide by zero");
    }

    @Test
    void posesInterpolateLinearlyAndRotateAlongTheShortArc() {
        final DisplayPose from = new DisplayPose();
        from.set(new double[] {0, 64, 0}, new double[3], new float[0], new double[] {1, 1, 1});
        final DisplayPose to = new DisplayPose();
        final float s = (float) Math.sin(Math.PI / 4);
        final float c = (float) Math.cos(Math.PI / 4);
        to.set(new double[] {10, 64, -4}, new double[] {0, 1, 0}, new float[] {0, s, 0, c}, new double[] {3, 3, 3});
        final DisplayPose mid = new DisplayPose();
        mid.lerp(from, to, 0.5);
        assertEquals(5, mid.x, EPS);
        assertEquals(-2, mid.z, EPS);
        assertEquals(0.5, mid.oy, EPS);
        assertEquals(2, mid.sx, EPS);
        assertEquals(Math.sin(Math.PI / 8), mid.qy, 1e-6, "half of a 90 degree turn is 45 degrees");
        assertEquals(Math.cos(Math.PI / 8), mid.qw, 1e-6);

        final DisplayPose flipped = new DisplayPose();
        flipped.set(new double[3], new double[3], new float[] {0, -s, 0, -c}, new double[] {1, 1, 1});
        mid.lerp(from, flipped, 0.5);
        assertEquals(Math.sin(Math.PI / 8), mid.qy, 1e-6, "the negated quaternion is the same rotation");

        assertEquals(0.25, DisplayPose.progress(1000, 400, 1100), EPS);
        assertEquals(1, DisplayPose.progress(1000, 400, 5000), EPS);
        assertEquals(0, DisplayPose.progress(1000, 400, 900), EPS);
        assertEquals(1, DisplayPose.progress(1000, 0, 1000), EPS);
        final DisplayPose zero = new DisplayPose();
        zero.set(new double[3], new double[3], new float[] {0, 0, 0, 0}, new double[] {1, 1, 1});
        assertEquals(1, zero.qw, EPS, "a zero quaternion becomes the identity");
    }

    @Test
    void worldMenuLayoutsPlaceAndPageEntries() {
        final double[] out = new double[2];
        WorldMenuLayout.RING.place(0, 4, 2, out);
        assertEquals(0, out[0], EPS);
        assertEquals(2, out[1], EPS, "the first ring entry is on top");
        WorldMenuLayout.RING.place(1, 4, 2, out);
        assertEquals(2, out[0], EPS, "then clockwise to the right");
        assertEquals(0, out[1], 1e-9);
        WorldMenuLayout.RING.place(0, 1, 2, out);
        assertEquals(0, out[1], EPS, "a single entry sits on the anchor");

        WorldMenuLayout.ROW.place(0, 3, 2, out);
        assertEquals(-1, out[0], EPS);
        WorldMenuLayout.ROW.place(2, 3, 2, out);
        assertEquals(1, out[0], EPS);
        WorldMenuLayout.COLUMN.place(0, 2, 1.5, out);
        assertEquals(0.375, out[1], EPS, "columns start on top");
        WorldMenuLayout.ROW.place(1, 2, 0.1, out);
        assertEquals(WorldMenuLayout.MIN_SPACING / 2, out[0], EPS, "spacing never drops below the minimum");

        WorldMenuLayout.GRID.place(0, 9, 2, out);
        assertEquals(-1, out[0], EPS);
        assertEquals(1, out[1], EPS);
        WorldMenuLayout.GRID.place(4, 9, 2, out);
        assertEquals(0, out[0], EPS);
        assertEquals(0, out[1], EPS, "the middle of a 3x3 grid is the anchor");

        assertEquals(3, WorldMenuLayout.RING.pages(17));
        assertEquals(1, WorldMenuLayout.RING.countOnPage(16, 17));
        assertEquals(8, WorldMenuLayout.RING.countOnPage(3, 17));
        assertEquals(1, WorldMenuLayout.GRID.pages(0));
        assertEquals(WorldMenuLayout.GRID, WorldMenuLayout.of("GRID"));
        assertEquals(WorldMenuLayout.RING, WorldMenuLayout.of("spiral"));

        assertEquals(0, WorldMenuLayout.cycle(-1, 1, 5));
        assertEquals(4, WorldMenuLayout.cycle(-1, -1, 5));
        assertEquals(0, WorldMenuLayout.cycle(4, 1, 5), "scrolling wraps around");
        assertEquals(3, WorldMenuLayout.cycle(0, -2, 5));
        assertEquals(-1, WorldMenuLayout.cycle(0, 1, 0));
    }

    @Test
    void gridRaycastFindsTheNearestOrientedBoxAndLocalHit() {
        final ElementGrid grid = new ElementGrid();
        final int far = grid.add(0.5, 64.5, -10, IDENTITY, 0.5, 0.5, 0.5, 0, -0.5, 0);
        final int near = grid.add(0.5, 64.5, -5, IDENTITY, 0.5, 0.5, 0.5, 0, 0, 0);
        assertEquals(2, grid.size());
        assertEquals(near, grid.raycast(0.5, 64.5, 0, 0, 0, -1, 32), "the nearer box wins");
        assertEquals(4.5, grid.hitDistance(), EPS);
        assertEquals(0.5, grid.localZ(), EPS, "hits are reported relative to the element anchor");
        assertEquals(-1, grid.raycast(0.5, 64.5, 0, 0, 0, -1, 4), "out of range");
        assertEquals(-1, grid.raycast(0.5, 64.5, 0, 0, 0, 1, 32), "behind the ray");

        grid.clear();
        assertEquals(-1, grid.raycast(0.5, 64.5, 0, 0, 0, -1, 32));
        // A thin panel turned 90 degrees about Y: its thin axis now lies along world x.
        final double h = Math.sqrt(0.5);
        grid.add(40, 70, 40, new double[] {0, h, 0, h}, 1, 1, 0.05, 0, 0, 0);
        assertEquals(0, grid.raycast(30, 70.2, 40, 1, 0, 0, 32), "rays cross chunk-sized cells to reach it");
        assertEquals(9.95, grid.hitDistance(), 1e-6);
        assertEquals(0.2, grid.localY(), 1e-6);
        assertEquals(-1, grid.raycast(30, 70.2, 41.5, 1, 0, 0, 32), "the turned panel is two blocks wide along z");
        assertEquals(0, far);
        grid.add(-20.5, 64, -20.5, IDENTITY, 0.5, 0.5, 0.5, 0, 0, 0);
        assertEquals(1, grid.raycast(-20.5, 64, -15, 0, 0, -1, 32), "negative coordinates use floored cells");
        assertEquals(0, grid.raycast(40, 70, 40, 0, 1, 0, 32), "an origin inside a box hits at distance 0");
        assertEquals(0, grid.hitDistance(), EPS);
    }

    @Test
    void burstClockCarriesFractionsAndCapsBacklogs() {
        final BurstClock clock = new BurstClock();
        assertEquals(0, clock.advance(5, 100));
        assertEquals(1, clock.advance(5, 100), "five per second fire every 200 ms");
        assertEquals(0, clock.advance(5, 100));
        assertEquals(BurstClock.MAX_BURSTS_PER_STEP, clock.advance(100, 60_000), "a pause does not flood");
        assertEquals(0, clock.advance(0, 1000));
        assertEquals(0, clock.advance(Double.NaN, 1000));
    }

    @Test
    void pointerSendsHoverClicksAndDrags() {
        final List<String> events = new ArrayList<>();
        final ElementPointer p = new ElementPointer((element, input, delta, key, local, lx, ly, lz) ->
                events.add(element + " " + input.wireName() + (delta != null ? " " + delta : "") + (key != null ? " " + key : "")
                        + (local ? String.format(java.util.Locale.ROOT, " %.2f", lx) : "")));
        assertFalse(p.press(ElementInput.PRIMARY, 0, 0, 0), "nothing hovered");
        p.hover("a");
        p.hover("a");
        p.hover("b");
        assertEquals(List.of("a hover_enter", "a hover_leave", "b hover_enter"), events);
        events.clear();

        assertTrue(p.press(ElementInput.SECONDARY, 0.1, 0, 0));
        assertTrue(p.press(ElementInput.PRIMARY, 0.2, 0, 0));
        assertTrue(p.dragging());
        p.hover(null);
        assertEquals("b", p.hovered(), "a drag keeps its element");
        p.move(0.201, 0, 0);
        p.move(0.5, 0, 0);
        assertTrue(p.scroll(2));
        assertTrue(p.key("key.keyboard.q"));
        assertTrue(p.release());
        assertFalse(p.release());
        assertEquals(List.of("b secondary 0.10", "b primary 0.20", "b drag_start 0.20", "b hover_leave", "b drag 0.50",
                "b scroll 2.0", "b key key.keyboard.q", "b drag_end 0.50"), events);
        events.clear();
        assertFalse(p.scroll(1), "nothing hovered after the drag");
        p.hover("c");
        p.reset();
        assertEquals(List.of("c hover_enter", "c hover_leave"), events);
    }
}
